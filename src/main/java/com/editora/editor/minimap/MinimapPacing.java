package com.editora.editor.minimap;

/**
 * Pure pacing for the {@link Minimap}'s edit-driven re-render: the longer the document, the longer two
 * renders are kept apart.
 *
 * <p>A render scans the document, draws and snapshots the runs, and ends in a {@code VirtualFlow} layout for
 * the viewport box; part of that cost does not shrink with the row aggregation ({@link MinimapRows}). (The
 * snapshot used to force a whole-window layout as well.) The 200 ms typing-pause debounce alone would run it at
 * every pause of a long edit session, so above {@link #FREE_LINES} the interval between two renders grows
 * with the line count — one per 200 ms at 2,000 lines, one per second at 10,000, capped at
 * {@link #MAX_INTERVAL_MILLIS}. Nothing is lost: the render after the last pause always runs, it only
 * waits for the remainder of the interval.
 */
final class MinimapPacing {

    private MinimapPacing() {}

    /** Up to this many lines a typing pause re-renders at once; the debounce is the only delay. */
    static final int FREE_LINES = 2_000;

    /** Milliseconds between two edit-driven renders per line of document, above {@link #FREE_LINES}. */
    private static final double MILLIS_PER_LINE = 0.1;

    /** The longest two edit-driven renders are kept apart, however long the document. */
    static final long MAX_INTERVAL_MILLIS = 2_000;

    /** The least time between two edit-driven renders of a {@code lines}-line document. */
    static long intervalMillis(int lines) {
        if (lines <= FREE_LINES) {
            return 0;
        }
        return Math.min(MAX_INTERVAL_MILLIS, Math.round(lines * MILLIS_PER_LINE));
    }

    /**
     * How long an edit-driven render requested now must still wait, given the document's size and how
     * long ago the last render ran: zero when it may run at once.
     */
    static long delayMillis(int lines, long sinceLastRenderNanos) {
        long interval = intervalMillis(lines);
        if (interval == 0) {
            return 0;
        }
        long since = sinceLastRenderNanos / 1_000_000L;
        return since >= interval ? 0 : interval - since;
    }
}
