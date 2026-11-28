package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.control.Tab;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A window's tab operations: closing one tab or a batch (with their questions), reopening the last closed, pinning,
 * renaming a tab's file, copying, revealing or opening a terminal at its path, stepping through the tabs, and
 * splitting, moving between and merging editor groups. The state stays on {@link MainController} (the tabs, the pins,
 * the closed-tab list); this resolves it when called. It lived inside {@code MainController}; it is a file of its own
 * only because of that file's size (see {@link WindowCommandHost}).
 */
final class WindowTabActions {

    private final MainController mc;

    WindowTabActions(MainController mc) {
        this.mc = mc;
    }

    void closeTabFromSwitcher(Tab tab) {
        closeTab(tab);
    }

    /**
     * Selects a tab chosen in an overlay picker (the Switcher / "Jump to Open File") and moves the caret into
     * its editor. The focus request is deferred via {@code Platform.runLater} because these pickers close through
     * the shared {@link OverlayHost}, whose {@code hide()} synchronously restores focus to the previously-focused
     * area — running the focus request afterward ensures the caret lands in the newly selected buffer instead of
     * the old one (otherwise the user has to click to get a caret). Robust regardless of whether the picker
     * hides before or after invoking this.
     */
    void activateAndFocusTab(Tab tab) {
        mc.editorArea.select(tab);
        Platform.runLater(
                () -> TabFocus.focusTab(tab, MainController::bufferOf)); // a page tab: its page (twelfth review AL1)
    }

    void toggleSplit(EditorBuffer.Split orientation) {
        EditorBuffer buffer = mc.activeBuffer();
        if (buffer == null) {
            return;
        }
        buffer.toggleSplit(orientation);
        mc.toolbar.refreshSplitButtons();
        mc.setStatus(tr(buffer.getSplit() == EditorBuffer.Split.NONE ? "status.editorUnsplit" : "status.editorSplit"));
    }

    void unsplit() {
        EditorBuffer buffer = mc.activeBuffer();
        boolean bufferWasSplit = buffer != null && buffer.getSplit() != EditorBuffer.Split.NONE;
        boolean groupsWereSplit = mc.editorArea.unsplit();
        if (bufferWasSplit && buffer != null) {
            buffer.setSplit(EditorBuffer.Split.NONE);
        }
        if (!groupsWereSplit && !bufferWasSplit) {
            mc.toolWindows.closeAllOpen();
        }
        mc.toolbar.refreshSplitButtons();
        mc.setStatus(tr(groupsWereSplit ? "status.editorGroupsMerged" : "status.editorUnsplit"));
    }

    /** Moves the active tab into a new group; refuses when that would immediately empty and collapse its group. */
    void splitEditorGroup(Orientation orientation) {
        if (mc.editorArea.splitActive(orientation)) {
            mc.setStatus(tr("status.editorGroupSplit"));
        } else {
            mc.setStatus(tr("status.editorGroupSplitUnavailable"));
        }
    }

    /** Moves the active tab to the next editor group, splitting first if the area is not split yet. */
    void moveTabToNextGroup() {
        mc.setStatus(tr(mc.editorArea.moveActiveToNextGroup() ? "status.editorGroupMoved" : "status.editorGroupNone"));
    }

    void focusNextEditorGroup() {
        if (!mc.editorArea.focusNextGroup()) {
            mc.setStatus(tr("status.editorGroupNotSplit"));
        } else {
            TabFocus.focus(mc.activeBuffer()); // its editor, not the tab's container (tenth review AO1)
        }
    }

    /** Merges every editor group back into one. */
    void unsplitEditorGroups() {
        mc.setStatus(tr(mc.editorArea.unsplit() ? "status.editorGroupsMerged" : "status.editorGroupNotSplit"));
    }

    /** Closes a single tab, confirming first if it is pinned and/or has unsaved changes. */
    void closeTab(Tab tab) {
        if (tab != null && tab.getUserData() instanceof TerminalEditorTab terminal) {
            mc.terminalCoordinator.requestClose(terminal); // its shell's own question, which may ask the host first
            return;
        }
        if (tab != null && mc.closes.confirmClose(tab, () -> closeTab(tab))) {
            rememberClosed(tab);
            mc.editorArea.remove(tab);
        }
    }

    /**
     * Remembers a file tab the user is closing. Called from the close command, the batch closes and the tab's
     * own close button only — never for a window close, a project switch or a tab swapped for another view.
     */
    void rememberClosed(Tab tab) {
        EditorBuffer buffer = MainController.bufferOf(tab);
        RestorePlaceholder placeholder = RestorePlaceholder.of(tab);
        if (buffer != null && buffer.getPath() != null) {
            mc.closedTabs.closed(buffer.getPath(), buffer.getArea().getCaretPosition());
        } else if (placeholder != null) {
            mc.closedTabs.closed(placeholder.path(), placeholder.caret()); // a restored tab never shown
        } else if (buffer == null) {
            // A viewer's file comes back too, in its viewer: an image, a PDF, or a file's bytes.
            mc.closedTabs.closed(MainController.tabPath(tab), 0, MainController.hexPaneOf(tab) != null);
        }
    }

    /** {@code buffer.reopenClosed}: opens the file whose tab was closed last, its caret where it was. */
    void reopenClosedTab() {
        ClosedTabs.Entry entry = mc.closedTabs.pop();
        if (entry != null && com.editora.vfs.Vfs.isLocal(entry.path())) {
            // deleted since? looked at off the FX thread (PERF-01, Pauta 6.2.0); then the next one back
            com.editora.perf.OffFx.then("reopen-look", () -> Files.exists(entry.path()), there -> {
                if (there.or(false)) {
                    reopenClosedTab(entry);
                } else {
                    reopenClosedTab();
                }
            });
            return;
        }
        reopenClosedTab(entry);
    }

    void reopenClosedTab(ClosedTabs.@Nullable Entry entry) {
        if (entry != null) {
            Path path = entry.path();
            Tab open = mc.tabForPath(path);
            if (open != null && (MainController.hexPaneOf(open) != null) == entry.hex()) {
                mc.editorArea.select(open); // reopened some other way since
                return;
            }
            if (entry.hex()) {
                mc.fileWorkflows.openHexTab(path, true); // its bytes again, though its name would open it as text
                return;
            }
            int caret = entry.caret();
            mc.fileWorkflows.openThen(path, () -> {
                EditorBuffer buffer = mc.activeBuffer();
                if (buffer != null && path.equals(buffer.getPath())) {
                    buffer.getArea()
                            .moveTo(Math.clamp(caret, 0, buffer.getArea().getLength()));
                    buffer.getArea().requestFollowCaret();
                }
            });
            return;
        }
        mc.setStatus(tr("status.reopenClosed.none"));
    }

    /**
     * Closes each tab in {@code targets} (a snapshot), prompting for dirty buffers and stopping if
     * the user cancels — mirroring the window-close coordinator.
     */
    private void closeTabs(List<Tab> targets) {
        for (int i = 0; i < targets.size(); i++) {
            Tab tab = targets.get(i);
            // A Save picked in a question writes off the FX thread: the batch goes on from this tab once it has landed.
            List<Tab> fromHere = List.copyOf(targets.subList(i, targets.size()));
            Runnable afterSave = () -> closeTabs(fromHere.stream()
                    .filter(t -> mc.editorArea.tabs().contains(t))
                    .toList());
            if (tab.getUserData() instanceof TerminalEditorTab terminal) {
                mc.terminalCoordinator.requestClose(terminal); // asks when its shell is running something
                continue;
            }
            EditorBuffer buffer = MainController.bufferOf(tab);
            if (buffer == null) {
                if (!mc.closes.confirmClose(tab, afterSave)) {
                    return;
                }
                rememberClosed(tab);
                mc.editorArea.remove(tab);
                continue;
            }
            if (buffer != null && !buffer.isDirty() && !mc.fileWorkflows.hasPendingSave(buffer)) {
                rememberClosed(tab);
                mc.editorArea.remove(tab);
                continue;
            }
            mc.editorArea.select(tab);
            if (mc.closes.askCloseIfDirty(buffer, afterSave) != CloseCoordinator.Answer.CLOSE) {
                return; // user cancelled, or saving first — stop the batch (a save goes on with it when it lands)
            }
            rememberClosed(tab);
            mc.editorArea.remove(tab);
        }
    }

    /** Non-pinned tabs whose index is less than {@code pivot}'s. */
    List<Tab> eligibleToLeft(Tab pivot) {
        int idx = mc.editorArea.indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = 0; i < idx; i++) {
            Tab t = mc.editorArea.tabAt(i);
            if (!mc.pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Non-pinned tabs whose index is greater than {@code pivot}'s. */
    List<Tab> eligibleToRight(Tab pivot) {
        int idx = mc.editorArea.indexOf(pivot);
        List<Tab> out = new ArrayList<>();
        for (int i = idx + 1; i < mc.editorArea.size(); i++) {
            Tab t = mc.editorArea.tabAt(i);
            if (!mc.pinned.contains(t)) {
                out.add(t);
            }
        }
        return out;
    }

    void closeOtherTabs(Tab keep) {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : mc.editorArea.tabs()) {
            if (t != keep && !mc.pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    void closeAllTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : mc.editorArea.tabs()) {
            if (!mc.pinned.contains(t)) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    void closeUnmodifiedTabs() {
        List<Tab> targets = new ArrayList<>();
        for (Tab t : mc.editorArea.tabs()) {
            EditorBuffer buffer = MainController.bufferOf(t);
            if (!mc.pinned.contains(t) && (buffer == null || !buffer.isDirty())) {
                targets.add(t);
            }
        }
        closeTabs(targets);
    }

    void closeTabsToLeft(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToLeft(pivot));
        }
    }

    void closeTabsToRight(Tab pivot) {
        if (pivot != null) {
            closeTabs(eligibleToRight(pivot));
        }
    }

    /** Copies the buffer's absolute path to the system clipboard. */
    void copyPath(@Nullable EditorBuffer buffer) {
        copyPath(buffer == null ? null : buffer.getPath());
    }

    /** Copies {@code path} in full to the clipboard: a buffer's file or a viewer's. */
    void copyPath(@Nullable Path path) {
        if (path == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(path.toAbsolutePath().toString());
        Clipboard.getSystemClipboard().setContent(content);
        mc.setStatus(tr("status.copiedPath"));
    }

    /** Reveals the active buffer's file in the OS file manager (a no-op for an unsaved/remote file). */
    void revealActiveBuffer() {
        EditorBuffer buffer = mc.activeBuffer();
        if (buffer == null) {
            return;
        }
        revealInFileManager(buffer.getPath(), false, mc.isLocalBuffer(buffer));
    }

    /**
     * Opens a terminal at the active buffer's containing folder, on its host for a remote file. With no saved file
     * active (the Welcome tab, an unsaved buffer) it opens where {@code tool.terminal} would: the project's folder,
     * or home.
     */
    void openTerminalForActiveBuffer() {
        EditorBuffer buffer = mc.activeBuffer();
        if (buffer == null || buffer.getPath() == null) {
            mc.terminalCoordinator.newTerminal();
            return;
        }
        openTerminalAt(buffer.getPath(), false, mc.isLocalBuffer(buffer));
    }

    /**
     * Reveals {@code path} in the OS file manager. Used by the palette command, the tab context menu,
     * and the Project tool window. No-op (with a status hint) for an unsaved or remote file.
     */
    void revealInFileManager(@Nullable Path path, boolean isDir, boolean local) {
        if (path == null) {
            mc.setStatus(tr("status.reveal.noFile"));
            return;
        }
        if (!local) {
            mc.setStatus(tr("status.reveal.remote"));
            return;
        }
        com.editora.process.DesktopActions.reveal(
                path.toAbsolutePath(),
                isDir,
                msg -> Platform.runLater(() -> mc.setStatus(tr("status.reveal.failed", msg))));
    }

    /**
     * Opens a terminal at {@code path}'s containing folder (or {@code path}, a folder): a tab in the Terminal
     * tool window, or the system's terminal when Settings → Terminal says so. Shared by the command, tab menu,
     * breadcrumb and tree.
     */
    void openTerminalAt(@Nullable Path path, boolean isDir, boolean local) {
        if (path == null) {
            mc.setStatus(tr("status.reveal.noFile"));
            return;
        }
        if (!local) {
            // A system terminal would open on this machine, not the host, so a remote folder always gets a tab.
            Path dir = isDir ? path : path.getParent();
            mc.terminalCoordinator.openAt(dir == null ? path : dir);
            return;
        }
        if (!mc.config.getSettings().isTerminalUseExternal()) {
            Path abs = path.toAbsolutePath();
            mc.terminalCoordinator.openAt(isDir ? abs : abs.getParent());
            return;
        }
        com.editora.process.DesktopActions.openTerminal(
                path.toAbsolutePath(),
                isDir,
                msg -> Platform.runLater(() -> mc.setStatus(tr("status.terminal.failed", msg))));
    }

    /**
     * Toggles a tab's pinned state. Pinned tabs are kept grouped at the front of the strip (in pin
     * order) and skipped by the bulk-close actions.
     */
    void togglePin(Tab tab) {
        if (tab == null) {
            return;
        }
        EditorBuffer buffer = mc.ensureBuffer(tab); // a restored tab not built yet has a header to mark: build it
        if (buffer == null && MainController.tabPath(tab) == null) {
            // The Welcome tab, a diff, a terminal: no file to keep open, and the session would not bring the
            // pin back. buffer.togglePin reaches here with whatever tab is selected.
            mc.setStatus(tr("status.noFileOpen"));
            return;
        }
        if (!mc.pinned.remove(tab)) {
            mc.pinned.add(tab);
        }
        // Either way the tab belongs at the end of its strip's pinned group: last of them once pinned, first
        // after them once unpinned.
        mc.editorArea.moveToPinBoundary(tab, mc.pinned::contains);
        if (buffer != null) {
            mc.updateTabMeta(tab, buffer);
        } else {
            renderViewerPin(tab); // an image, a PDF or a hex view: pinned as a file's tab is
        }
        mc.setStatus(tr(mc.pinned.contains(tab) ? "status.pinned" : "status.unpinned"));
    }

    /** Draws the pin marker (or its absence) on a viewer tab, whose header is the generic one. */
    void renderViewerPin(Tab tab) {
        if (MainController.bufferOf(tab) == null && tab.getUserData() instanceof TabContent content) {
            TabHeaders.toggleClass(tab, "pinned", mc.pinned.contains(tab));
            mc.tabHeaders.contentHeader(tab, content);
        }
    }

    /** Prompts for a new name for the buffer's file; see {@link #renameFileTo}. */
    void renameFile(@Nullable EditorBuffer buffer, Tab tab) {
        if (buffer != null && buffer.getPath() != null) {
            Path old = buffer.getPath();
            String name = old.getFileName().toString();
            mc.promptText(tr("dialog.renameFile.title"), tr("dialog.renameFile.content"), name, typed -> {
                if (!typed.isBlank()) {
                    renameFileTo(buffer, old, old.resolveSibling(typed.trim()));
                }
            });
        }
    }

    /**
     * Renames the buffer's file on disk, then follows the rename through {@link MainController#onProjectFileRenamed} — the
     * path the Project tree uses. The tab menu used to re-point only its own buffer, so another window showing
     * the same file kept the old path (and would recreate it on save), and only the fold state moved with it.
     */
    void renameFileTo(EditorBuffer buffer, Path old, Path target) {
        if (target.equals(old)) {
            return;
        }
        mc.fileWorkflows.invalidatePendingWrite(old);
        // The name is checked and the file moved off the FX thread (PERF-01, Pauta 6.2.0); then every window follows.
        com.editora.perf.OffFx.then("rename-file", () -> RenamedFileState.move(old, target), moved -> {
            if (!moved.ok()) {
                mc.setStatus(tr("status.renameFailed", moved.why()));
            } else if (!moved.or(false)) {
                mc.setStatus(tr("status.renameFailedExists", target.getFileName()));
            } else {
                renamedFileTo(buffer, old, target);
            }
        });
    }

    private void renamedFileTo(EditorBuffer buffer, Path old, Path target) {
        mc.onProjectFileRenamed(old, target); // every window: path, EditorConfig, LSP, tab, path-keyed session state
        mc.previews.ensurePreviewControls(buffer); // a rename to/from .md/.mmd flips previewability
        mc.logViewer.ensureControl(buffer); // a rename to/from .log flips the log control
        if (mc.recentFiles != null) {
            mc.recentFiles.add(target);
        }
        mc.statusBar.refresh();
    }

    void nextBuffer() {
        stepBuffer(1);
    }

    /** {@code buffer.previous}: the tab before the selected one, wrapping to the last. */
    void previousBuffer() {
        stepBuffer(-1);
    }

    private void stepBuffer(int step) {
        int count = mc.editorArea.focusedSize(); // within the split group, as IntelliJ's (thirteenth review AK3)
        if (count > 1) {
            int idx = Math.floorMod(mc.editorArea.selectedIndex() + step, count);
            mc.editorArea.select(idx);
            Platform.runLater(
                    () -> TabFocus.focusTab(mc.editorArea.selectedTab(), MainController::bufferOf)); // AQ2, AL1
        }
    }
}
