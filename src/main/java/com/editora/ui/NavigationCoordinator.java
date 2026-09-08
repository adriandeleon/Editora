package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Orientation;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.RecentFiles;
import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/** Owns navigation pickers, history and folding actions. */
final class NavigationCoordinator {
    interface Host {
        WindowSessionCoordinator sessions();

        WindowChromeCoordinator chrome();

        FileWorkflowCoordinator fileWorkflows();

        EditorArea editorArea();

        Stage stage();

        KeymapManager keymap();

        OverlayHost overlayHost();

        com.editora.snippet.SnippetManager snippets();

        ProjectManager projects();

        ToolWindowManager toolWindows();

        StructurePanel structurePanel();

        RecentFiles recentFiles();

        boolean projectsEnabled();

        void navigateToLine(int line);

        void activateAndFocusTab(Tab tab);

        List<Tab> openTabsForSwitcher();

        String homeCollapsed(String full);

        EditingCoordinator editing();

        GitCoordinator git();

        IndexCoordinator indexCoordinator();

        LspCoordinator lspCoordinator();

        void openAndGoto(Path file, int line0, int col0);

        java.util.Map<String, String> invertBindings();

        void splitEditorGroup(Orientation orientation);

        void setStatus(String message);

        EditorBuffer activeBuffer();

        java.util.List<com.editora.editor.UndoHistory.Checkpoint> undoHistoryCheckpoints();

        void restoreUndoCheckpoint(com.editora.editor.UndoHistory.Checkpoint c);

        CodeArea activeArea();

        Tab addBuffer(EditorBuffer buffer);

        Tab addBuffer(EditorBuffer buffer, boolean select);

        Tab addBuffer(EditorBuffer buffer, boolean select, boolean resolvePathSettings);

        void openRecent(Path file);

        void openPathPreview(Path file);

        EditorBuffer bufferOf(Tab tab);

        Tab tabForPath(Path file);

        void persistFolds(EditorBuffer buffer);
    }

    QuickOpen<com.editora.snippet.Snippet> snippetPalette;
    private final Host host;

    NavigationCoordinator(Host host) {
        this.host = host;
    }

    /** Back/forward jump list of visited editor locations. */
    final NavigationHistory navHistory = new NavigationHistory();

    /** True while a back/forward jump is executing — suppresses re-recording the jump into history. */
    boolean navigating;

    /** True while {@code openAndGoto} drives {@code gotoInFile} — so only the outer call records the jump. */
    boolean suppressNavRecord;

    QuickOpen<Path> recentPalette;

    QuickOpen<StructurePanel.Outline> structurePalette;

    QuickOpen<Tab> openFilesPalette;

    QuickOpen<ToolWindow> toolWindowPalette;

    QuickOpen<ToolWindow> splitToolWindowPalette;

    QuickOpen<com.editora.editor.UndoHistory.Checkpoint> undoHistoryPalette;

    QuickOpen<NavigationHistory.Location> recentLocationsPalette;

    QuickOpen<Path> relatedPalette;

    FileFinder fileFinder;

    FileFinder folderFinder;

    /** Builds the keyboard "Jump to…" pickers (recent files, structure) — command-palette-style popups. */
    void setupJumpPickers() {
        recentPalette = new QuickOpen<>(
                "Jump to Recent File",
                "Type to filter recent files…",
                () -> List.copyOf(host.recentFiles().getList()),
                p -> p.getFileName() == null ? p.toString() : p.getFileName().toString(),
                p -> p.getParent() == null ? "" : p.getParent().toString(),
                host::openRecent);
        recentPalette.setItemIcon(p -> FileIcons.forFileName(
                p.getFileName() == null ? p.toString() : p.getFileName().toString()));
        structurePalette = new QuickOpen<>(
                "Jump to Structure",
                "Type to filter symbols…",
                () -> host.structurePanel().outline(),
                StructurePanel.Outline::label,
                StructurePanel.Outline::kind,
                entry -> host.navigateToLine(entry.line()));
        openFilesPalette = new QuickOpen<>(
                "Jump to Open File",
                "Type to filter open files…",
                host::openTabsForSwitcher,
                tab -> (isTabDirty(tab) ? "• " : "") + bufferTitle(tab), // dirty marker, like the tab
                tab -> bufferParentDir(tab),
                tab -> bufferTitle(tab), // search by the plain name (no "• " prefix)
                host::activateAndFocusTab);
        openFilesPalette.setItemStyleClass(
                tab -> isTabDirty(tab) ? "dirty-name" : null); // amber/italic, like a dirty tab
        openFilesPalette.setItemIcon(tab -> FileIcons.forFileName(bufferTitle(tab))); // file-type glyph
        toolWindowPalette = new QuickOpen<>(
                "Jump to Tool Window",
                "Type to filter tool windows…",
                () -> host.toolWindows().getRegisteredToolWindows().stream()
                        .filter(tw -> host.git().isEnabled() || !"tool.commit".equals(tw.getCommandId()))
                        .filter(tw -> host.projectsEnabled() || !"tool.project".equals(tw.getCommandId()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)),
                ToolWindow::getTitle,
                tw -> host.invertBindings().getOrDefault(tw.getCommandId(), ""),
                host.toolWindows()::open);
        splitToolWindowPalette = new QuickOpen<>(
                tr("toolwindow.openInSplit"),
                tr("toolwindow.splitPrompt"),
                () -> host.toolWindows().getRegisteredToolWindows().stream()
                        .filter(host.toolWindows()::canSplitWith)
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new)),
                ToolWindow::getTitle,
                tw -> host.invertBindings().getOrDefault(tw.getCommandId(), ""),
                host.toolWindows()::openInSplit);
        undoHistoryPalette = new QuickOpen<>(
                tr("toolwindow.undoHistory"),
                tr("undoHistory.popupPrompt"),
                host::undoHistoryCheckpoints,
                c -> c.linePreview().isEmpty() ? tr("undoHistory.blankLine") : c.linePreview(),
                NavigationCoordinator::undoCheckpointTime, // detail column = the capture time
                host::restoreUndoCheckpoint);
        recentLocationsPalette = new QuickOpen<>(
                tr("nav.recentLocations.title"),
                tr("nav.recentLocations.prompt"),
                () -> new ArrayList<>(navHistory.recent()),
                loc -> loc.snippet().isEmpty() ? tr("nav.recentLocations.blankLine") : loc.snippet(),
                NavigationCoordinator::locationLabel, // detail column = file:line
                // Matching the snippet alone would make "the file I was in" unfindable, and matching the
                // label alone would make "the line about X" unfindable; the row shows both, so both match.
                loc -> loc.snippet() + " " + locationLabel(loc),
                loc -> host.openAndGoto(loc.path(), loc.line(), loc.column()));
        recentLocationsPalette.setPreview(this::previewLocation, this::restorePreviewOrigin);
        relatedPalette = new QuickOpen<>(
                tr("related.title"),
                tr("related.prompt"),
                () -> new ArrayList<>(relatedCandidates),
                path -> path.getFileName().toString(),
                path -> host.homeCollapsed(path.toString()),
                host.fileWorkflows()::openPath);
        relatedPalette.setOverlayHost(host.overlayHost());
        snippetPalette = new QuickOpen<>(
                "Insert Snippet",
                "Type to filter snippets…",
                () -> {
                    EditorBuffer b = host.activeBuffer();
                    return new ArrayList<>(host.snippets().forLanguage(b == null ? "global" : b.getLanguage()));
                },
                s -> s.prefix() + " — " + s.name(),
                com.editora.snippet.Snippet::description,
                s -> {
                    EditorBuffer b = host.activeBuffer();
                    if (b != null) {
                        b.insertSnippet(s);
                    }
                });
        fileFinder = new FileFinder(this::finderStartDir, this::findFileChosen);
    }

    /** Start directory for the keyboard file finder: the active file's folder, else the home dir. */
    Path finderStartDir() {
        EditorBuffer buffer = host.activeBuffer();
        Path path = buffer == null ? null : buffer.getPath();
        if (path != null && path.getParent() != null) {
            return path.getParent();
        }
        Project active = host.projects() == null ? null : host.projects().active();
        if (active != null) {
            return Path.of(active.root());
        }
        return Path.of(System.getProperty("user.home", "."));
    }

    /** Opens an existing file, or creates a new buffer for a not-yet-existing path (written on save). */
    void findFileChosen(Path target) {
        if (Files.isRegularFile(target)) {
            host.fileWorkflows().openPath(target);
            return;
        }
        Tab existing = host.tabForPath(target);
        if (existing != null) {
            host.editorArea().select(existing);
            EditorBuffer existingBuffer = host.bufferOf(existing);
            if (existingBuffer != null) {
                existingBuffer.getArea().requestFocus();
            }
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(target);
        host.addBuffer(buffer);
        host.setStatus(tr("status.newFile", target.getFileName()));
    }

    String bufferTitle(Tab tab) {
        EditorBuffer b = host.bufferOf(tab);
        if (b != null) {
            return b.getTitle();
        }
        // Non-buffer tabs (the Welcome tab) carry their title in the TabContent (the tab text is empty
        // because the title lives in a draggable graphic header).
        return tab != null && tab.getUserData() instanceof com.editora.editor.TabContent tc ? tc.title() : "";
    }

    String bufferParentDir(Tab tab) {
        EditorBuffer b = host.bufferOf(tab);
        Path p = b == null ? null : b.getPath();
        return p == null || p.getParent() == null ? "" : p.getParent().toString();
    }

    /** Whether {@code tab}'s buffer has unsaved changes (used to mark dirty files in the pickers). */
    boolean isTabDirty(Tab tab) {
        EditorBuffer b = host.bufferOf(tab);
        return b != null && b.isDirty();
    }

    /** The active file-backed buffer's current caret location (0-based), or {@code null} when none. */
    NavigationHistory.Location captureCurrent() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            return null;
        }
        CodeArea a = b.getArea();
        return a == null
                ? null
                : new NavigationHistory.Location(b.getPath(), a.getCurrentParagraph(), a.getCaretColumn());
    }

    /** Records a jump into the back/forward history: the {@code origin} we left, then the {@code dest}. */
    void recordJump(NavigationHistory.Location origin, NavigationHistory.Location dest) {
        if (dest == null) {
            return;
        }
        if (origin != null) {
            navHistory.record(withSnippet(origin));
        }
        navHistory.record(withSnippet(dest));
    }

    /** Cap on a recorded line's text, so one minified line can't sit in the history at full length. */
    static final int MAX_LOCATION_SNIPPET = 120;

    /**
     * Attaches the location's line text, so a recent-locations list can show what is there rather than
     * only where it is. Both call sites record after the target buffer is on screen, so the line is
     * readable; a location whose file is not open keeps an empty snippet rather than putting a disk read
     * on the navigation path.
     */
    NavigationHistory.Location withSnippet(NavigationHistory.Location loc) {
        if (loc == null || !loc.snippet().isEmpty()) {
            return loc;
        }
        return new NavigationHistory.Location(loc.path(), loc.line(), loc.column(), lineTextAt(loc.path(), loc.line()));
    }

    /** The trimmed, length-capped text of {@code line} in an open buffer for {@code path}; "" if not open. */
    String lineTextAt(Path path, int line) {
        Tab tab = host.tabForPath(path);
        EditorBuffer buffer = tab == null ? null : host.bufferOf(tab);
        CodeArea area = buffer == null ? null : buffer.getArea();
        if (area == null || line < 0 || line >= area.getParagraphs().size()) {
            return "";
        }
        String text = area.getParagraph(line).getText().strip();
        return text.length() <= MAX_LOCATION_SNIPPET ? text : text.substring(0, MAX_LOCATION_SNIPPET) + "…";
    }

    /** {@code nav.back}: return to the previous location in the jump list. */
    void navBack() {
        NavigationHistory.Location loc = navHistory.back();
        if (loc == null) {
            host.setStatus(tr("status.nav.noBack"));
            return;
        }
        navigating = true;
        host.openAndGoto(loc.path(), loc.line(), loc.column()); // clears `navigating` in its runLater
    }

    /** {@code nav.forward}: go to the next location in the jump list (after going back). */
    void navForward() {
        NavigationHistory.Location loc = navHistory.forward();
        if (loc == null) {
            host.setStatus(tr("status.nav.noForward"));
            return;
        }
        navigating = true;
        host.openAndGoto(loc.path(), loc.line(), loc.column());
    }

    /**
     * {@code nav.recentLocations}: a picker over the places visited in this session, newest first.
     *
     * <p>The counterpart to Recent Files, and a different question: recent files answers "which file",
     * this answers "where in it" — which is what you actually lost when a jump took you elsewhere. It
     * reads the same trail Back walks, so the two can never disagree about where you have been.
     */
    void showRecentLocations() {
        if (navHistory.recent().isEmpty()) {
            host.setStatus(tr("status.nav.noRecentLocations"));
            return;
        }
        previewOrigin = captureCurrent();
        recentLocationsPalette.show(host.stage());
    }

    /** Where the editor was when a previewing picker opened, so cancelling can put it back. */
    NavigationHistory.Location previewOrigin;

    /**
     * Shows a highlighted location in the editor without committing to it: no focus change, and nothing
     * recorded in the jump list, so browsing the picker cannot itself become history.
     *
     * <p>A location whose file is not open is opened into the <em>preview slot</em>, so arrowing down a
     * list of twenty locations leaves one tab rather than twenty. Before preview tabs existed this case
     * was skipped entirely, which made the feature useless for exactly the locations you had navigated
     * away from.
     */
    void previewLocation(NavigationHistory.Location loc) {
        if (loc == null) {
            return;
        }
        if (host.tabForPath(loc.path()) == null) {
            if (!java.nio.file.Files.isReadable(loc.path())) {
                return; // deleted or unreadable since it was visited
            }
            suppressNavRecord = true;
            try {
                host.openPathPreview(loc.path());
            } finally {
                suppressNavRecord = false;
            }
            if (host.tabForPath(loc.path()) == null) {
                return;
            }
        }
        suppressNavRecord = true;
        try {
            host.sessions().gotoInFile(loc.path(), loc.line() + 1, loc.column() + 1, false);
        } finally {
            suppressNavRecord = false;
        }
    }

    /** Puts the editor back where it was before previewing — the picker was dismissed, not used. */
    void restorePreviewOrigin() {
        NavigationHistory.Location origin = previewOrigin;
        previewOrigin = null;
        previewLocation(origin);
    }

    /**
     * {@code search.everywhere}: one picker over commands, project files and symbols.
     *
     * <p>Seeded from a single-line selection, like the find bar and Find in Files — if you have selected
     * the thing you are looking for, retyping it is busywork.
     */
    void showSearchEverywhere() {
        if (host.chrome().searchEverywherePopup == null) {
            return; // overlay host not installed yet (very early in init)
        }
        host.chrome().searchEverywherePopup.show(host.editing().singleLineSelection());
    }

    /**
     * {@code nav.relatedFile}: jump between a file and its counterpart — a test and its subject, a header
     * and its implementation, a component and its stylesheet.
     *
     * <p>The candidate names come from the pure {@link com.editora.search.RelatedFiles}; this half decides
     * which of them exist. A counterpart rarely sits beside its partner (a test lives under
     * {@code src/test}, a header under {@code include}), so the sibling directory is only the first place
     * looked — the project index knows every file, and matching on name there finds the rest for free.
     *
     * <p>One match opens; several offer a picker, since {@code Foo.css} and {@code Foo.scss} can both
     * exist and only the user knows which was meant.
     */
    void gotoRelatedFile() {
        EditorBuffer b = host.activeBuffer();
        Path current = b == null ? null : b.getPath();
        if (current == null) {
            host.setStatus(tr("status.related.noFile"));
            return;
        }
        List<String> names =
                com.editora.search.RelatedFiles.candidates(current.getFileName().toString());
        if (names.isEmpty()) {
            host.setStatus(tr("status.related.none", current.getFileName()));
            return;
        }
        host.indexCoordinator().ensureBuilt(() -> openRelated(current, names));
    }

    void openRelated(Path current, List<String> names) {
        List<Path> found = new ArrayList<>();
        Path dir = current.getParent();
        for (String name : names) {
            // Beside the file first: when a counterpart IS a sibling that is nearly always the right one,
            // and it costs a single exists() rather than a scan.
            if (dir != null) {
                Path sibling = dir.resolve(name);
                if (java.nio.file.Files.isRegularFile(sibling) && !sibling.equals(current)) {
                    found.add(sibling);
                }
            }
            for (IndexCoordinator.FileHit hit : host.indexCoordinator().searchFiles(name, 20)) {
                if (hit.file().getFileName().toString().equals(name)
                        && !hit.file().equals(current)
                        && !found.contains(hit.file())) {
                    found.add(hit.file());
                }
            }
        }
        if (found.isEmpty()) {
            host.setStatus(tr("status.related.none", current.getFileName()));
            return;
        }
        if (found.size() == 1) {
            host.fileWorkflows().openPath(found.get(0));
            return;
        }
        relatedCandidates = found;
        relatedPalette.show(host.stage());
    }

    List<Path> relatedCandidates = List.of();

    /**
     * {@code lsp.gotoDefinitionInSplit}: open the definition beside the code that referenced it.
     *
     * <p>The pair you want on screen together is the call and the thing called — comparing them is the
     * usual reason for going there at all. Splitting after the jump puts the definition in the new group
     * and leaves the origin where it was.
     *
     * <p>A definition in the SAME file is jumped to without splitting: there is only one tab, so the split
     * would move it and leave the group it came from empty.
     */
    void gotoDefinitionInSplit() {
        EditorBuffer origin = host.activeBuffer();
        Path originPath = origin == null ? null : origin.getPath();
        host.lspCoordinator().gotoDefinition(() -> {
            EditorBuffer landed = host.activeBuffer();
            Path landedPath = landed == null ? null : landed.getPath();
            if (landedPath != null && !landedPath.equals(originPath)) {
                host.splitEditorGroup(Orientation.HORIZONTAL);
            }
        });
    }

    /** {@code file.java:214} — a location's file and 1-based line, for a picker's detail column. */
    static String locationLabel(NavigationHistory.Location loc) {
        return loc.path().getFileName() + ":" + (loc.line() + 1);
    }

    void foldAll() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.foldAll();
            host.setStatus(tr("status.foldedAll"));
        }
    }

    void unfoldAll() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.unfoldAll();
            host.setStatus(tr("status.unfoldedAll"));
        }
    }

    void foldAtCaret() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().foldAtCaret();
        }
    }

    void unfoldAtCaret() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().unfoldAtCaret();
        }
    }

    void toggleFoldAtCaret() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().toggleFoldAtCaret();
        }
    }

    /** Folds every region at the given fold level (1-based); see {@code FoldManager.foldLevel}. */
    void foldLevel(int level) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().foldLevel(level);
            host.setStatus(tr("status.foldLevel", level));
        }
    }

    /** Creates a manual fold range from the selection and collapses it (VS Code parity). */
    void createFoldFromSelection() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (buffer.createManualFoldFromSelection()) {
            host.persistFolds(buffer);
            host.setStatus(tr("status.fold.manualCreated"));
        } else {
            host.setStatus(tr("status.fold.manualNeedsSelection"));
        }
    }

    /** Removes every manual fold range in the active buffer. */
    void removeManualFolds() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        int n = buffer.removeManualFolds();
        host.persistFolds(buffer);
        host.setStatus(tr("status.fold.manualRemoved", n));
    }

    void foldAllExcept() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().foldAllExceptCaret();
        }
    }

    void unfoldAllExcept() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().unfoldAllExceptCaret();
        }
    }

    void foldAllBlockComments() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            host.setStatus(tr("status.fold.comments", buffer.getFoldManager().foldAllBlockComments()));
        }
    }

    void foldAllMarkerRegions() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            host.setStatus(tr("status.fold.markers", buffer.getFoldManager().foldAllMarkerRegions()));
        }
    }

    void unfoldAllMarkerRegions() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            host.setStatus(
                    tr("status.fold.markersUnfolded", buffer.getFoldManager().unfoldAllMarkerRegions()));
        }
    }

    void foldRecursively() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().foldRecursivelyAtCaret();
        }
    }

    void unfoldRecursively() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.getFoldManager().unfoldRecursivelyAtCaret();
        }
    }

    /** Moves the caret to {@code target}'s header line, revealing it if it's hidden inside a fold. */
    void gotoFold(com.editora.editor.FoldRegions.Region target) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (target == null) {
            host.setStatus(tr("status.fold.noTarget"));
            return;
        }
        int line = target.startLine();
        buffer.getFoldManager().unfoldContaining(line);
        host.editing().moveAndFollow(a -> a.moveTo(line, 0));
    }

    void gotoParentFold() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = host.activeArea();
        if (buffer != null && area != null) {
            gotoFold(com.editora.editor.FoldTree.parentFold(
                    buffer.getFoldManager().regions(), area.getCurrentParagraph()));
        }
    }

    void gotoNextFold() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = host.activeArea();
        if (buffer != null && area != null) {
            gotoFold(com.editora.editor.FoldTree.nextFold(
                    buffer.getFoldManager().regions(), area.getCurrentParagraph()));
        }
    }

    void gotoPreviousFold() {
        EditorBuffer buffer = host.activeBuffer();
        CodeArea area = host.activeArea();
        if (buffer != null && area != null) {
            gotoFold(com.editora.editor.FoldTree.previousFold(
                    buffer.getFoldManager().regions(), area.getCurrentParagraph()));
        }
    }

    /** Prompts for a 1-based line number and moves the caret there (clamped to the document). */
    void goToLine() {
        CodeArea area = host.activeArea();
        if (area == null) {
            return;
        }
        int total = area.getParagraphs().size();
        // Sits near the top like the palette and every other overlay card (it used to be centered in the
        // middle of the window, which made it jump relative to the rest), with a muted note re-reminding
        // the user of the line:column notation.
        Label promptLabel = new Label(tr("dialog.goToLine.content", total));
        TextField field = new TextField(String.valueOf(area.getCurrentParagraph() + 1));
        field.setPrefColumnCount(32);
        com.editora.command.TextInputKeymap.install(field, host.keymap());
        Label note = new Label(tr("dialog.goToLine.header"));
        note.getStyleClass().add("overlay-note");
        note.setWrapText(true);
        VBox body = new VBox(6, promptLabel, field, note);
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.goToLine.title"),
                body,
                field,
                tr("dialog.goToLine.button"),
                null,
                () -> handleGoToLine(field.getText(), area, total),
                null,
                false,
                false);
    }

    /** Parses {@code input} as {@code line} or {@code line:column} and moves the caret there. */
    void handleGoToLine(String input, CodeArea area, int total) {
        {
            String text = input.trim();
            String[] parts = text.split(":", 2);
            try {
                int line = Math.max(1, Math.min(total, Integer.parseInt(parts[0].trim()))) - 1;
                int column = 0; // 0-based; default to the start of the line
                if (parts.length > 1 && !parts[1].trim().isEmpty()) {
                    int lineLen = area.getParagraphLength(line);
                    column = Math.max(1, Math.min(lineLen + 1, Integer.parseInt(parts[1].trim()))) - 1;
                }
                int targetLine = line;
                int targetColumn = column;
                EditorBuffer buffer = host.activeBuffer();
                if (buffer != null) {
                    buffer.getFoldManager().unfoldContaining(targetLine);
                }
                host.editing().moveAndFollow(a -> a.moveTo(targetLine, targetColumn));
                host.setStatus(tr("status.gotoResult", targetLine + 1, targetColumn + 1));
            } catch (NumberFormatException e) {
                host.setStatus(tr("status.gotoInvalid", input));
            }
        }
    }

    /** The checkpoint's capture time as {@code HH:mm:ss} for the popup's detail column. */
    static String undoCheckpointTime(com.editora.editor.UndoHistory.Checkpoint c) {
        return UNDO_TIME.format(java.time.Instant.ofEpochMilli(c.epochMillis()));
    }

    private static final java.time.format.DateTimeFormatter UNDO_TIME =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss").withZone(java.time.ZoneId.systemDefault());
}
