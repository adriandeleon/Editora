package com.editora.ui;

import java.util.List;

import javafx.geometry.Rectangle2D;

/**
 * Pure decisions behind floating a tool window into its own stage: how its bounds are stored, and whether
 * stored bounds are still usable.
 */
final class ToolWindowFloat {

    private ToolWindowFloat() {}

    /** What a floating tool window opens at when it has no remembered size. */
    static final double DEFAULT_WIDTH = 420;

    static final double DEFAULT_HEIGHT = 520;

    /** Below this a stage is a title bar with nothing under it — treat stored bounds that small as junk. */
    static final double MIN_USABLE = 80;

    /** How much of a restored stage must overlap a screen for it to count as reachable, in px each way. */
    static final double MIN_VISIBLE = 60;

    record Bounds(double x, double y, double width, double height) {}

    /** Flattens bounds for the session file, which models this as a plain list of numbers. */
    static List<Double> toList(double x, double y, double width, double height) {
        return List.of(x, y, width, height);
    }

    /**
     * Reads stored bounds back, or null if they are absent, the wrong shape, or too small to be usable.
     *
     * <p>Defensive because these are the one piece of tool-window state that can be made nonsense by
     * something outside the app: a stage dragged mostly off a screen, or a session file hand-edited.
     */
    static Bounds fromList(List<Double> stored) {
        if (stored == null || stored.size() != 4) {
            return null;
        }
        for (Double d : stored) {
            if (d == null || !Double.isFinite(d)) {
                return null;
            }
        }
        double w = stored.get(2);
        double h = stored.get(3);
        if (w < MIN_USABLE || h < MIN_USABLE) {
            return null;
        }
        return new Bounds(stored.get(0), stored.get(1), w, h);
    }

    /**
     * Whether a stage at these bounds would land somewhere the user can actually reach it.
     *
     * <p>The case this exists for is a monitor that is no longer attached: bounds saved on a second screen
     * restore to coordinates that now belong to nothing, and the stage opens invisibly off the desktop with
     * no way to retrieve it but editing the session file. Requiring a real overlap — not merely a corner —
     * also rules out a stage left a pixel onto the screen edge.
     */
    static boolean isReachable(Bounds b, List<Rectangle2D> screens) {
        if (b == null || screens == null) {
            return false;
        }
        for (Rectangle2D s : screens) {
            double overlapX = Math.min(b.x() + b.width(), s.getMaxX()) - Math.max(b.x(), s.getMinX());
            double overlapY = Math.min(b.y() + b.height(), s.getMaxY()) - Math.max(b.y(), s.getMinY());
            if (overlapX >= Math.min(MIN_VISIBLE, b.width()) && overlapY >= Math.min(MIN_VISIBLE, b.height())) {
                return true;
            }
        }
        return false;
    }
}
