package com.editora.ui;

import java.util.List;

import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Window;

/**
 * Where a secondary window (Settings, Run Configurations, …) opens: centred on its owner, but always
 * wholly inside the <em>visual</em> bounds of the owner's screen, so its title bar can never land above
 * the menu bar or off a smaller second display. The arithmetic is pure; only {@link #screenOf} and
 * {@link #centerOnOwner} touch the toolkit.
 */
final class WindowPlacement {

    private WindowPlacement() {}

    /** {@code preferred} limited to {@code fraction} of the usable screen area. Pure. */
    static Dimension2D clampSize(double prefWidth, double prefHeight, Rectangle2D visual, double fraction) {
        return new Dimension2D(
                Math.min(prefWidth, visual.getWidth() * fraction), Math.min(prefHeight, visual.getHeight() * fraction));
    }

    /**
     * The top-left corner that centres a {@code width}×{@code height} window on {@code owner}, moved the
     * least distance needed to keep it inside {@code visual}. A window larger than the screen is pinned to
     * the screen's top-left so its title bar stays reachable. Pure.
     */
    static Point2D centered(Rectangle2D owner, double width, double height, Rectangle2D visual) {
        double x = owner.getMinX() + (owner.getWidth() - width) / 2;
        double y = owner.getMinY() + (owner.getHeight() - height) / 2;
        return new Point2D(
                clamp(x, visual.getMinX(), visual.getMaxX() - width),
                clamp(y, visual.getMinY(), visual.getMaxY() - height));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    /** Visual bounds of the screen showing most of {@code owner}; the primary screen without an owner. */
    static Rectangle2D screenOf(Window owner) {
        if (owner != null && !Double.isNaN(owner.getX()) && !Double.isNaN(owner.getY())) {
            Rectangle2D bounds = new Rectangle2D(
                    owner.getX(), owner.getY(), Math.max(1, owner.getWidth()), Math.max(1, owner.getHeight()));
            return pick(
                    bounds,
                    Screen.getScreensForRectangle(bounds).stream()
                            .map(Screen::getVisualBounds)
                            .toList());
        }
        return Screen.getPrimary().getVisualBounds();
    }

    /** The candidate overlapping {@code window} most; the primary screen when none does. */
    static Rectangle2D pick(Rectangle2D window, List<Rectangle2D> candidates) {
        Rectangle2D best = null;
        double bestArea = 0;
        for (Rectangle2D c : candidates) {
            double w = Math.min(window.getMaxX(), c.getMaxX()) - Math.max(window.getMinX(), c.getMinX());
            double h = Math.min(window.getMaxY(), c.getMaxY()) - Math.max(window.getMinY(), c.getMinY());
            double area = Math.max(0, w) * Math.max(0, h);
            if (best == null || area > bestArea) {
                best = c;
                bestArea = area;
            }
        }
        return best != null ? best : Screen.getPrimary().getVisualBounds();
    }

    /** Positions {@code window} (of the given size) centred on {@code owner}, on the owner's screen. */
    static void centerOnOwner(Window window, Window owner, double width, double height) {
        if (owner == null
                || Double.isNaN(owner.getX())
                || Double.isNaN(owner.getY())
                || Double.isNaN(owner.getWidth())
                || Double.isNaN(owner.getHeight())) {
            return; // an owner that was never shown has no position to centre on
        }
        Rectangle2D ownerBounds = new Rectangle2D(
                owner.getX(), owner.getY(), Math.max(0, owner.getWidth()), Math.max(0, owner.getHeight()));
        Point2D at = centered(ownerBounds, width, height, screenOf(owner));
        window.setX(at.getX());
        window.setY(at.getY());
    }
}
