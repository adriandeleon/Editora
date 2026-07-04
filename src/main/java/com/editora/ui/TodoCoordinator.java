package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.TodoMark;
import com.editora.editor.TodoMatcher;
import com.editora.todo.TodoGrouping;
import com.editora.todo.TodoMatch;
import com.editora.todo.TodoPattern;
import com.editora.todo.TodoPatterns;
import com.editora.todo.TodoScanner;
import com.editora.todo.TodoService;

import static com.editora.i18n.Messages.tr;

/**
 * TODO / highlight-pattern feature (in-editor highlight matcher + the tool-window project/open-files scan +
 * the {@code todo.*} commands), extracted from {@link MainController} via the {@link CoordinatorHost} pattern.
 * Owns the {@link TodoService} + the {@link TodoPanel}; {@code MainController} keeps the {@code ToolWindow}
 * (built with {@link #panel()}) plus the {@code view.toggleTodoHighlight} command (it routes through the
 * generic {@code toggleSetting} helper and just delegates its apply to {@link #applyHighlight()}).
 */
final class TodoCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (project root, open-in-editor, the TODO tool window). */
    interface Ops {
        /** This window's project root, or {@code null} for the no-project window (scan open files only). */
        Path projectRoot();

        /** Opens {@code file} and jumps to {@code line}/{@code col} (a clicked TODO result). */
        void openMatch(Path file, int line, int col);

        boolean isToolWindowOpen();

        void toggleToolWindow();

        /** Home-collapsed display form of an absolute path (shared with the search-scope label). */
        String homeCollapsed(String absolutePath);

        /**
         * Opens {@code file}, and if its 1-based {@code line} still reads exactly {@code expectedLine}, replaces
         * that paragraph with {@code newLine} as one undoable edit (marks the buffer dirty), then runs
         * {@code afterApply}. A no-op with a status message when the line changed since the scan.
         */
        void applyLineEdit(Path file, int line, String expectedLine, String newLine, Runnable afterApply);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final TodoService service = new TodoService();
    private final TodoPanel panel;

    private CommandRegistry registry;
    private TodoMatcher matcher = text -> List.of();
    /** Effective highlight on/off (Settings.todoHighlight). */
    private boolean highlightOn;

    TodoCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.panel = new TodoPanel(new TodoPanel.Actions() {
            @Override
            public void openMatch(Path file, int line, int col) {
                ops.openMatch(file, line, col);
            }

            @Override
            public void refresh() {
                runScan();
            }

            @Override
            public void setPriority(Path file, TodoMatch m, String priority) {
                applyEdit(
                        file,
                        m,
                        com.editora.todo.TodoEdit.withPriority(m.lineText(), m.col() - 1, m.parsed(), priority));
            }

            @Override
            public void setKeyword(Path file, TodoMatch m, String keyword) {
                applyEdit(
                        file, m, com.editora.todo.TodoEdit.withKeyword(m.lineText(), m.col() - 1, m.parsed(), keyword));
            }

            @Override
            public void editDescription(Path file, TodoMatch m) {
                host.promptText(
                        tr("todo.editDescription.title"),
                        tr("todo.editDescription.label"),
                        m.parsed().description(),
                        text -> applyEdit(
                                file,
                                m,
                                com.editora.todo.TodoEdit.withDescription(
                                        m.lineText(), m.col() - 1, m.parsed(), text)));
            }
        });
        // Persist any group-by change the user makes. The lambda reads settings lazily (config isn't set at
        // construction time — the coordinator is a MainController field), and the restore below is guarded so
        // it doesn't re-save the value it just restored.
        panel.setGroupByChangeHandler(g -> {
            if (restoringGroupBy) {
                return;
            }
            host.settings().setTodoGroupBy(g.name());
            host.requestSave();
        });
    }

    /** True while {@link #restoreGroupByOnce} is programmatically setting the group-by (suppresses re-saving). */
    private boolean restoringGroupBy;

    private boolean groupByRestored;

    /** Restores the persisted "Group by" dimension once, on the first {@link #applyHighlight} (config is ready). */
    private void restoreGroupByOnce() {
        if (groupByRestored) {
            return;
        }
        groupByRestored = true;
        restoringGroupBy = true;
        panel.setGroupBy(parseGroupBy(host.settings().getTodoGroupBy()));
        restoringGroupBy = false;
    }

    /** Parses a persisted "Group by" name back to the enum, defaulting to {@code FILE} for anything unknown. */
    private static TodoGrouping.GroupBy parseGroupBy(String name) {
        try {
            return TodoGrouping.GroupBy.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            return TodoGrouping.GroupBy.FILE;
        }
    }

    /** Rewrites the match's source line (undoable) via the controller, then re-scans if the panel is open. */
    private void applyEdit(Path file, TodoMatch m, String newLine) {
        if (m.parsed() == null || newLine.equals(m.lineText())) {
            return;
        }
        ops.applyLineEdit(file, m.line(), m.lineText(), newLine, this::refreshPanelIfOpen);
    }

    TodoPanel panel() {
        return panel;
    }

    /** Sorts the TODO tree so the active file's group is on top (pass the normalized path; on tab switch). */
    void setActiveFile(java.nio.file.Path normalizedActive) {
        panel.setActiveFile(normalizedActive);
    }

    void registerCommands(CommandRegistry registry) {
        this.registry = registry;
        registry.register(Command.of("tool.todo", ops::toggleToolWindow));
        registry.register(Command.of("todo.refresh", () -> {
            if (!ops.isToolWindowOpen()) {
                ops.toggleToolWindow();
            }
            runScan();
        }));
        registry.register(Command.of("todo.addPattern", this::promptAddPattern));
        registry.register(Command.of("todo.next", () -> jumpTodo(true)));
        registry.register(Command.of("todo.previous", () -> jumpTodo(false)));
    }

    /** Moves the caret to the next/previous TODO match in the active buffer (wrapping). */
    private void jumpTodo(boolean forward) {
        EditorBuffer b = host.activeBuffer();
        boolean found = b != null && (forward ? b.jumpToNextTodo() : b.jumpToPreviousTodo());
        if (!found) {
            host.setStatus(tr("status.todo.none"));
        }
    }

    /** Compiles the configured patterns and pushes the highlight matcher + on/off to every buffer. On by
     *  default; the project / open-files scan in the TODO tool window is lazy (refreshed on demand). */
    void applyHighlight() {
        restoreGroupByOnce(); // first apply (init) runs after config is set — safe to read the saved group-by
        Settings s = host.settings();
        highlightOn = s.isTodoHighlight();
        List<TodoPatterns.Compiled> compiled = highlightOn ? TodoPatterns.compile(s.getTodoPatterns()) : List.of();
        matcher = text -> toTodoMarks(TodoScanner.scan(text, compiled));
        host.forEachBuffer(this::applyToBuffer);
        refreshPanelIfOpen();
    }

    /** Pushes the current matcher + enabled state to one buffer (used by applyHighlight + addBuffer). */
    void applyToBuffer(EditorBuffer b) {
        if (b == null) {
            return;
        }
        b.setTodoMatcher(matcher);
        b.setTodoHighlightEnabled(highlightOn);
    }

    /**
     * Maps pure scanner matches to the editor's neutral highlight marks: the keyword offsets + 0-based line +
     * color, plus the absolute {@code [tag]} / {@code (priority)} spans (with their part colors) when the
     * comment parsed structurally, so the overlay can color each part. Part offsets are line-relative in the
     * parse, so they're rebased to absolute via the line start ({@code keywordStart − (col − 1)}).
     */
    private static List<TodoMark> toTodoMarks(List<TodoMatch> matches) {
        List<TodoMark> out = new ArrayList<>(matches.size());
        for (TodoMatch m : matches) {
            int line0 = Math.max(0, m.line() - 1);
            com.editora.todo.TodoComment p = m.parsed();
            if (p == null) {
                out.add(new TodoMark(m.start(), m.end(), line0, m.lineText(), m.color()));
                continue;
            }
            int lineStart = m.start() - (m.col() - 1); // absolute offset of the line's first char
            int tagStart = p.hasTag() ? lineStart + p.tagStart() : -1;
            int tagEnd = p.hasTag() ? lineStart + p.tagEnd() : -1;
            int priStart = p.hasPriority() ? lineStart + p.priorityStart() : -1;
            int priEnd = p.hasPriority() ? lineStart + p.priorityEnd() : -1;
            out.add(new TodoMark(
                    m.start(),
                    m.end(),
                    line0,
                    m.lineText(),
                    m.color(),
                    tagStart,
                    tagEnd,
                    p.hasTag() ? com.editora.todo.TodoColors.TAG_COLOR : null,
                    priStart,
                    priEnd,
                    com.editora.todo.TodoColors.priorityColor(p.priority())));
        }
        return out;
    }

    /** Re-runs the TODO tool-window scan if it's open (e.g. after the patterns changed). */
    void refreshPanelIfOpen() {
        if (ops.isToolWindowOpen()) {
            runScan();
        }
    }

    /** Scans for TODO/highlight matches and fills the tool window: the open project's tree when a project
     *  is open, else just the open buffers. Off-thread (TodoService); no-op when the feature is off. */
    void runScan() {
        refreshScope(); // keep the panel's "scanning in" label in step with what we scan
        Settings s = host.settings();
        if (!s.isTodoHighlight()) {
            panel.setResults(new TodoService.Outcome(List.of(), 0, 0, false));
            return;
        }
        List<TodoPatterns.Compiled> compiled = TodoPatterns.compile(s.getTodoPatterns());
        Map<Path, String> open = new HashMap<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null) {
                open.put(b.getPath().toAbsolutePath().normalize(), b.getContent());
            }
        });
        // Scope: THIS window's project tree (Ops.projectRoot), else (the "No Project" window) only the open
        // files — the global window must not inherit a project root from another window.
        Path root = ops.projectRoot();
        service.setRespectGitignore(s.isSearchRespectGitignore()); // same "exclude .gitignore" setting as search
        host.setStatus(tr("todo.scanning"));
        service.scan(compiled, root, open, outcome -> {
            panel.setResults(outcome);
            host.setStatus(
                    outcome.totalMatches() == 0
                            ? tr("todo.none")
                            : tr("todo.summary", outcome.totalMatches(), outcome.fileCount()));
        });
    }

    /** Pushes the current TODO scan scope to the panel's label: this window's project tree (when one is
     *  open), else the open-files-only scope — matching what {@link #runScan} actually scans. */
    private void refreshScope() {
        Path root = ops.projectRoot();
        if (root == null) {
            panel.setScope(tr("search.scopeOpenFiles"), null);
            return;
        }
        String full = root.toString();
        panel.setScope(ops.homeCollapsed(full), full);
    }

    /** Quick-add a highlight pattern from the palette (name → regex); full editing is in Settings. */
    private void promptAddPattern() {
        host.promptText(tr("todo.addPattern.title"), tr("todo.addPattern.nameLabel"), "", name -> {
            if (name == null || name.isBlank()) {
                return;
            }
            String suggested = "\\b" + Pattern.quote(name.strip()) + "\\b";
            host.promptText(tr("todo.addPattern.title"), tr("todo.addPattern.regexLabel"), suggested, regex -> {
                if (regex == null || regex.isBlank()) {
                    return;
                }
                Settings s = host.settings();
                List<TodoPattern> list = new ArrayList<>(s.getTodoPatterns());
                list.add(new TodoPattern(name.strip(), regex.strip(), TodoPatterns.DEFAULT_COLOR, false, true));
                s.setTodoPatterns(list);
                host.requestSave();
                applyHighlight();
                host.syncSettingsWindow();
                host.setStatus(tr("status.todo.patternAdded", name.strip()));
            });
        });
    }

    void shutdown() {
        service.shutdown();
    }
}
