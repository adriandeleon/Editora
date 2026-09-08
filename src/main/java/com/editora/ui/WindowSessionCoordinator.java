package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javafx.application.Platform;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import com.editora.ui.MainController.OpenTarget;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/** Restores and persists window sessions and handles startup targets. */
final class WindowSessionCoordinator {
    interface Host {

        TabPane tabPane();

        EditorArea editorArea();

        Stage stage();

        ConfigManager config();

        ProjectPanel projectPanel();

        ProjectManager projects();

        ToolWindowManager toolWindows();

        BookmarkCoordinator bookmarkCoordinator();

        DebugCoordinator debugCoordinator();

        Set<Tab> pinned();

        void showWelcomeIfNoTabs();

        void refreshProjectPanelList();

        boolean projectsEnabled();

        void updateWindowTitle();

        NavigationCoordinator navigation();

        PreviewCoordinator previews();

        WindowChromeCoordinator chrome();

        FileWorkflowCoordinator fileWorkflows();

        DiffCoordinator diffCoordinator();

        LspCoordinator lspCoordinator();

        NotesCoordinator notesCoordinator();

        EditorBuffer openBackgroundBuffer(Path target);

        void setStatus(String message);

        Tab addBuffer(EditorBuffer buffer);

        Tab addBuffer(EditorBuffer buffer, boolean select);

        Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings);

        void updateTabMeta(Tab tab, EditorBuffer buffer);

        void clearLoading(EditorBuffer buffer);

        void discardLoading(EditorBuffer buffer);

        EditorBuffer bufferOf(Tab tab);

        Tab tabForPath(Path file);

        Path tabPath(Tab tab);

        Path windowProjectRoot();

        void restoreFolds(EditorBuffer buffer);

        Tab tabForBuffer(EditorBuffer buffer);

        void restoreReadOnly(EditorBuffer buffer);
    }

    private final Host host;

    WindowSessionCoordinator(Host host) {
        this.host = host;
    }

    /**
     * Restores last session's open files (with their carets); falls back to one empty buffer.
     *
     * <p>Two phases so the UI is responsive immediately: first every tab is created (empty), so all
     * tab headers show at once; then content + folds are filled one file per pulse — the active file
     * first. Filling a non-selected tab is cheap (its editor isn't rendered), so a heavily-folded
     * background file can't freeze startup. Tab order and pinning are preserved.
     */
    public void openInitialBuffer() {
        WorkspaceState state = host.config().getWorkspaceState();
        List<WorkspaceState.OpenFile> files = new ArrayList<>();
        for (WorkspaceState.OpenFile f : skipSessionFiles ? List.<WorkspaceState.OpenFile>of() : state.getOpenFiles()) {
            // parseStorable reconstructs a local path directly, or a remote (sftp://) one via the resolver —
            // which is null until its connection is open, so a remote entry is skipped at startup rather than
            // reopened as a same-named *local* file.
            Path rp = f.getPath() == null || f.getPath().isBlank()
                    ? null
                    : com.editora.vfs.Vfs.parseStorable(f.getPath());
            if (rp != null && Files.isReadable(rp)) {
                files.add(f);
            }
        }
        if (files.isEmpty()) {
            // No session to restore (empty, or --no-session): open the CLI action's file (--new-file / FILE
            // target) synchronously, so it's on the first frame exactly as in the with-session path — there
            // is nothing to restore around it, so there's no reason to wait a pulse. Then flip the
            // restore-complete bookkeeping, and show Welcome only if nothing got opened.
            runPendingStartupAction(hasStartupWork);
            runPendingAfterRestore(); // action already ran when hasStartupWork; this does the bookkeeping
            Platform.runLater(host::showWelcomeIfNoTabs);
            return;
        }
        String activePath = state.getActiveFile();
        List<EditorBuffer> buffers = new ArrayList<>();
        int activeIndex = 0;
        for (int i = 0; i < files.size(); i++) {
            if (files.get(i).getPath().equals(activePath)) {
                activeIndex = i;
                break;
            }
        }
        // A command-line FILE must be on screen from the first frame — otherwise the session's own active
        // file is selected + filled first and the user watches an unrelated file (with its LSP starting)
        // before theirs appears. So: if the requested file is itself part of the session, make *its* tab the
        // selected one and fill it first; if it isn't (or --new-file was given), open it up front so the
        // restored tabs are appended around it without ever stealing the selection.
        int cliIndex = indexOfStartupTarget(files);
        if (cliIndex < 0 && pendingAfterRestore != null && hasStartupWork) {
            runPendingStartupAction(true); // synchronous: its tab must exist before any restored tab
        }
        int selectIndex = cliIndex >= 0 ? cliIndex : (hasStartupWork ? -1 : activeIndex);
        // Rebuild the split shape *before* opening anything, so each file can be inserted straight into its
        // group rather than opened into one group and moved afterwards.
        com.editora.config.EditorGroupLayout layout = state.getEditorLayout();
        host.editorArea().restoreLayout(layout);
        for (int i = 0; i < files.size(); i++) {
            WorkspaceState.OpenFile f = files.get(i);
            host.editorArea().setRestoreTargetGroup(layout == null ? -1 : f.getGroup());
            Path p = com.editora.vfs.Vfs.parseStorable(f.getPath()); // non-null: the filter above kept only readable
            boolean active = i == selectIndex;
            // A raster image restores into the read-only image viewer (a null buffer placeholder keeps the
            // per-file fill indices aligned; fillSessionFiles skips nulls).
            if (ImageFormats.isSupported(p.getFileName().toString())) {
                Tab tab = host.fileWorkflows().openImageTab(p, active);
                if (f.isPinned()) {
                    host.pinned().add(tab);
                }
                buffers.add(null);
                continue;
            }
            // A PDF restores into the read-only PDF viewer (null placeholder keeps the fill indices aligned).
            if (PdfViewerPane.isPdf(p.getFileName().toString())) {
                Tab tab = host.fileWorkflows().openPdfTab(p, active);
                if (f.isPinned()) {
                    host.pinned().add(tab);
                }
                buffers.add(null);
                continue;
            }
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(p); // sets the tab title/language; content comes later
            buffer.setHeavyFile(true); // suppress LSP/minimap while this restored tab is only a shell
            buffer.setViewMode(true);
            host.fileWorkflows().loadingBuffers.add(buffer);
            Tab tab = host.addBuffer(buffer, active, false);
            if (f.isPinned()) {
                host.pinned().add(tab);
                host.updateTabMeta(tab, buffer);
            }
            buffers.add(buffer);
        }
        host.editorArea().setRestoreTargetGroup(-1);
        if (layout != null) {
            // A saved file can be gone from disk; a group that loses every file would otherwise come back as
            // a blank pane the user has to close by hand.
            host.editorArea().pruneEmptyGroups();
            host.editorArea().applyRestoredSelection(layout);
            Tab active = selectIndex >= 0 && selectIndex < host.editorArea().size()
                    ? host.editorArea().tabs().get(selectIndex)
                    : null;
            if (active != null) {
                host.editorArea().select(active); // re-assert the active file after the per-group selections
            }
        }
        // Fill order: the selected file first (the CLI target when there is one, else the session's active
        // file), then the rest in tab order.
        int firstIndex = selectIndex >= 0 ? selectIndex : activeIndex;
        List<Integer> order = new ArrayList<>();
        order.add(firstIndex);
        for (int i = 0; i < files.size(); i++) {
            if (i != firstIndex) {
                order.add(i);
            }
        }
        fillSessionFiles(files, buffers, order, 0);
    }

    /** Fills one restored buffer per pulse (in {@code order}), keeping the UI responsive between files. */
    void fillSessionFiles(List<WorkspaceState.OpenFile> files, List<EditorBuffer> buffers, List<Integer> order, int k) {
        if (k >= order.size()) {
            runPendingAfterRestore(); // session fully restored — now safe to apply CLI targets
            return;
        }
        Platform.runLater(() -> {
            int i = order.get(k);
            EditorBuffer buffer = buffers.get(i);
            Runnable continued = () -> {
                if (k == 0) {
                    // The requested file (already selected + just filled) is the one the CLI action targets,
                    // so run it now rather than after the whole restore — a caret jump needs its content.
                    runPendingStartupAction(false);
                    // Keep background tabs out of the visible file's first rendered frames.
                    afterNextPaint(() -> fillSessionFiles(files, buffers, order, k + 1));
                    return;
                }
                fillSessionFiles(files, buffers, order, k + 1);
            };
            if (buffer == null) { // image/PDF/hex viewer: nothing to fill
                continued.run();
            } else {
                fillSessionBuffer(files.get(i), buffer, continued);
            }
        });
    }

    /**
     * Runs {@code action} once the current content has had a frame to paint. A pulse's {@code handle()} runs
     * at the <em>start</em> of a pulse, before that pulse renders, so two ticks is what proves a frame
     * completed — the same reasoning as the startup instrumentation's first-paint mark.
     */
    static void afterNextPaint(Runnable action) {
        new javafx.animation.AnimationTimer() {
            private int ticks;

            @Override
            public void handle(long now) {
                if (++ticks >= 2) {
                    stop();
                    action.run();
                }
            }
        }.start();
    }

    /**
     * Opens OS-delivered files in this window (macOS Finder "Open With" — routed here by
     * {@code MacOpenFiles.install} → {@code WindowManager.openExternalFiles}). Each target is opened like any
     * other file (an already-open file just re-focuses its tab) and, when it carries one, jumped to its line.
     */
    public void openExternalFiles(java.util.List<OpenTarget> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        // The command line's own path, rather than a second one beside it. The two used to differ — this one
        // could not honour a line number — and a difference between them is invisible until someone opens the
        // same file both ways and gets two different results.
        applyStartupTargets(files, null);
    }

    /** A one-shot action applying the command-line startup targets; see {@link #runPendingStartupAction}. */
    Runnable pendingAfterRestore;

    /** The command-line {@code FILE} targets, consulted by {@link #openInitialBuffer()} to pick the tab to
     *  select + fill first, so the requested file is on screen from the first frame. */
    List<OpenTarget> startupTargets = List.of();

    /** True when the command line asked for anything to be opened (a {@code FILE} target or --new-file). */
    boolean hasStartupWork;

    /** {@code --no-session}: don't restore the saved session's open files (see {@link #startup}). */
    boolean skipSessionFiles;

    /** Standalone diff startup is asynchronous, so don't insert Welcome while its worker is reading files. */
    boolean suppressWelcome;

    /**
     * The index in {@code files} of the first command-line {@code FILE} target that is also part of the
     * restored session, or {@code -1} (no targets, or none of them is a session file).
     */
    int indexOfStartupTarget(List<WorkspaceState.OpenFile> files) {
        for (OpenTarget t : startupTargets) {
            Path want = t.file().toAbsolutePath().normalize();
            for (int i = 0; i < files.size(); i++) {
                Path p = com.editora.vfs.Vfs.parseStorable(files.get(i).getPath());
                if (p != null && p.toAbsolutePath().normalize().equals(want)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Runs the one-shot command-line startup action (opening {@code FILE} targets / --new-file), if it hasn't
     * run yet. {@code now} runs it synchronously — used when its tab must exist before the restored tabs are
     * appended; otherwise it's deferred one pulse.
     */
    void runPendingStartupAction(boolean now) {
        Runnable r = pendingAfterRestore;
        pendingAfterRestore = null;
        if (r == null) {
            return;
        }
        if (now) {
            r.run();
        } else {
            Platform.runLater(r);
        }
    }

    /**
     * Startup entry point (replaces the bare {@code openInitialBuffer()} call): optionally activates a
     * project, restores the session, then — once restore completes — opens any command-line files (jumping to
     * line:column), additive on top of the restored session. With no arguments it's exactly the old
     * {@code openInitialBuffer()}. The chrome flags are applied earlier, by {@link #applyStartupChrome}.
     */
    public void startup(Path projectDir, List<OpenTarget> targets, String newFile) {
        startup(projectDir, targets, newFile, false);
    }

    /**
     * As above; {@code noSession} ({@code --no-session}) skips the saved session's files entirely and opens
     * only what the command line asked for.
     *
     * <p>For a file-manager "Open With" launch the saved tabs are pure cost — every restored file is a buffer
     * to load and highlight, and (once looked at) a language server to run, for files the user didn't ask to
     * see. Measured on an 8-file session opening one file: 4 fewer server processes, roughly half the CPU
     * Editora burns while starting, ~225 MB less. The saved session is left untouched, so the next ordinary
     * launch restores everything as usual.
     */
    public void startup(Path projectDir, List<OpenTarget> targets, String newFile, boolean noSession) {
        if (projectDir != null && host.projectsEnabled()) {
            activateStartupProject(projectDir); // swap to the project's session before it's restored
        }
        this.skipSessionFiles = noSession;
        startupTargets = targets == null ? List.of() : List.copyOf(targets);
        hasStartupWork = !startupTargets.isEmpty() || newFile != null;
        // The CLI action still runs after the requested file's own content is in place (so a restored caret
        // can't override a requested line:column) — but openInitialBuffer now front-loads that one file
        // rather than waiting for the whole session to finish restoring.
        pendingAfterRestore = () -> applyStartupTargets(targets, newFile);
        openInitialBuffer();
    }

    /**
     * Starts the transient {@code --diff-ui} workspace: no saved tabs are restored and the only content is
     * the asynchronously loaded comparison. The normal session is left untouched on exit.
     */
    public void startupDiffUi(Path left, Path right) {
        skipSessionFiles = true;
        suppressWelcome = true;
        startupTargets = List.of();
        hasStartupWork = true;
        pendingAfterRestore = () -> {
            Path leftPath = left == null ? null : left.toAbsolutePath().normalize();
            Path rightPath = right == null ? null : right.toAbsolutePath().normalize();
            if (!readableDiffPath(leftPath) || !readableDiffPath(rightPath)) {
                Path bad = !readableDiffPath(leftPath) ? leftPath : rightPath;
                host.chrome().exitDiffUiMode();
                suppressWelcome = false;
                host.showWelcomeIfNoTabs();
                host.setStatus(tr("status.diff.unreadable", bad == null ? "" : bad));
                return;
            }
            if (Files.isDirectory(leftPath) != Files.isDirectory(rightPath)) {
                host.chrome().exitDiffUiMode();
                suppressWelcome = false;
                host.showWelcomeIfNoTabs();
                host.setStatus(tr("status.diff.pathTypeMismatch"));
                return;
            }
            host.diffCoordinator().comparePaths(leftPath, rightPath);
        };
        openInitialBuffer();
    }

    static boolean readableDiffPath(Path path) {
        return path != null && (Files.isRegularFile(path) || Files.isDirectory(path)) && Files.isReadable(path);
    }

    void applyStartupTargets(List<OpenTarget> targets, String newFile) {
        if (newFile != null) {
            // --new-file[=NAME]: open a fresh buffer instead of the Welcome page. "" = blank untitled.
            EditorBuffer buffer = new EditorBuffer();
            if (!newFile.isBlank()) {
                buffer.setDisplayName(newFile);
            }
            host.addBuffer(buffer, true);
            host.setStatus(newFile.isBlank() ? tr("status.newBuffer") : tr("status.newFile", newFile));
        }
        if (targets != null) {
            for (OpenTarget t : targets) {
                host.fileWorkflows().openPath(t.file().toAbsolutePath().normalize(), true);
            }
            if (targets.stream().anyMatch(t -> t.line() > 0)) {
                // Defer once more so it runs after openPath's own goToStart for any newly-opened file.
                Platform.runLater(() -> {
                    for (OpenTarget t : targets) {
                        if (t.line() > 0) {
                            gotoInFile(t.file().toAbsolutePath().normalize(), t.line(), t.column());
                        }
                    }
                });
            }
        }
    }

    /** Marks the restore complete, running the CLI startup action first if it hasn't already run. */
    void runPendingAfterRestore() {
        runPendingStartupAction(false);
        openMainClassForRunConfig();
    }

    /**
     * Opens the class a saved Java run configuration launches, when the restored session has no Java file.
     *
     * <p>A Java launch resolves its classpath through jdtls <b>routed via an open Java file</b>
     * ({@link com.editora.run.RunConfigRouting}), so a project whose session holds only {@code pom.xml}
     * cannot run its own saved configuration — it reports "open a Java file from the project", which reads
     * like a bug when the configuration is sitting right there in the toolbar.
     *
     * <p>Deliberately conservative, so it can't be a surprise:
     *
     * <ul>
     *   <li>only when this window has a project and a Java configuration with a real main class;
     *   <li>only when <b>no</b> Java file was restored — an existing Java tab already routes fine;
     *   <li>only if the file actually exists under the project root (no disk search, just the standard
     *       source layouts — see {@link com.editora.run.MainClassSource});
     *   <li>in the <b>background</b> when other tabs restored, so it never steals the tab the user left on.
     * </ul>
     */
    void openMainClassForRunConfig() {
        Path root = host.windowProjectRoot();
        if (root == null) {
            return;
        }
        String fqn = mainClassOfSelectedJavaConfig();
        if (fqn == null) {
            return;
        }
        boolean hasJavaOpen = false;
        boolean hasAnyTab = false;
        for (Tab t : host.tabPane().getTabs()) {
            EditorBuffer b = host.bufferOf(t);
            hasAnyTab = true;
            if (b != null && b.getPath() != null && "java".equals(b.getLanguage())) {
                hasJavaOpen = true;
                break;
            }
        }
        if (hasJavaOpen) {
            return; // an open Java tab already gives the launch something to route through
        }
        for (String relative : com.editora.run.MainClassSource.candidates(fqn)) {
            Path candidate = root.resolve(relative);
            if (java.nio.file.Files.isRegularFile(candidate)) {
                if (hasAnyTab) {
                    host.openBackgroundBuffer(candidate); // don't steal the tab the session restored
                } else {
                    host.fileWorkflows().openPath(candidate);
                }
                return;
            }
        }
    }

    /** The main class of this window's selected Java run configuration (else the first one), or null. */
    String mainClassOfSelectedJavaConfig() {
        List<com.editora.config.RunConfiguration> configs =
                host.config().getWorkspaceState().getRunConfigurations();
        if (configs == null || configs.isEmpty()) {
            return null;
        }
        String selected = host.config().getWorkspaceState().getSelectedRunConfig();
        com.editora.config.RunConfiguration chosen = configs.stream()
                .filter(c -> c.name().equals(selected))
                .findFirst()
                .orElse(configs.get(0));
        // A file name in the field is a mistake caught with its own message at launch; do not act on it here.
        return chosen.isJava() && !chosen.missingMainClass() && !chosen.mainClassLooksLikeAFile()
                ? chosen.mainClass()
                : null;
    }

    /** Activates {@code dir} as the active project (startup-safe; no open buffers to confirm). */
    void activateStartupProject(Path dir) {
        Path root = dir.toAbsolutePath().normalize();
        String name = root.getFileName() == null
                ? root.toString()
                : root.getFileName().toString();
        Project p = host.projects().createOrGet(name, root);
        host.projects().setActive(p.id());
        host.projects().save();
        host.config().setWorkspaceStateFile(host.projects().stateFile(p)); // openInitialBuffer() then restores it
        host.projectPanel().setRoot(Path.of(p.root()));
        host.refreshProjectPanelList();
        host.updateWindowTitle();
    }

    /** Selects the tab for {@code file} (if open) and moves the caret to a 1-based line/column. */
    void gotoInFile(Path file, int line1, int col1) {
        gotoInFile(file, line1, col1, true);
    }

    /** Jumps to {@code file}:{@code line1}:{@code col1}; {@code focusEditor} false leaves focus where it is. */
    void gotoInFile(Path file, int line1, int col1, boolean focusEditor) {
        NavigationHistory.Location origin = (host.navigation().suppressNavRecord || host.navigation().navigating)
                ? null
                : host.navigation().captureCurrent();
        Tab tab = host.tabForPath(file);
        if (tab == null) {
            return;
        }
        host.editorArea().select(tab);
        EditorBuffer buffer = host.bufferOf(tab);
        if (host.fileWorkflows().loadingBuffers.contains(buffer)) {
            host.fileWorkflows()
                    .afterBufferLoad
                    .computeIfAbsent(buffer, ignored -> new ArrayList<>())
                    .add(() -> gotoInFile(file, line1, col1, focusEditor));
            return;
        }
        CodeArea area = buffer.getArea();
        int total = area.getParagraphs().size();
        int line = Math.max(1, Math.min(total, line1)) - 1;
        int col = 0;
        if (col1 > 0) {
            int lineLen = area.getParagraphLength(line);
            col = Math.max(1, Math.min(lineLen + 1, col1)) - 1;
        }
        buffer.getFoldManager().unfoldContaining(line);
        int targetLine = line;
        int targetCol = col;
        area.moveTo(targetLine, targetCol);
        if (!host.navigation().suppressNavRecord && !host.navigation().navigating) {
            host.navigation().recordJump(origin, new NavigationHistory.Location(file, targetLine, targetCol));
        }
        area.requestFollowCaret();
        Platform.runLater(() -> {
            try {
                area.showParagraphAtTop(targetLine);
            } catch (RuntimeException ignored) {
                // Viewport not ready; ignore.
            }
        });
        if (focusEditor) {
            area.requestFocus();
        }
    }

    /** Loads a restored tab's content, large-file mode, folds, and caret (the tab already exists). */
    void fillSessionBuffer(WorkspaceState.OpenFile f, EditorBuffer buffer, Runnable onComplete) {
        Path file = Path.of(f.getPath());
        host.fileWorkflows().fileLoadExecutor.execute(() -> {
            try {
                FileWorkflowCoordinator.PreparedLoad load = host.fileWorkflows().prepareLoad(file, true);
                Platform.runLater(() -> {
                    Tab tab = host.tabForBuffer(buffer);
                    if (tab != null) {
                        if (load.binary()) {
                            boolean selected = host.editorArea().selectedTab() == tab;
                            boolean wasPinned = host.pinned().remove(tab);
                            host.discardLoading(buffer);
                            host.editorArea().remove(tab);
                            Tab hex = host.fileWorkflows().openHexTab(load.file(), selected);
                            if (wasPinned) {
                                host.pinned().add(hex);
                            }
                        } else {
                            buffer.setViewMode(false);
                            finishSessionBuffer(f, buffer, load);
                            host.clearLoading(buffer);
                        }
                    } else {
                        host.discardLoading(buffer);
                    }
                    onComplete.run();
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    host.discardLoading(buffer); // unreadable now — leave the restored tab empty
                    onComplete.run();
                });
            }
        });
    }

    void finishSessionBuffer(
            WorkspaceState.OpenFile f, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
        String note = host.fileWorkflows().applyPreparedLoad(buffer, load);
        host.fileWorkflows().notePerfContentLoaded(buffer);
        if (!note.isEmpty()) {
            host.setStatus(note);
        }
        host.restoreFolds(buffer);
        host.bookmarkCoordinator().restoreBookmarks(buffer);
        host.debugCoordinator().restoreBreakpoints(buffer);
        host.notesCoordinator().restoreNotes(buffer);
        host.restoreReadOnly(buffer);
        host.previews().restoreMarkdownMode(buffer);
        // The tab was set up before content loaded; start or close its server now that its real tier is known.
        host.lspCoordinator().syncBuffer(buffer);
        CodeArea area = buffer.getArea();
        int caret = Math.max(0, Math.min(f.getCaret(), area.getLength()));
        area.moveTo(caret);
        scrollRestoredCaretIntoView(buffer, SCROLL_SETTLE_ATTEMPTS);
    }

    /** Pulses to keep defending a restored buffer's scroll while the layout is still moving under it. */
    static final int SCROLL_SETTLE_ATTEMPTS = 24;

    /**
     * Parks a restored buffer's viewport on its saved caret, retrying while the viewport isn't ready.
     *
     * <p>{@code replaceText} leaves the caret — and the viewport — at the <em>end</em> of the document (the
     * same reason {@link EditorBuffer#goToStart()} exists), so until the view is positioned the file paints
     * scrolled to its tail. {@code moveTo} alone doesn't move the viewport, and this scroll used to be a
     * single {@code Platform.runLater} whose failure was swallowed: on a freshly-added tab the viewport
     * isn't laid out yet, that one attempt did nothing, and the file sat at its tail until some unrelated
     * event happened to scroll it — for a command-line file, the {@code requestFocus} in {@code openPath},
     * which is why opening from the file manager showed a visible scroll jump (the line numbers running
     * from the end of the file back to the top).
     *
     * <p>So: try in this same pulse — when the tab is already laid out that removes the jump entirely — and
     * otherwise retry on the next few pulses until the viewport has a height to scroll. Bounded, so a
     * background tab that never lays out costs at most {@link #SCROLL_SETTLE_ATTEMPTS} no-op calls.
     *
     * <p>Positioning it once isn't enough, though: the virtual flow <b>re-anchors itself to the top during a
     * later layout pass</b> — stack-traced to {@code Parent.layout → VirtualFlow.layoutChildren →
     * Navigator.fillViewportFrom → CellListManager.cropTo}, with no app code involved — which showed as a
     * file restored deep in the document flashing to line 1 and back. (A second, distinct collapse from the
     * one inside {@code setStyleSpans} that {@code EditorBuffer.setStyleSpansPreservingScroll} handles.) So
     * once the target offset is known, a listener defends it for the settle window: a drop to the top while
     * we wanted a non-zero offset is put back <em>in the same pulse</em>, before that frame renders.
     * Correcting it a pulse later instead — the obvious poll — only turns the jump into a bounce.
     */
    void scrollRestoredCaretIntoView(EditorBuffer buffer, int attemptsLeft) {
        CodeArea area = buffer.getArea();
        double[] targetY = {-1}; // the scroll offset the restore produced, once it's known
        boolean[] applying = {false}; // re-entrancy guard: our own set fires the listener again
        javafx.beans.value.ChangeListener<Number> hold = (o, ov, nv) -> {
            if (applying[0] || targetY[0] <= 1 || nv == null || nv.doubleValue() > 1) {
                return;
            }
            applying[0] = true;
            try {
                area.estimatedScrollYProperty().setValue(targetY[0]);
            } catch (RuntimeException ignored) {
                // Flow not in a state to scroll; the pulse loop will retry.
            } finally {
                applying[0] = false;
            }
        };
        area.estimatedScrollYProperty().addListener(hold);
        holdRestoredScroll(
                buffer,
                targetY,
                attemptsLeft,
                () -> area.estimatedScrollYProperty().removeListener(hold));
    }

    /** Positions the viewport once the area is laid out, then disarms the hold listener. */
    void holdRestoredScroll(EditorBuffer buffer, double[] targetY, int attemptsLeft, Runnable disarm) {
        CodeArea area = buffer.getArea();
        try {
            if (targetY[0] <= 1 && area.getHeight() > 0) { // not positioned yet, and now laid out
                area.showParagraphAtTop(area.getCurrentParagraph());
                Double y = area.estimatedScrollYProperty().getValue();
                targetY[0] = y == null ? -1 : y; // a file restored at the top needs no defending
            }
        } catch (RuntimeException ignored) {
            // Viewport not ready; retry below.
        }
        if (attemptsLeft > 1) {
            Platform.runLater(() -> holdRestoredScroll(buffer, targetY, attemptsLeft - 1, disarm));
            return;
        }
        disarm.run();
        // The minimap's first render can run before layout settles; refresh once it has.
        buffer.refreshMinimap();
    }

    void persistSession() {
        if (skipSessionFiles) {
            // --no-session opened only the command line's file, so this window's tab list is *not* the
            // session — writing it back would replace the user's saved tabs with the single file they
            // happened to open from the file manager. Window bounds are equally unrepresentative here, so
            // the whole session write is skipped and the saved session is left exactly as it was.
            return;
        }
        List<WorkspaceState.OpenFile> files = new ArrayList<>();
        for (Tab tab : host.editorArea().tabs()) {
            EditorBuffer buffer = host.bufferOf(tab);
            Path p = host.tabPath(tab); // buffer or image-viewer path (image tabs restore too)
            if (p != null) {
                int caret = buffer != null ? buffer.getArea().getCaretPosition() : 0;
                // Vfs.toStorableString keeps a remote file's sftp:// URI — a bare path would be reopened as a
                // *local* file on restart (a same-named local file could silently open in its place).
                files.add(new WorkspaceState.OpenFile(
                        com.editora.vfs.Vfs.toStorableString(p),
                        caret,
                        host.pinned().contains(tab),
                        host.editorArea().groupIndexOf(tab)));
            }
        }
        WorkspaceState state = host.config().getWorkspaceState();
        state.setOpenFiles(files);
        // Same predicate the loop above filters on, so the saved selection index counts the same tabs.
        state.setEditorLayout(host.editorArea().snapshotLayout(t -> host.tabPath(t) != null));
        Path activePath = host.tabPath(host.editorArea().selectedTab());
        state.setActiveFile(activePath != null ? com.editora.vfs.Vfs.toStorableString(activePath) : "");
        persistWindowBounds(state);
        host.toolWindows().persistDividers(); // capture a divider dragged but left open (close() only saves on hide)
        restoreCliFocusToolWindows(state);
        host.config().save(); // durable flush on quit — not coalesced
    }

    /**
     * Undoes the persisted side-effects of a {@code --zen}/{@code --expert} session override, so the flag
     * really is session-only: entering the mode closed the docked tool windows and {@code close()} persisted
     * "nothing open", which would otherwise lose them on the next (flagless) launch. Only fills a side the
     * user hasn't since reopened by hand, and leaves the pre-focus snapshots out of the saved file.
     */
    void restoreCliFocusToolWindows(WorkspaceState state) {
        if (host.chrome().cliFocusToolWindows == null) {
            return;
        }
        if (state.getOpenLeftToolWindow().isEmpty()) {
            state.setOpenLeftToolWindow(host.chrome().cliFocusToolWindows[0]);
        }
        if (state.getOpenRightToolWindow().isEmpty()) {
            state.setOpenRightToolWindow(host.chrome().cliFocusToolWindows[1]);
        }
        if (state.getOpenBottomToolWindow().isEmpty()) {
            state.setOpenBottomToolWindow(host.chrome().cliFocusToolWindows[2]);
        }
        state.getPreZenToolWindows().clear(); // a snapshot of a mode that was never saved as "on"
        state.getPreExpertToolWindows().clear();
    }

    /** Records the main window's geometry. When maximized, keep the last normal bounds so
     *  un-maximizing on the next launch restores a sensible size. */
    void persistWindowBounds(WorkspaceState state) {
        if (host.stage() == null) {
            return;
        }
        state.setWindowMaximized(host.stage().isMaximized());
        if (!host.stage().isMaximized()) {
            state.setWindowX(host.stage().getX());
            state.setWindowY(host.stage().getY());
            state.setWindowWidth(host.stage().getWidth());
            state.setWindowHeight(host.stage().getHeight());
        }
    }
}
