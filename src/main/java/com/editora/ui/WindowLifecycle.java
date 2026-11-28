package com.editora.ui;

import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

/**
 * A window's lifecycle steps: what it re-checks when it regains focus, closing it (programmatically or once a save
 * picked in its question lands), quitting the app, and releasing everything it holds when it closes, viewer tabs
 * included. It lived inside {@code MainController}; it is a file of its own only because of that file's size (see
 * {@link WindowCommandHost}).
 */
final class WindowLifecycle {

    private final MainController mc;

    WindowLifecycle(MainController mc) {
        this.mc = mc;
    }

    /** The window gained focus: re-check what another program may have changed while we were away — unless
     *  this is the focus gained by being shown ({@link StartupFocus}), which repeats nothing. */
    void onWindowFocusGained() {
        StartupFocus.Action action = mc.startupFocus.onFocusGained();
        if (action == StartupFocus.Action.STARTUP_REFRESH) {
            mc.git.refresh(); // the project root is known only since setWindowContext
            mc.refreshBuildTools();
        }
        if (action != StartupFocus.Action.REGAIN) {
            return;
        }
        // The filesystem may have shifted while we were away — drop cached canonical paths (#680).
        com.editora.config.PathKeys.invalidateCanonicalCache();
        mc.fileWorkflows.checkExternalChanges();
        mc.git.refresh(); // another tool may have changed the repo while we were away
        mc.refreshBuildTools(); // a marker file (or the active file's project) may have changed while away
        mc.toolbar.refreshPasteState(); // clipboard may have changed in another app while we were away
        if (mc.projectPanel != null) {
            mc.projectPanel.refreshTreeOnRegain(); // pick up files/folders added or removed outside Editora
        }
        if (mc.containerCoordinator != null) {
            mc.containerCoordinator.windowFocused(); // a dev container may have been started or stopped meanwhile
        }
        mc.diffCoordinator.refreshOpenDiffs(); // a compared file may have changed on disk while we were away
        mc.httpClient.refreshAllEnvironments(); // an env file edited elsewhere: the env pills and {{variable}} marks
    }

    private void shutdownDebugSessions() {
        if (mc.debugCoordinator == null) {
            mc.dapManager.shutdown();
        } else {
            mc.debugCoordinator.sessions().shutdown();
        }
    }

    /** The window close asked for again once a Save picked in its question has landed (PERF-01, Pauta 6.2.0). */
    void closeAfterSave() {
        if (mc.windowManager != null) {
            mc.windowManager.requestClose(mc);
        } else if (closeWindowProgrammatically()) {
            mc.stage.close();
        }
    }

    /**
     * Closes this window's session programmatically (the "Close Project" command): prompts to save dirty
     * buffers, persists the session, and releases the window's resources. Returns {@code false} if the
     * user cancelled (the window stays open). The caller ({@link WindowManager}) closes the stage.
     */
    boolean closeWindowProgrammatically() {
        if (!mc.terminalCoordinator.confirmCloseWithBusyTerminals()
                || !mc.closes.confirmCloseAll(this::closeAfterSave)) {
            return false;
        }
        disposeWindow();
        return true;
    }

    /** Releases this window's resources on close: language servers, debug session, and worker threads. */
    void disposeWindow() {
        mc.sessionClosed = true; // no further session writes from this window (see requestSave)
        mc.removalUndo.clear(); // its restores hold this window's buffers, panels and pages
        WindowDisposal.runAll(() -> mc.recovery.dispose()); // stops copying; deletes nothing (see closedByUser)
        // Every step is isolated (WindowDisposal): one shutdown that throws used to skip all the later ones.
        WindowDisposal.runAll(() -> mc.sessions.flushPendingMarks()); // while the buffers still hold their marks
        for (Tab tab : mc.editorArea.tabs()) {
            WindowDisposal.runAll(() -> {
                EditorBuffer buffer = MainController.bufferOf(tab);
                if (buffer != null) {
                    mc.fileWorkflows.invalidatePendingWrites(buffer);
                    buffer.dispose();
                } else {
                    disposeViewerTab(tab); // an image/hex/PDF tab holds a thread + file handle + GPU texture too
                }
            });
        }
        WindowDisposal.runAll(
                () -> mc.lspManager.close(), // stop this window's language servers and refuse any late restart
                () -> shutdownDebugSessions(), // end the debug sessions and release their connect workers
                mc.debugCoordinator == null ? null : () -> mc.debugCoordinator.shutdown(), // its before-launch build
                () -> mc.git.shutdown(),
                () -> mc.github.shutdown(), // stop the gh worker thread
                () -> mc.indexCoordinator.dispose(), // stop the symbol-index walker
                mc.historyCoordinator == null ? null : () -> mc.historyCoordinator.shutdown(),
                () -> mc.searchCoordinator.shutdown(),
                () -> mc.todoCoordinator.shutdown(),
                () -> mc.previews.shutdownLint(),
                () -> mc.mermaid.shutdown(),
                () -> mc.diagram.shutdown(),
                () -> mc.typst.shutdown(),
                () -> mc.doctorCoordinator.shutdown(), // stop any in-flight Doctor probes
                () -> mc.buildCoordinators.forEach(BuildCoordinator::shutdown),
                mc.updates::shutdown, // stop the update-check worker
                () -> mc.htmlPreview.shutdown(), // stop the HTML-preview HTTP server + worker
                () -> mc.logViewer.shutdown(), // stop any log tail-follow poll thread
                () -> mc.stopMcpIfOwner(), // stop the MCP server if this window owns it
                () -> mc.agentCoordinator.shutdown(), // kill the ACP agent process tree
                () -> mc.aiCoordinator.shutdown(), // cancel any in-flight AI generation
                () -> mc.exports.shutdown(),
                () -> mc.runCoordinator.shutdown(),
                () -> mc.terminalCoordinator.shutdown(), // end every terminal's shell
                // The WindowManager-wide Settings Sync held this window's Settings listener — and through it the
                // whole closed window (about 21 MB, its plugins' panes and command closures too) — for good.
                () -> mc.settingsWindow.setSettingsSync(null),
                () -> mc.testRunCoordinator.shutdown(), // stop the report poller + elapsed timer
                mc.installCoordinator == null ? null : () -> mc.installCoordinator.shutdown(),
                () -> mc.fileWorkflows.shutdown(),
                () -> mc.diffCoordinator.shutdown(), // the diff-service worker thread
                () -> mc.mavenProjectCoordinator.shutdown(), // archetype:generate process + catalog fetch thread
                () -> mc.externalToolCoordinator.shutdown(), // the external-tool worker thread
                () -> mc.httpClient.shutdown(), // the http-client worker thread
                () -> mc.httpVariableHints.shutdown(), // the {{variable}} check's worker thread
                () -> mc.remoteCoordinator.shutdown(), // SFTP sessions + the SSH client (and un-pin the Vfs hooks)
                () -> {
                    if (mc.projectPanel != null) {
                        mc.projectPanel.dispose(); // stop the project tree's filesystem watcher + its daemon thread
                    }
                });
    }

    /**
     * Releases a non-buffer viewer tab (image / hex / PDF).
     *
     * <p>These panes were only released through {@code Tab.setOnClosed}, and JavaFX fires that from exactly one
     * place — {@code TabPaneBehavior.closeTab()}, i.e. a click on the tab's ✕. Every one of the app's own close
     * paths ({@code closeTab}, Close All / Others / Left / Right, and window close) removes the tab
     * <em>programmatically</em>, which does not fire it. So closing a PDF with Ctrl-W leaked its {@code
     * pdf-render} thread and left the document's file handle open for the life of the process (on Windows,
     * holding a lock on the file), and an image tab kept its decoded texture pinned. Closing the same tab by
     * clicking the ✕ leaked nothing — which is exactly why this survived manual testing.
     */
    void disposeViewerTab(Tab tab) {
        if (tab.getUserData() instanceof ImageViewerPane image) {
            image.dispose();
        } else if (tab.getUserData() instanceof HexViewerPane hex) {
            hex.dispose();
        } else if (tab.getUserData() instanceof PdfViewerPane pdf) {
            pdf.dispose();
        } else if (tab.getUserData() instanceof PrReviewPane pr) {
            mc.github.onReviewPaneClosed(pr); // drop it from the coordinator's per-PR map (no resource to release)
        } else if (tab.getUserData() instanceof TerminalEditorTab terminal) {
            mc.terminalCoordinator.editorTabClosed(terminal); // its shell ends, unless it went back to the panel
        }
    }

    /**
     * In-app quit. {@code Platform.exit()} fires no {@code Stage.onCloseRequest}, so the per-window close
     * handler never runs — every window has to be prompted + persisted here, or the other windows lose their
     * unsaved buffers and their whole session. {@link WindowManager#confirmCloseAllWindows} does that (and
     * disposes each window's services); the null case is the unit-test/standalone controller.
     */
    boolean onQuit() {
        // One question: quitting, and which terminals that ends (in every window).
        List<String> busy =
                mc.windowManager != null ? mc.windowManager.busyTerminals() : mc.terminalCoordinator.busyTerminals();
        boolean ok = mc.closes.confirmQuit(busy)
                && (mc.windowManager != null
                        ? mc.windowManager.confirmCloseAllWindows()
                        : mc.closes.confirmCloseAll(null)); // the quit may wait for a save
        if (ok) {
            Platform.exit();
        }
        return ok;
    }
}
