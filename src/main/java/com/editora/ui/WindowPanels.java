package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * A window's tool-window panels that are built on first use (Project, Structure, File Information, Undo History,
 * Commit, Git Log, GitHub, Markdown Lint) and the small queries the Project panel is built with. The panels
 * themselves stay fields of {@link MainController}, which tests and the panels' readers reach by name; this builds
 * them into those fields. It lived inside {@code MainController}; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class WindowPanels {

    private final MainController mc;

    WindowPanels(MainController mc) {
        this.mc = mc;
    }

    /**
     * One lazily built tool window: its panel is made by {@code content} on the window's first open. The
     * title is the {@code toolwindow.<id>} message and the toggle command {@code tool.<id>}; the overload
     * below is for the two whose keys do not follow the id.
     */
    static ToolWindow toolWindow(
            String id,
            ToolWindow.Side side,
            java.util.function.Supplier<javafx.scene.Node> icon,
            java.util.function.Supplier<? extends Region> content) {
        return toolWindow(id, "toolwindow." + id, side, icon, content, "tool." + id);
    }

    static ToolWindow toolWindow(
            String id,
            String titleKey,
            ToolWindow.Side side,
            java.util.function.Supplier<javafx.scene.Node> icon,
            java.util.function.Supplier<? extends Region> content,
            String commandId) {
        return new ToolWindow(id, tr(titleKey), side, icon, content, commandId);
    }

    /** The Structure panel, built on its first use; it attaches the active buffer as it is built. */
    StructurePanel structurePanel() {
        if (mc.structurePanel == null) {
            mc.structurePanel = new StructurePanel(mc.bookmarkCoordinator, mc.notesCoordinator);
            mc.structurePanel.setNarrowActions(mc.editing.structureNarrowActions());
            mc.structurePanel.setSimpleMode(mc.chrome::simpleModeActive);
            mc.structurePanel.setChordLookup(id -> mc.keymap == null ? null : mc.keymap.displayChord(id));
            EditorBuffer active = mc.activeBuffer();
            mc.structurePanel.attach(active);
            mc.lspCoordinator.requestStructureSymbols(active); // the LSP outline, when the server offers one
        }
        return mc.structurePanel;
    }

    /** The File Information panel, built on its first use; it attaches the active buffer as it is built. */
    FileInformationPanel fileInfoPanel() {
        if (mc.fileInfoPanel == null) {
            mc.fileInfoPanel = new FileInformationPanel();
            mc.fileInfoPanel.attach(mc.activeBuffer());
        }
        return mc.fileInfoPanel;
    }

    /** The Undo History panel, built on its first use; it attaches the active buffer as it is built. */
    UndoHistoryPanel undoHistoryPanel() {
        if (mc.undoHistoryPanel == null) {
            mc.undoHistoryPanel = new UndoHistoryPanel();
            mc.undoHistoryPanel.attach(mc.activeBuffer());
        }
        return mc.undoHistoryPanel;
    }

    /** The Commit panel, built on its first use with the Git state the window has seen so far. */
    GitPanel gitPanel() {
        if (mc.gitPanel == null) {
            GitPanel panel = new GitPanel(new GitPanelActions(mc.git, mc.diffCoordinator, mc.fileWorkflows));
            panel.setOnClone(mc.git::cloneRepo);
            panel.setOnGenerateCommitMessage(mc.aiCoordinator::generateCommitMessage);
            if (mc.lastGitStatus != null) {
                panel.setStatus(mc.lastGitStatus);
            }
            if (mc.lastGitOperation != null) {
                panel.setOperation(mc.lastGitOperation);
            }
            if (mc.lastGitRefusal != null) {
                panel.setRefusal(mc.lastGitRefusal);
            }
            panel.setAiAvailable(mc.gitAiAvailable);
            mc.gitPanel = panel;
        }
        return mc.gitPanel;
    }

    /** The Git Log panel, built on its first use (the window's open loads the history into it). */
    GitLogPanel gitLogPanel() {
        if (mc.gitLogPanel == null) {
            mc.gitLogPanel = new GitLogPanel(mc.gitLogOps);
        }
        return mc.gitLogPanel;
    }

    /** The GitHub panel, built on its first use (the window's open fetches its list). */
    GitHubPanel githubPanel() {
        if (mc.githubPanel == null) {
            mc.githubPanel = new GitHubPanel(mc.gitWindows.githubActions());
        }
        return mc.githubPanel;
    }

    /** The Markdown Lint panel, built on its first use (the window's open runs the scan that fills it). */
    MarkdownLintPanel markdownLintPanel() {
        if (mc.markdownLintPanel == null) {
            mc.markdownLintPanel = new MarkdownLintPanel(
                    mc.previews.lint(),
                    MarkdownLintPanel.actions(
                            (file, line, col) -> mc.openAndGoto(file, line - 1, col - 1),
                            mc.previews::runMarkdownLintScan));
        }
        return mc.markdownLintPanel;
    }

    /**
     * The Project panel, built on its first use — the tool window's first open, or a reveal into it. Until
     * then the state it would show is tracked here (the root, the Git status, the active file, the settings)
     * and applied when it is built; refreshes that reach a panel not built yet are dropped, since the build
     * reads everything afresh.
     */
    ProjectPanel projectPanel() {
        if (mc.projectPanel != null) {
            return mc.projectPanel;
        }
        ProjectPanel panel = new ProjectPanel(
                mc.fileWorkflows::openPath,
                mc::onProjectFileRenamed,
                mc.projectActions::onProjectTreeFileDeleted,
                this::isPathModified,
                this::hasFileOpen,
                this::projectMapPreviewContent);
        panel.setEditorWindow(() -> mc.stage); // the Map window opens even while the Project tool window is closed
        panel.setMapSceneSetup(mc.toolWindows::setUpSecondaryScene);
        panel.setRememberedMapState(mc.config::getWorkspaceState, mc.config::save, mc::requestSave);
        panel.setOpenFiles(() -> mc.editorArea.tabs().stream()
                .map(MainController::tabPath)
                .filter(java.util.Objects::nonNull)
                .toList());
        panel.setPrompt(mc::promptText); // in-scene rename prompt
        panel.setPromptIn(host -> (title, label, initial, onAccept) ->
                mc.promptTextIn(host, title, label, initial, onAccept)); // the same, over the Map window
        panel.setOnFocusEditor(mc.toolWindows::focusEditor); // Escape on the Project Map
        panel.setDeletePreparation(mc.projectDeletes);
        panel.setOnNewFile(mc.templateActions::newFileOfType); // folder "New ▸ <type>"
        panel.setNewFileTypePicker(mc.templateActions::pickNewFileType); // Project: New File in Selected Folder…
        panel.setOnNewFromTemplate(mc.templateActions::newFromTemplate); // folder "New From Template…"
        panel.setRenameParticipant(mc.lspCoordinator.treeParticipant()); // a class goes with its file (B15)
        mc.lspCoordinator.setOnTreeMoveUndone(panel::forgetLastMove);
        panel.setMavenMenu(mc.mavenProjectCoordinator::mavenMenu);
        panel.setOnNewMavenProject(mc.mavenProjectCoordinator::newProject); // folder "New Maven Project…"
        panel.setOnStatus(mc::setStatus); // drag-move / multi-delete feedback in the status bar
        panel.setMapOutputActions(mc.exports::printProjectMap, mc.exports::exportProjectMapPdf);
        panel.setMapRootPinned( // the No Project window: its tree follows the active tab, an open Map does not
                () -> mc.windowProject == null && (mc.remoteCoordinator == null || !mc.remoteCoordinator.isMounted()));
        // An external program (a terminal `git`, another editor, a build) changed files under the repo while
        // Editora already had focus: re-evaluate the working-tree-anchored surfaces the focus-regain handler
        // also refreshes — Git status + the Commit stripe, build-tool markers, and open diffs (#529).
        panel.setOnExternalChanges((changes, complete) -> {
            com.editora.config.PathKeys.invalidateCanonicalCache(); // files moved on disk under us (#680)
            mc.indexCoordinator.onExternalChanges(changes, complete);
            mc.previews.lint().onExternalChanges(changes); // a changed .markdownlint* re-lints open buffers
            mc.lspCoordinator.projectFilesChanged(changes, complete); // a jkit.toml or .sdkmanrc edited elsewhere
            if (panel.mayChangeGitStatus(changes, complete)) {
                mc.git.refresh(); // not for a batch wholly in gitignored paths, a build's output (F2)
            }
            mc.refreshBuildTools();
            mc.diffCoordinator.refreshOpenDiffs();
            mc.fileWorkflows.checkRemoteExternalChanges(changes); // reported by a remote folder's host
        });
        panel.setFsChangeSink(mc.lspCoordinator::watchedFilesChanged); // didChangeWatchedFiles: no stale models (#677)
        panel.setMapGit(mc.git.service()); // the Map's recent changes, on the service's own activity lane
        panel.setOnReveal((p, dir) -> mc.revealInFileManager(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        panel.setOnOpenTerminal((p, dir) -> mc.tabActions.openTerminalAt(p, dir, com.editora.vfs.Vfs.isLocal(p)));
        panel.setFileActions(ProjectTreeFileActions.of(mc, mc::localHistoryEnabled));
        panel.setMarkerActions(new ProjectTreeMarkerActions(mc.bookmarkCoordinator, mc.notesCoordinator));
        panel.setCardGutterSource(MapCardGutterMarks.of(mc)); // breakpoints, bookmarks, ▶ and bars on Map cards
        if (mc.debugCoordinator != null) { // the Map follows the debugger's stops, its call stack picks frames
            panel.setOnDebugFramePicked(mc.debugCoordinator.mapFollow::selectFrameFromMap);
            mc.debugCoordinator.mapFollow.setOnMapStop(panel::setDebugStop);
        }
        panel.setMapOpenActions(mc::openAndNavigate, mc.projectActions::openNarrowedFromMap);
        panel.setMapServerSymbols(file -> mc.structurePanel == null ? null : mc.structurePanel.serverSymbolsFor(file));
        panel.shareIndexes(mc.indexCoordinator, mc.lspManager); // "@name" in the Map; Search Everywhere's files
        mc.lspCoordinator.setOnProblemsShown(panel::setMapProblems); // the Problems window's errors and warnings
        panel.setMapCardAids(
                () -> { // the Map's code cards read as the editor does: its settings
                    Settings s = mc.config.getSettings();
                    return new MapCardAids(
                            s.isSemanticHighlight(), s.isInlayHints(), s.getInlayHintMode(), s.isStickyScroll());
                });
        panel.setSimpleMode(mc.chrome::simpleModeActive);
        panel.setShowHidden(mc.config.getSettings().isProjectShowHidden());
        panel.setRespectGitignore(mc.config.getSettings().isSearchRespectGitignore());
        if (mc.projectGitStatus != null) {
            panel.setGitStatus(mc.projectGitStatus);
        }
        panel.setRoot(mc.projectRoot);
        EditorBuffer selected = mc.activeBuffer();
        panel.setActiveFile(selected == null ? null : selected.getPath());
        mc.projectPanel = panel;
        mc.remoteCoordinator.panelBuilt(panel);
        return panel;
    }

    /** Re-lists the Project tree if it exists; an unbuilt one lists afresh when built. */
    void refreshProjectTreeIfBuilt() {
        if (mc.projectPanel != null) {
            mc.projectPanel.refreshTree();
        }
    }

    void refreshProjectMarkersIfBuilt() {
        if (mc.projectPanel != null) {
            mc.projectPanel.refreshMarkers();
        }
    }

    /**
     * The editor area with the breadcrumb hung beneath it, as one node for the tool-window layout.
     *
     * <p>The breadcrumb names the <em>active file</em>, so it belongs to the editor rather than to the
     * window frame. Stacked in the bottom bar it sat between the bottom tool stripe and the status bar —
     * spanning the whole window, sandwiched between two full-width bars, and reading as a second status
     * bar describing the window rather than a path bar describing the file. Inside the horizontal split it
     * is only as wide as the editor, sits directly under the text it describes, and no longer crosses under
     * the left/right tool windows.
     *
     * <p>Wrapping is safe for the stripe alignment {@link ToolWindowManager} does on this node: it looks the
     * tab header up by style class through the whole subtree, and the breadcrumb is added at the bottom, so
     * the top edge it measures from is unchanged.
     */
    javafx.scene.Node editorFooter() {
        mc.editorEmptyState = new EditorEmptyState(mc.registry, mc.keymap, mc::projectsEnabled);
        BorderPane withBreadcrumb =
                new BorderPane(new javafx.scene.layout.StackPane(mc.editorArea.node(), mc.editorEmptyState.node()));
        withBreadcrumb.setBottom(mc.breadcrumb);
        return withBreadcrumb;
    }

    Region placeholder(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("tool-window-placeholder");
        label.setWrapText(true);
        StackPane wrapper = new StackPane(label);
        wrapper.setAlignment(javafx.geometry.Pos.CENTER);
        return wrapper;
    }

    /** True if {@code file} is open in a tab whose buffer has unsaved changes. */
    private boolean isPathModified(Path file) {
        Tab tab = mc.tabForPath(file);
        if (tab == null) {
            return false;
        }
        EditorBuffer buffer = MainController.bufferOf(tab);
        return buffer != null && buffer.isDirty();
    }

    /** Captures an open editor's current text for the Map's floating preview, including unsaved changes. */
    ProjectMapPreview.Content projectMapPreviewContent(Path file) {
        EditorBuffer buffer = MainController.bufferOf(mc.tabForPath(file));
        if (buffer == null) {
            return ProjectMapPreview.Content.closed(mc.editorConfigCharsetFor(file));
        }
        var area = buffer.getArea();
        int length = area.getLength();
        int end = Math.min(length, ProjectMapPreview.MAX_OUTLINE_CHARS); // a card cuts its own (C2)
        return ProjectMapPreview.Content.buffer(() -> area.getText(0, end), end < length, buffer.docVersion());
    }

    /**
     * Whether this window already has {@code file} open. Used by {@link WindowManager} to route an
     * externally-delivered launch to the window that already holds the file, rather than opening a second
     * buffer on it in a new window — two independent buffers over one file is an edit-loss hazard rather than
     * mere clutter, since saving one leaves the other stale behind an external-change prompt.
     */
    boolean hasFileOpen(Path file) {
        return file != null && mc.tabForPath(file) != null;
    }
}
