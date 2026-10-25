package com.editora.editor.completion;

import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;

/**
 * Where a popup anchored at the caret goes vertically: below the caret line when it fits on the screen,
 * otherwise above it. Before this every caret popup opened below and JavaFX's auto-fix pushed it back up over
 * the line being typed, so near the bottom of a maximized window the completion list hid {@code items.}
 * while you typed it (Java review A8). IntelliJ's lookup and intention popups flip above in the same spot.
 */
public final class CaretPopupPlacement {

    /** The gap between the caret line and the popup. */
    static final double GAP = 2;

    private CaretPopupPlacement() {}

    /**
     * The popup's top edge for a caret line spanning {@code caretTop..caretBottom} and a popup {@code height}
     * tall on a screen whose usable area spans {@code screenTop..screenBottom}. Pure.
     *
     * <p>Below when it fits; else above when that fits; else the side with more room (auto-fix then trims
     * the overhang on that side, still clear of the caret line).
     */
    public static double top(
            double caretTop, double caretBottom, double height, double screenTop, double screenBottom) {
        double below = caretBottom + GAP;
        if (below + height <= screenBottom) {
            return below;
        }
        double above = caretTop - GAP - height;
        if (above >= screenTop) {
            return above;
        }
        return screenBottom - caretBottom >= caretTop - screenTop ? below : Math.max(screenTop, above);
    }

    /** {@link #top} for {@code caretScreen} on the screen it is on (the primary screen when none). */
    public static double top(Bounds caretScreen, double height) {
        Rectangle2D area = usableArea(caretScreen);
        return top(caretScreen.getMinY(), caretScreen.getMaxY(), height, area.getMinY(), area.getMaxY());
    }

    private static Rectangle2D usableArea(Bounds caret) {
        var screens = Screen.getScreensForRectangle(
                caret.getMinX(), caret.getMinY(), Math.max(1, caret.getWidth()), Math.max(1, caret.getHeight()));
        return (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
    }
}
