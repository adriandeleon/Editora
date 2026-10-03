package com.editora.editor;

/**
 * The line an incremental highlight pass must start from. Pure bookkeeping (FX-thread confined by its owner),
 * so the one rule that matters can be tested without a toolkit or a background tokenizer:
 *
 * <p><b>A pass that was dispatched but never applied still owes its lines.</b> Dispatching a pass consumes
 * the "earliest edited line", so if that pass is then discarded — superseded by a newer one, or the document
 * changed length under it — a newer pass that started from only the <em>later</em> edit would leave everything
 * between the two edits tokenized against the old text. The dispatched start line is therefore kept until a
 * pass actually applies, and every new pass starts from the minimum of the two.
 */
final class HighlightStart {

    private static final int NONE = Integer.MAX_VALUE;

    /** Earliest line edited since the last dispatched pass ({@code 0} = the whole document). */
    private int edited;
    /** Start line of the dispatched pass that has not applied yet. */
    private int inFlight = NONE;

    /** Records that {@code line} changed — or, for a style-only update, must be restyled from. */
    void edited(int line) {
        edited = Math.min(edited, Math.max(0, line));
    }

    /** Forces the next pass to cover the whole document (a language or grammar change). */
    void invalidate() {
        edited = 0;
    }

    /**
     * The start line for a pass being dispatched now: the earliest line still owed, which then stays owed
     * until {@link #applied()}. {@link Integer#MAX_VALUE} when nothing is owed (the caller treats that as a
     * line it has no end-state for, i.e. a full pass).
     */
    int dispatch() {
        int from = Math.min(edited, inFlight);
        inFlight = from;
        edited = NONE;
        return from;
    }

    /** The dispatched pass reached the document: nothing before the next edit is owed any more. */
    void applied() {
        inFlight = NONE;
    }

    @Override
    public String toString() {
        return "edited=" + edited + " inFlight=" + inFlight;
    }
}
