package com.editora.ui;

/**
 * Where to put a split divider so a tool window fits its content.
 *
 * <p>Split out as pure arithmetic because the alternative — reading the divider back after setting it — is
 * not measurable: a {@code SplitPane} only settles a position on a layout pulse, so a headless assertion
 * reads whatever the previous pref-size layout left behind.
 */
final class ToolWindowFit {

    private ToolWindowFit() {}

    /** Never take more than this much of the split from the editor, however wide the content is. */
    static final double MAX_FRACTION = 0.45;

    /** Returned when the panel should be left exactly where its remembered size put it. */
    static final double NO_CHANGE = -1;

    /**
     * The divider fraction that gives a panel {@code wantPx}, or {@link #NO_CHANGE}.
     *
     * @param wantPx the panel's preferred width; a virtualized list reports a constant rather than a
     *     measure of its content, which is why the caller only asks after seeing a real scrollbar
     * @param havePx its current width — the fit only ever widens, so a smaller want is left alone
     * @param totalPx the split's width
     * @param leftSide true for a left-docked panel, whose width grows with the fraction (a right-docked
     *     one grows as the fraction shrinks)
     */
    static double fraction(double wantPx, double havePx, double totalPx, boolean leftSide) {
        if (!(totalPx > 0) || !(havePx > 0) || !(wantPx > havePx)) {
            return NO_CHANGE;
        }
        double width = Math.min(wantPx, MAX_FRACTION * totalPx);
        if (width <= havePx) {
            return NO_CHANGE; // already at or past the cap
        }
        double fraction = width / totalPx;
        return leftSide ? fraction : 1 - fraction;
    }

    /** The narrowest a remembered pixel size may leave the editor, as a share of the split. */
    static final double MIN_EDITOR_SHARE = 0.1;

    /**
     * How many pixels a side's panel takes when its divider sits at {@code position}.
     *
     * @param leadingSide true for a left-docked panel, whose size grows with the position (a right or
     *     bottom one grows as the position shrinks)
     */
    static double pixels(double position, double totalPx, boolean leadingSide) {
        return (leadingSide ? position : 1 - position) * totalPx;
    }

    /**
     * The divider position that gives a side's panel {@code px} pixels of a split {@code totalPx} long, or
     * {@link #NO_CHANGE} when either is unknown. Capped so the editor keeps {@link #MIN_EDITOR_SHARE}: a
     * size remembered on a wide monitor must not swallow the editor on a narrow one.
     */
    static double positionFor(double px, double totalPx, boolean leadingSide) {
        if (!(px > 0) || !(totalPx > 0)) {
            return NO_CHANGE;
        }
        double share = Math.min(px / totalPx, 1 - MIN_EDITOR_SHARE);
        return leadingSide ? share : 1 - share;
    }
}
