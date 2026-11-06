package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

/**
 * The window's listeners on the editor area's tabs: the fan-out that runs when another tab becomes active (panels,
 * status bar, language server, Git, tool windows, toolbar), the tear-down of a closed tab's buffer, and the
 * most-recently-used order they keep for the Switcher. Moved out of {@link MainController} for that file's size.
 */
final class EditorTabListeners {

    private final MainController mc;

    /** Most-recently-used tab order, head = most recent. */
    private final LinkedList<Tab> mru = new LinkedList<>();

    EditorTabListeners(MainController mc) {
        this.mc = mc;
    }

    /** Installs the listeners on the editor area; called once, from {@link MainController#init}. */
    void install() {
        // A mouse click in the editor area repositions the caret, which ends an Emacs mark session.
        mc.editorArea.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> mc.editing.deactivateMark());
        TabFocus.install(mc.editorArea, mc::activeBuffer); // a tab header's click focuses its editor (AQ2)
        // A selected tab always has its buffer: a restore placeholder is built before any listener runs.
        mc.editorArea.setTabMaterializer(tab -> mc.sessions.materialize(tab, true));
        mc.editorArea.addSelectionListener((obs, was, now) -> mc.findBar.onActiveBufferChanged());
        mc.editorArea.addSelectionListener((obs, was, now) -> {
            if (now != null) {
                mru.remove(now);
                mru.addFirst(now);
            }
            // Release the outgoing buffer's per-tab GPU caches (minimap snapshot) and restore the
            // incoming one's, so retained VRAM doesn't grow with the number of open files.
            EditorBuffer outgoing = MainController.bufferOf(was);
            if (outgoing != null) {
                outgoing.setRenderingActive(false);
            }
            EditorBuffer buffer = MainController.bufferOf(now);
            if (buffer != null) {
                buffer.setRenderingActive(true);
                buffer.onTabShown(); // force a repaint: a switched-to area can come up blank until the next pulse
                // A restored background tab defers its language server until it's actually looked at.
                mc.lspCoordinator.onBufferShown(buffer);
            }
            // A panel not built yet attaches the active buffer when it is (see the accessors).
            if (mc.fileInfoPanel != null) {
                mc.fileInfoPanel.attach(buffer);
            }
            if (mc.structurePanel != null) {
                mc.structurePanel.attach(buffer);
            }
            if (mc.undoHistoryPanel != null) {
                mc.undoHistoryPanel.attach(buffer);
            }
            mc.lspCoordinator.requestStructureSymbols(
                    buffer); // upgrade the outline to LSP symbols when the server supports them
            mc.statusBar.attach(buffer);
            mc.breadcrumb.setActiveBuffer(buffer);
            // Sort the Problems / TODO tool windows so the active file's group is on top (IDE convention).
            // Problems is keyed by canonical (LSP) paths; TODO by as-walked (scan) paths.
            Path activePath = buffer == null ? null : buffer.getPath();
            if (mc.projectPanel != null) {
                mc.projectPanel.setActiveFile(activePath);
            }
            mc.lspCoordinator.setProblemsActiveFile(
                    activePath == null ? null : MainController.canonicalPath(activePath));
            mc.todoCoordinator.setActiveFile(
                    activePath == null ? null : activePath.toAbsolutePath().normalize());
            mc.updateWindowTitle(); // show the active file's name + path in the window title bar
            mc.updateProjectFolderView(); // global window: retarget the tree at the new file's folder
            mc.searchCoordinator.refreshScope(); // Find-in-Files "current folder" tracks the active file
            if (FileWorkflowCoordinator.AUTOSAVE_FOCUS.equals(mc.fileWorkflows.autoSaveMode())) {
                mc.fileWorkflows.autoSaveAllDirty(); // saves the outgoing buffer (and any other dirty ones)
            }
            mc.toolbar.refreshSplitButtons();
            mc.toolbar.refreshEditState(); // save/undo/redo/cut/copy enablement for the new tab
            mc.toolbar.refreshPasteState(); // clipboard read off the keystroke path
            mc.chrome.updateZenButton(); // re-position the Zen "Z" if the new file is/isn't Markdown
            mc.chrome.updateExpertButton(); // and the Expert "E"
            mc.fileWorkflows.checkExternalChanges(); // prompt if the file we just switched to changed on disk
            mc.git.refresh(); // update branch/status + this file's gutter change bars
            mc.refreshBuildTools(); // re-detect marker files for the newly active file/project
            mc.startupFocus.repositoryStateComputed(); // the first focus after show need not repeat these two
            mc.lspCoordinator
                    .updateStatusBar(); // show/hide the "LSP: <server>" segment + Problems window for the new file
            mc.debugCoordinator.updateDebugAvailability(); // Debug window only for a debuggable file / live session
            mc.updateRunButton(); // show the Run button only for a compact source file
            mc.updateBufferToolWindows(); // hide buffer-only tool windows when there's no actionable buffer
            mc.csvCoordinator.refreshFor(mc.activeBuffer()); // re-target the CSV grid at the new active buffer
            mc.httpClient.refreshFor(mc.activeBuffer()); // …and the .http response preview
            mc.refreshHttpEnvSegment(); // the status bar's "Env: dev" follows the active .http file
            mc.historyCoordinator.refresh(); // re-gate + reload the Local File History list for the new active file
            mc.installPrompts.maybeOfferInstall(
                    mc.activeBuffer()); // offer to install this language's LSP/DAP if it's missing
            mc.refreshMenuEnablement(); // buffer-shaped menu items (preview, CSV, .http, Typst) follow the tab
            mc.requestSave(); // the session's active file — captured by the coalesced save, not only on exit
        });
        mc.editorArea.addTabsListener((ListChangeListener<Tab>) c -> {
            // After the change settles: moving a tab between groups empties one for a moment.
            Platform.runLater(() -> {
                if (mc.editorEmptyState != null) {
                    mc.editorEmptyState.update(mc.editorArea.isEmpty());
                }
            });
            boolean membershipChanged = false;
            while (c.next()) {
                membershipChanged |= c.wasAdded() || c.wasRemoved();
                // A tab added in the background (e.g. session restore opening many at once) starts
                // rendering-inactive so it holds no minimap snapshot until it's first shown.
                if (c.wasAdded()) {
                    Tab selected = mc.editorArea.selectedTab();
                    for (Tab added : c.getAddedSubList()) {
                        EditorBuffer b = MainController.bufferOf(added);
                        if (b != null && added != selected) {
                            b.setRenderingActive(false);
                        }
                        mc.recovery.track(b); // unsaved text of this buffer is kept for crash recovery
                    }
                }
                // A pin reorder removes+re-adds the same tab, and so does moving one between editor groups
                // (EditorArea.isRelocating) — in both cases the tab is not closing, so skip the cleanup or
                // the buffer would be disposed and its language server shut down out from under a live tab.
                if (c.wasRemoved() && !mc.editorArea.isRelocating()) {
                    mru.removeAll(c.getRemoved());
                    mc.pinned.removeAll(c.getRemoved());
                    // Tear down each closed buffer. dispose() bumps the generation guards so any in-flight
                    // preview/highlight/TODO task discards its result instead of touching a dead buffer, and drops the
                    // preview subscription. It does NOT stop threads: the preview/highlight executors are shared
                    // app-lifetime pools, not one pair per buffer (measured: 4 highlighter threads with 24 files open).
                    // See EditorBuffer.dispose.
                    for (Tab removed : c.getRemoved()) {
                        EditorBuffer closed = MainController.bufferOf(removed);
                        if (closed != null) {
                            mc.fileWorkflows.invalidatePendingWrites(closed);
                            if (closed.getPath() != null && mc.lspManager.isManaged(closed.getPath())) {
                                mc.lspManager.closeDocument(closed.getPath());
                                mc.lspCoordinator.clearDiagnostics(closed.getPath());
                            }
                            mc.logViewer.onBufferClosed(closed); // cancel tail-follow + drop per-buffer state
                            mc.csvCoordinator.onBufferClosed(closed); // drop the CSV grid's edit listener
                            mc.httpClient.onBufferClosed(closed); // drop this buffer's HTTP response panel
                            mc.htmlPreview.onBufferClosed(closed); // the HTML preview serves its file from disk again
                            mc.httpVariableHints.forget(closed);
                            mc.recovery.untrack(closed); // closed by choice: its recovery copy goes with it
                            if (closed.getPath() != null && mc.tabForPath(closed.getPath()) == null) {
                                mc.git.service().forgetBlame(closed.getPath()); // per-line records of a closed file
                                mc.historyCoordinator.pathClosed(closed.getPath());
                            }
                            closed.dispose();
                        } else {
                            // Tab.setOnClosed only fires for a click on the ✕ — never for a programmatic
                            // remove (Ctrl-W, Close All/Others/Left/Right, window close). See disposeViewerTab.
                            mc.disposeViewerTab(removed);
                        }
                    }
                }
            }
            if (membershipChanged && mc.projectPanel != null) {
                mc.projectPanel.refreshOpenFiles();
            }
            mc.requestSave(); // the session's open-file list survives a crash, not just a clean close
        });
    }

    /**
     * All open tabs for the Switcher, most-recently-used first. The MRU only records tabs that have
     * been selected, so any open-but-never-activated tab (e.g. a restored background tab) is appended
     * in tab-strip order — ensuring every open file is listed.
     */
    List<Tab> openTabsForSwitcher() {
        List<Tab> ordered = new ArrayList<>();
        for (Tab tab : mru) {
            if (mc.editorArea.contains(tab)) {
                ordered.add(tab);
            }
        }
        for (Tab tab : mc.editorArea.tabs()) {
            if (!ordered.contains(tab)) {
                ordered.add(tab);
            }
        }
        return ordered;
    }
}
