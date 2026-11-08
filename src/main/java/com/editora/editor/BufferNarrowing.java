package com.editora.editor;

import java.util.List;

import com.editora.editor.fold.NarrowCarry;
import com.editora.editor.markdown.ScrollAnchor;
import com.editora.text.FoldRegions;
import org.jspecify.annotations.Nullable;

/**
 * Emacs narrowing of a buffer: the text outside the accessible region held aside ({@link NarrowCarry}) while the
 * area holds only the region, carrying marks, symbols, fold regions and both views' carets across the swap, and
 * dropping the undo history at the boundary so an undo can never duplicate the held-aside text.
 */
final class BufferNarrowing {

    private final EditorBuffer b;

    BufferNarrowing(EditorBuffer b) {
        this.b = b;
    }

    /**
     * Emacs narrowing: the document text before/after the accessible region (and its styles, symbols and fold
     * regions, see {@link NarrowCarry}), held aside while the area itself holds only the region. Null when the
     * buffer is widened (the normal state).
     */
    NarrowCarry.@Nullable Held narrowHeld;
    /** Fired whenever narrowing turns on or off, from wherever — the UI reconciles off this, not off the
     *  command, so a widen forced by a whole-document write cannot leave a stale indicator or a suspended
     *  language server behind. */
    private Runnable onNarrowChanged = () -> {};

    boolean isNarrowed() {
        return narrowHeld != null;
    }

    /** Offset of the accessible region within the whole document (0 when widened). */
    int narrowStart() {
        return NarrowCarry.regionStart(narrowHeld);
    }

    /**
     * Emacs {@code narrow-to-region}: makes only {@code [start, end)} accessible, holding the rest aside.
     * Returns false when the request is not narrowable (an empty region, or a file large enough that
     * swapping the text is not worth it).
     *
     * <p>The document text really is replaced, so anything reading {@code area.getText()} directly sees
     * only the region — which is the point, and is why {@link EditorBuffer#getContent()} exists to keep whole-file
     * callers correct.
     *
     * <p><b>Undo history is dropped at the boundary.</b> The swap is itself an edit, and undoing across it
     * would restore the whole document <em>into</em> the narrowed area while the hidden text is still held
     * aside — duplicating the file. Rather than let that be reachable, both narrowing and widening clear
     * the history of both split views and the Undo History checkpoints; edits made while narrowed undo
     * normally.
     */
    boolean narrowTo(int start, int end) {
        if (b.largeFile || b.hugeFile || b.logView.filtered()) {
            return false;
        }
        // Offsets arrive in *area* coordinates (callers read them from the selection), which while
        // already narrowed are relative to the region — so rebase them onto the document before widening,
        // or re-narrowing would silently measure the new region from the top of the file instead.
        int base = narrowStart();
        NarrowCarry.Held narrowed = narrowHeld;
        // Re-narrow from the whole document (narrowing does not nest, as in Emacs), without showing it widened.
        List<FoldRegions.Region> regions = narrowed != null ? widenInternal(narrowed, false) : b.folds.regions();
        String full = b.documentTextSnapshot(); // widened just above, so this is the whole document
        int s = Math.max(0, Math.min(start + base, full.length()));
        int e = Math.max(0, Math.min(end + base, full.length()));
        if (s >= e) {
            return false;
        }
        int caret = b.area.getCaretPosition();
        int caret2 = b.area2 == null ? 0 : b.area2.getCaretPosition();
        boolean hadHistory = hasUndoHistory();
        b.clearBraceMatch(); // its offsets are the area's, which the swap changes
        NarrowCarry.Held held = NarrowCarry.hold(b.area, full, s, e, b.symbols, regions);
        narrowHeld = held; // set first: the swap fires the dirty listener, which reads getContent()
        LineMarks.narrow(
                s, e, () -> NarrowCarry.narrow(b.area, s, e), b.bookmarks, b.breakpoints, b.notes, b.gitLines, b.pins);
        b.symbols = NarrowCarry.narrowSymbols(held); // before the regions: their listeners read the symbols
        b.folds.carryRegions(NarrowCarry.narrowRegions(held));
        b.forgetHistoryAtNarrowBoundary(hadHistory);
        b.area.moveTo(Math.max(0, Math.min(caret - s, b.area.getLength())));
        b.area.requestFollowCaret();
        moveSplitCaret(caret2 - s);
        b.narrowView.narrowed(full, s, e, b.area2);
        onNarrowChanged.run();
        return true;
    }

    void setOnNarrowChanged(Runnable callback) {
        this.onNarrowChanged = callback == null ? () -> {} : callback;
    }

    /** The 0-based file line the region starts on (0 when widened): a narrowed buffer keeps the file's numbering. */
    int narrowFirstLine() {
        return b.narrowView.firstLine();
    }

    /** Whether {@link #narrowTo} can take effect here: not a large, huge or filtered view, and editable or view-only. */
    boolean canNarrow() {
        return !b.largeFile && !b.hugeFile && !b.logView.filtered() && (b.isEditable() || b.isViewMode());
    }

    /** Emacs {@code widen}: restores access to the whole document. No-op when not narrowed. */
    void widen() {
        NarrowCarry.Held held = narrowHeld;
        if (held == null) { // not narrowed
            return;
        }
        widenInternal(held, true);
        onNarrowChanged.run();
    }

    /** {@code held}: {@link #narrowHeld}. Returns the whole file's regions; a re-narrow (!shown) shows none of it. */
    private List<FoldRegions.Region> widenInternal(NarrowCarry.Held held, boolean shown) {
        int prefix = held.prefixText().length();
        int caret = b.area.getCaretPosition();
        int caret2 = b.area2 == null ? 0 : b.area2.getCaretPosition();
        narrowHeld = null; // cleared first: the swap fires the dirty listener, which reads getContent()
        boolean hadHistory = hasUndoHistory();
        String region = b.documentTextSnapshot();
        b.clearBraceMatch(); // its offsets are the area's, which the swap changes
        int top = ScrollAnchor.firstVisibleLine(b.area); // the region's lines stay put on screen, as in Emacs
        LineMarks.widen(() -> NarrowCarry.widen(b.area, held), b.bookmarks, b.breakpoints, b.notes, b.gitLines, b.pins);
        b.symbols = NarrowCarry.widenSymbols(held, region, b.symbols); // before the regions, as narrowTo
        List<FoldRegions.Region> regions = NarrowCarry.widenRegions(held, region, b.folds.regions());
        b.forgetHistoryAtNarrowBoundary(hadHistory);
        b.area.moveTo(Math.min(prefix + caret, b.area.getLength()));
        if (shown) { // a re-narrow's listeners (the Structure outline) must not see the whole file in between
            b.folds.carryRegions(regions);
            b.jumps.keepAtTop(b.area, held.firstLine() + Math.max(0, top), top < 0);
        }
        moveSplitCaret(prefix + caret2);
        b.narrowView.widened(b.area2);
        return regions;
    }

    /** Puts the split's second caret back after the document was replaced under it (narrowing, widening). */
    private void moveSplitCaret(int to) {
        if (b.area2 != null) {
            b.area2.moveTo(Math.clamp(to, 0, b.area2.getLength()));
            b.area2.requestFollowCaret();
        }
    }

    /** Something an undo or an Undo History restore could bring back (more than the load's own baseline). */
    private boolean hasUndoHistory() {
        return b.area.isUndoAvailable() || b.area.isRedoAvailable() || b.undoHistory.size() > 1;
    }

    /** Whether a narrow/widen swap cleared a non-empty undo history since the last call: the UI says so. */
    boolean takeHistoryDropped() {
        boolean dropped = historyDropped;
        historyDropped = false;
        return dropped;
    }

    boolean historyDropped;
}
