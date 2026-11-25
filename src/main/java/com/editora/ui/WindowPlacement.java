package com.editora.ui;

import java.util.List;

import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

import org.jspecify.annotations.Nullable;

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
    static Rectangle2D screenOf(@Nullable Window owner) {
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

    /**
     * The app window a window opened without an owner belongs over: the focused showing stage, else the first
     * showing one; null when no stage is showing.
     */
    static @Nullable Window frontWindow() {
        Window first = null;
        for (Window w : Window.getWindows()) {
            if (!(w instanceof Stage) || !w.isShowing()) {
                continue;
            }
            if (w.isFocused()) {
                return w;
            }
            if (first == null) {
                first = w;
            }
        }
        return first;
    }

    /**
     * Positions {@code window} centred on {@code owner}, on the owner's screen. Called on every open, not just the
     * first, so a window reopened after the editor moved comes up over the editor again. A window shown before is
     * centred at the size it has now (the user may have resized it); {@code width}×{@code height} is the size a
     * never-shown window opens at. Without a usable owner it centres on the app window in front, and with none of
     * those on the screen.
     */
    static void centerOnOwner(Window window, @Nullable Window owner, double width, double height) {
        Window over = hasBounds(owner) ? owner : frontWindowOtherThan(window);
        double w = sized(window.getWidth()) ? window.getWidth() : width;
        double h = sized(window.getHeight()) ? window.getHeight() : height;
        if (over == null) {
            window.centerOnScreen();
            return;
        }
        Rectangle2D ownerBounds =
                new Rectangle2D(over.getX(), over.getY(), Math.max(0, over.getWidth()), Math.max(0, over.getHeight()));
        Point2D at = centered(ownerBounds, w, h, screenOf(over));
        window.setX(at.getX());
        window.setY(at.getY());
    }

    private static boolean sized(double length) {
        return !Double.isNaN(length) && length > 0;
    }

    /** Whether {@code owner} has a position to centre on (one that was never shown has none). */
    private static boolean hasBounds(@Nullable Window owner) {
        return owner != null
                && !Double.isNaN(owner.getX())
                && !Double.isNaN(owner.getY())
                && !Double.isNaN(owner.getWidth())
                && !Double.isNaN(owner.getHeight());
    }

    /** {@link #frontWindow()} with a position to centre on, skipping {@code self}. */
    private static @Nullable Window frontWindowOtherThan(Window self) {
        Window first = null;
        for (Window w : Window.getWindows()) {
            if (w == self || !(w instanceof Stage) || !w.isShowing() || !hasBounds(w)) {
                continue;
            }
            if (w.isFocused()) {
                return w;
            }
            if (first == null) {
                first = w;
            }
        }
        return first;
    }
}
