package com.editora.terminal;

/**
 * How fast a drag past the top or bottom edge scrolls the scrollback, and how much of that each
 * frame gets to show.
 *
 * <p>By time rather than per event: a pointer held still just below the pane sends no events at
 * all, and it still means "keep going". By distance as well, so a pointer barely past the edge
 * creeps a line at a time and one flung well outside the window gets through a long log quickly —
 * what every editor does, and the only way both a careful selection and a long one are possible
 * with the same gesture. The fraction a frame does not amount to a whole line is carried, as
 * the view carries the wheel's, so a slow scroll is steady rather than stuttering.
 */
final class AutoScroll {

    /** Lines a second with the pointer just past the edge. */
    static final double SLOWEST = 6;

    /** Lines a second gained for each line's height further out. */
    static final double PER_LINE_OUT = 18;

    static final double FASTEST = 240;

    private long lastNanos = -1;
    private double carried;

    /**
     * Lines a second for a pointer {@code distance} pixels past the edge, never zero: being past it
     * at all is the request.
     */
    static double linesPerSecond(double distance, double lineHeight) {
        double linesOut = lineHeight > 0 ? Math.max(0, distance) / lineHeight : 0;
        return Math.min(FASTEST, SLOWEST + PER_LINE_OUT * linesOut);
    }

    /**
     * The whole lines to scroll this frame, with the remainder kept for the next. The first frame
     * after {@link #reset} only starts the clock, so the time spent before the drag left the pane
     * is not paid out in one jump.
     */
    int step(long nowNanos, double distance, double lineHeight) {
        if (lastNanos < 0) {
            lastNanos = nowNanos;
            return 0;
        }
        double seconds = Math.max(0, nowNanos - lastNanos) / 1e9;
        lastNanos = nowNanos;
        // A frame that took a second — a stall, a window drag — is still one frame's worth, or the
        // selection lurches by a screen when the stall ends.
        seconds = Math.min(seconds, 0.1);
        carried += linesPerSecond(distance, lineHeight) * seconds;
        int lines = (int) carried;
        carried -= lines;
        return lines;
    }

    void reset() {
        lastNanos = -1;
        carried = 0;
    }
}
