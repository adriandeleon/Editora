package com.editora.terminal;

/**
 * The mapping between the scrollback position and the scrollbar's value.
 *
 * <p>Pure and separate because the two count in opposite directions, which is the kind of sign
 * error that produces an inverted scrollbar — one that works, just backwards, and reads as a
 * deliberate choice rather than a bug.
 *
 * <p>Scroll origin is JediTerm's: {@code 0} is the live screen and it goes <em>negative</em> going
 * back through history, down to {@code -historyLines}. The bar counts the other way, because every
 * terminal puts the live screen at the bottom of the track: {@code 0} at the oldest line,
 * {@code historyLines} at the live screen.
 */
final class ScrollBarModel {

    private ScrollBarModel() {}

    /** Bar value for a scroll origin. */
    static double value(int historyLines, int scrollOrigin) {
        return historyLines + scrollOrigin;
    }

    /** Scroll origin for a bar value, clamped to the history that actually exists. */
    static int origin(int historyLines, double value) {
        int origin = (int) Math.round(value) - historyLines;
        if (origin > 0) return 0;
        if (origin < -historyLines) return -historyLines;
        return origin;
    }

    /**
     * Whether the bar is worth showing.
     *
     * <p>Not on the alternate screen: a full-screen program owns the viewport and has no scrollback
     * of its own, so a bar there would either do nothing or scroll away the program's own display.
     */
    static boolean useful(boolean enabled, int historyLines, boolean alternateScreen) {
        return enabled && historyLines > 0 && !alternateScreen;
    }
}
