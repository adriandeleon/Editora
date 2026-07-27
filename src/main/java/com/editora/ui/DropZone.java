package com.editora.ui;

/**
 * Where a tab dropped onto an editor group should land: into the group, or into a new group split off one of
 * its edges. Pure geometry, so the hit-testing that decides what a drag does is unit-testable rather than
 * only reachable by actually dragging something.
 *
 * <p>The rule is the one every IDE uses: an outer margin along each edge splits, and everything inside it
 * moves the tab into the group. The margin is a <b>fraction of the group's own size</b>, so it stays usable
 * in a narrow column and does not swallow a wide one — but it is also capped in pixels, because on a very
 * wide editor a pure fraction puts the split target absurdly far from the edge the user is aiming at.
 */
enum DropZone {
    /** Move the tab into this group. */
    CENTER,
    LEFT,
    RIGHT,
    TOP,
    BOTTOM;

    /** Fraction of the group's width/height that counts as an edge. */
    static final double EDGE_FRACTION = 0.25;

    /** Upper bound on that margin, so a wide group's split target stays near its edge. */
    static final double MAX_EDGE_PX = 120;

    /** Whether this zone splits the group rather than dropping into it. */
    boolean isSplit() {
        return this != CENTER;
    }

    /**
     * The zone containing {@code (x, y)} within a group of {@code width} × {@code height}.
     *
     * <p>Whichever edge the point is nearest, relatively, wins — comparing the horizontal and vertical
     * penetration as fractions rather than in pixels, so a corner resolves the same way in a tall narrow
     * group as in a short wide one. A degenerate (zero-sized) group is all centre; there is nowhere
     * meaningful to aim.
     */
    static DropZone of(double x, double y, double width, double height) {
        if (width <= 0 || height <= 0) {
            return CENTER;
        }
        double marginX = Math.min(width * EDGE_FRACTION, MAX_EDGE_PX);
        double marginY = Math.min(height * EDGE_FRACTION, MAX_EDGE_PX);

        boolean nearLeft = x < marginX;
        boolean nearRight = x > width - marginX;
        boolean nearTop = y < marginY;
        boolean nearBottom = y > height - marginY;
        if (!nearLeft && !nearRight && !nearTop && !nearBottom) {
            return CENTER;
        }

        // How far into an edge the point is, as a fraction of that edge's margin: 1.0 hard against the
        // border, 0.0 at the inner boundary. Comparing fractions keeps corners symmetric across aspect
        // ratios, which comparing raw pixel distances does not.
        double horizontal = nearLeft ? (marginX - x) / marginX : nearRight ? (x - (width - marginX)) / marginX : -1;
        double vertical = nearTop ? (marginY - y) / marginY : nearBottom ? (y - (height - marginY)) / marginY : -1;

        if (horizontal >= vertical) {
            return nearLeft ? LEFT : RIGHT;
        }
        return nearTop ? TOP : BOTTOM;
    }
}
