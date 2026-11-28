package com.editora.editor;

import com.editora.editor.undo.CompletionUndoFactory;
import com.editora.editor.undo.CompletionUndoManager;
import com.editora.editor.undo.UndoHistory;
import com.editora.editor.undo.UndoMerge;
import org.fxmisc.richtext.util.UndoUtils;
import org.fxmisc.undo.UndoManager;
import org.jspecify.annotations.Nullable;

/**
 * Undo for one buffer: the bounded undo manager each view gets, ending an undo group at a word or line boundary,
 * the checkpoints Local History's undo tree is built from, and turning undo off in large-file mode.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferUndo {

    private final EditorBuffer b;

    BufferUndo(EditorBuffer b) {
        this.b = b;
    }

    /** Max undo entries kept per view; caps undo memory (RichTextFX defaults to unlimited). */
    private static final int UNDO_HISTORY = 300;
    /** The document's fixed-size undo history (bounded, so it can't grow without limit), shared by both views. */
    UndoManager<?> boundedUndoManager() {
        // A followed log's new lines are not edits: they stay out of the history (see LogView#adjusting).
        return CompletionUndoFactory.forDocument(
                b.area, () -> b.focusedArea, UNDO_HISTORY, UndoMerge.PAUSE, b.logView::adjusting);
    }
    /** Ends the current undo group at a word/line boundary (see {@link UndoMerge}); no-op for huge files. */
    void breakUndoGroupIfBoundary(String inserted, String removed) {
        if (!b.largeFile && UndoMerge.breakAfter(inserted, removed)) {
            b.area.getUndoManager().preventMerge();
        }
    }

    @Nullable
    Runnable onUndoHistoryChanged;
    /** Notified after a checkpoint is recorded, so the Undo History panel can refresh (active buffer only). */
    void setOnUndoHistoryChanged(@Nullable Runnable r) {
        this.onUndoHistoryChanged = r;
    }
    /** Snapshots the current document state into the history (no-op for huge/oversized files). */
    void captureUndoCheckpoint() {
        // The capture rides a debounced (UndoMerge.PAUSE) subscription that is still live when the tab
        // closes, so an edit made just before closing would otherwise snapshot a whole document INTO a
        // buffer that dispose() has already emptied — pointless work that also undoes the clear. Same
        // reason applyHighlighting() checks this flag.
        if (b.disposed || b.largeFile || b.area.getLength() > EditorBuffer.UNDO_HISTORY_MAX_BYTES) {
            return;
        }
        if (b.undoHistory.add(b.documentTextSnapshot(), b.area.getCaretPosition(), System.currentTimeMillis())
                && onUndoHistoryChanged != null) {
            onUndoHistoryChanged.run();
        }
    }
    /** Restores a checkpoint's document text + caret (a single undoable edit). */
    void restoreUndoCheckpoint(UndoHistory.Checkpoint c) {
        if (c == null || !b.isEditable()) {
            return;
        }
        // The span that differs is worked out off the FX thread (PERF-01, Pauta 6.2.0); then the caret.
        b.replaceVisibleTextLater(b.area, c.text(), applied -> {
            if (applied) {
                b.area.moveTo(UndoHistory.clamp(c.caret(), b.area.getLength()));
                b.area.requestFocus();
            }
        });
    }
    /** Picks the undo manager for the current mode: none for large/huge files, bounded otherwise. */
    void applyUndoMode() {
        b.area.setUndoManager(b.largeFile ? UndoUtils.noOpUndoManager() : boundedUndoManager());
        if (b.area2 != null) {
            b.area2.setUndoManager(b.area.getUndoManager());
        }
    }
    /** Keeps a programmatic mutation (or a command's edit) separate from adjacent typing (both views share
     *  one undo history). */
    void preventUndoMerge() {
        if (!b.largeFile) {
            b.area.getUndoManager().preventMerge();
        }
    }
    /** Makes every edit until the returned action runs ONE undo/redo step (a macro replay), in both views. */
    Runnable beginUndoSpan() {
        return b.largeFile ? () -> {} : CompletionUndoManager.beginSpan(b.area, b.area2);
    }
}
