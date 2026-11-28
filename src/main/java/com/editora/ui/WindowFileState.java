package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * The state a window keeps per file and re-applies each time the file opens: its folds, its read-only (View) pin,
 * and, through the coordinators, its bookmarks, breakpoints, notes and review threads; and the commands that toggle
 * read-only. It lived inside {@code MainController}; it is a file of its own only because of that file's size (see
 * {@link WindowCommandHost}).
 */
final class WindowFileState {

    private final MainController mc;

    WindowFileState(MainController mc) {
        this.mc = mc;
    }

    /** Persists the buffer's collapsed fold regions + manual fold ranges, keyed by its file path. */
    void persistFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> lines = buffer.getFoldManager().collapsedStartLines();
        var map = mc.config.getWorkspaceState().getFoldedRegions();
        if (lines.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), lines);
        }
        List<Integer> manual = com.editora.editor.fold.ManualFolds.toFlat(
                buffer.getFoldManager().manualRegions());
        var manualMap = mc.config.getWorkspaceState().getManualFoldRegions();
        if (manual.isEmpty()) {
            manualMap.remove(file.toString());
        } else {
            manualMap.put(file.toString(), manual);
        }
        mc.requestSave();
    }

    /** The stored state every open of a file re-applies: folds, bookmarks, breakpoints, notes, read-only. */
    void restorePerFileState(EditorBuffer buffer) {
        restoreFolds(buffer);
        mc.bookmarkCoordinator.restoreBookmarks(buffer);
        mc.debugCoordinator.restoreBreakpoints(buffer);
        mc.notesCoordinator.restoreNotes(buffer);
        mc.reviewCoordinator.restore(buffer);
        restoreReadOnly(buffer);
    }

    /** Re-applies a file's saved manual fold ranges + collapsed fold regions after it is opened. */
    void restoreFolds(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Integer> manual =
                mc.config.getWorkspaceState().getManualFoldRegions().get(file.toString());
        List<Integer> saved = mc.config.getWorkspaceState().getFoldedRegions().getOrDefault(file.toString(), List.of());
        buffer.getFoldManager()
                .restore(manual == null ? List.of() : com.editora.editor.fold.ManualFolds.fromFlat(manual), saved);
    }

    /**
     * Toggles the active buffer's read-only ("View") mode, persists it, and refreshes the indicators.
     * Huge files are already read-only by necessity (truncated load) and can't be made editable.
     */
    void toggleReadOnly() {
        EditorBuffer buffer = mc.activeBuffer();
        if (buffer == null) {
            return;
        }
        if (buffer.isReadOnly()) { // huge-file mode
            mc.setStatus(tr("status.largeReadOnly"));
            return;
        }
        buffer.setViewMode(!buffer.isViewMode());
        afterReadOnlyChange(buffer);
        mc.setStatus(
                buffer.isViewMode() ? ChordHint.tr("status.viewMode", "view.toggleReadOnly") : tr("status.editable"));
    }

    /** Turns off read-only ("Enable Editing" banner button); persists + refreshes the indicators. */
    void enableEditing(EditorBuffer buffer) {
        if (buffer == null || !buffer.isViewMode()) {
            return;
        }
        buffer.setViewMode(false);
        afterReadOnlyChange(buffer);
        mc.setStatus(tr("status.editingEnabled"));
    }

    /** Persists the read-only state and refreshes the tab + status-bar indicators for {@code buffer}. */
    private void afterReadOnlyChange(EditorBuffer buffer) {
        persistReadOnly(buffer);
        Tab tab = mc.tabForBuffer(buffer);
        if (tab != null) {
            mc.updateTabMeta(tab, buffer);
        }
        mc.statusBar.refresh();
        if (buffer == mc.activeBuffer()) {
            mc.toolbar.refreshEditState(); // editability change affects cut enablement
            mc.toolbar.schedulePasteStateRefresh(); // ...and paste enablement (a restored shell flips when it lands)
        }
    }

    /** Persists whether a file is pinned read-only (View mode), keyed by absolute path. */
    private void persistReadOnly(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<String> list = mc.config.getWorkspaceState().getReadOnlyFiles();
        String key = file.toString();
        if (buffer.isViewMode()) {
            if (!list.contains(key)) {
                list.add(key);
            }
        } else {
            list.remove(key);
        }
        mc.requestSave();
    }

    /**
     * Applies read-only ("View") mode to a freshly opened file: on when the user pinned it read-only
     * in a previous session, or when the file isn't writable on disk. Huge files are already read-only
     * and left untouched.
     */
    void restoreReadOnly(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null || buffer.isReadOnly()) {
            return;
        }
        boolean persisted = mc.config.getWorkspaceState().getReadOnlyFiles().contains(file.toString());
        // Log files open in View mode by default — the log viewer is for reading, and follow-tail still
        // appends programmatically while read-only — but the "Enable Editing" banner lets the user opt in.
        boolean logDefault = mc.logViewer.isEnabled() && buffer.isLog();
        boolean writable = com.editora.vfs.Vfs.isWritableOnDisk(file);
        buffer.setWritableOnDisk(com.editora.vfs.Vfs.isRemote(file) ? writable : null); // the banner cannot ask
        if (shouldOpenReadOnly(persisted, writable) || logDefault) {
            buffer.setViewMode(true);
        }
    }

    /** Whether a file should open in View mode: pinned read-only, or not writable on disk. Pure. */
    static boolean shouldOpenReadOnly(boolean persisted, boolean writable) {
        return persisted || !writable;
    }
}
