package com.editora.editor;

/**
 * The part of the document an incremental highlight pass still owes: one character range
 * {@code [start, end)} in the coordinates of the <em>current</em> text, covering every edit (and every
 * style-only restyle request) since a pass last applied. Pure bookkeeping (FX-thread confined by its
 * owner), so the rules that matter can be tested without a toolkit or a background tokenizer:
 *
 * <ul>
 *   <li><b>A pass that was dispatched but never applied still owes its range.</b> Nothing is consumed at
 *       dispatch: the range is only dropped by {@link #applied()}, so a pass that is superseded, or
 *       discarded because the document changed under it, leaves the next one covering both its own edit
 *       and the earlier one.</li>
 *   <li><b>The range follows the text.</b> Each later edit maps the range through itself before joining
 *       it, so the two ends keep naming the same characters however much text is inserted or removed
 *       around them. Outside the range the current text is, character for character, the text the last
 *       applied pass saw — which is what lets a pass stop at the far end as soon as the grammar state
 *       re-converges, instead of running to the end of the file.</li>
 * </ul>
 *
 * <p>Offsets rather than lines, so recording an edit costs a few integer operations and never asks the
 * document for a line lookup on the keystroke path.
 */
final class HighlightDirty {

    private static final int NONE = -1;

    /** Start of the owed range, or {@link #NONE} when nothing is owed. A fresh buffer owes everything. */
    private int start;
    /** End (exclusive) of the owed range; {@link Integer#MAX_VALUE} = to the end of the document. */
    private int end = Integer.MAX_VALUE;

    /**
     * Records the replacement of {@code removed} characters at {@code position} by {@code inserted}
     * characters. Successive calls take positions in the coordinates each replacement ran in, which is
     * how a multi-change edit reports them.
     */
    void edited(int position, int removed, int inserted) {
        int at = Math.max(0, position);
        int insertedEnd = at + Math.max(0, inserted);
        if (start == NONE) {
            start = at;
            end = insertedEnd;
            return;
        }
        int removedEnd = at + Math.max(0, removed);
        int shift = insertedEnd - removedEnd;
        // A boundary before the edit stays put, one after the replaced text shifts with it, and one inside
        // the replaced text collapses onto the replacement — its start for the range start, its end for
        // the range end, so the range can only grow.
        int mappedStart = start <= at ? start : start >= removedEnd ? start + shift : at;
        int mappedEnd = end == Integer.MAX_VALUE || end <= at ? end : end >= removedEnd ? end + shift : insertedEnd;
        start = Math.min(mappedStart, at);
        end = Math.max(mappedEnd, insertedEnd);
    }

    /**
     * Where the character at {@code offset} is after {@code removed} characters at {@code position} were
     * replaced by {@code inserted}, or {@code -1} if it was one of the characters removed.
     */
    static int moved(int offset, int position, int removed, int inserted) {
        if (offset < position) {
            return offset;
        }
        return offset >= position + removed ? offset + inserted - removed : -1;
    }

    /** Adds {@code [from, to)} of the current text to the owed range without any text change (a restyle). */
    void include(int from, int to) {
        int lo = Math.max(0, Math.min(from, to));
        int hi = Math.max(from, to);
        if (start == NONE) {
            start = lo;
            end = hi;
        } else {
            start = Math.min(start, lo);
            end = Math.max(end, hi);
        }
    }

    /** Owes the whole document (a language or grammar change, a feature toggle). */
    void invalidate() {
        start = 0;
        end = Integer.MAX_VALUE;
    }

    /** A pass covering the owed range reached the document: nothing is owed until the next edit. */
    void applied() {
        start = NONE;
        end = NONE;
    }

    boolean isClean() {
        return start == NONE;
    }

    /** Start offset of the owed range. Only meaningful while {@link #isClean()} is false. */
    int start() {
        return start;
    }

    /** End offset (exclusive) of the owed range; {@link Integer#MAX_VALUE} for "to the end". */
    int end() {
        return end;
    }

    @Override
    public String toString() {
        return start == NONE ? "clean" : "[" + start + ", " + (end == Integer.MAX_VALUE ? "end" : end) + ")";
    }
}
