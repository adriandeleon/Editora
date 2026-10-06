package com.editora.ui;

import java.util.List;

import javafx.geometry.Dimension2D;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure placement arithmetic for secondary windows (Settings, Run Configurations, Debug Log). */
class WindowPlacementTest {

    private static final Rectangle2D LAPTOP = new Rectangle2D(0, 25, 1440, 875); // visual bounds under a menu bar

    @Test
    void centresOnTheOwnerWhenItFits() {
        Point2D at = WindowPlacement.centered(new Rectangle2D(100, 100, 1000, 700), 600, 400, LAPTOP);
        assertEquals(300, at.getX());
        assertEquals(250, at.getY());
    }

    @Test
    void aWindowTallerThanItsOwnerStaysBelowTheTopOfTheScreen() {
        // The reported case: a maximized 824px-tall owner and a window clamped to 92% of the screen.
        Rectangle2D owner = new Rectangle2D(0, 25, 1440, 824);
        Dimension2D size = WindowPlacement.clampSize(1180, 940, LAPTOP, 0.92);
        assertEquals(805, size.getHeight(), 0.01);
        Point2D clamped = WindowPlacement.centered(owner, size.getWidth(), size.getHeight(), LAPTOP);
        assertTrue(clamped.getY() >= LAPTOP.getMinY(), "title bar is on screen");
        // Centring with the unclamped constant is what used to put the title bar above the screen.
        assertTrue(owner.getMinY() + (owner.getHeight() - 940) / 2 < LAPTOP.getMinY());
        assertEquals(
                25, WindowPlacement.centered(owner, 1180, 940, LAPTOP).getY(), "an oversized window pins to the top");
    }

    @Test
    void clampsEveryEdgeIntoTheVisualBounds() {
        Point2D left = WindowPlacement.centered(new Rectangle2D(-400, 300, 300, 200), 600, 400, LAPTOP);
        assertEquals(0, left.getX());
        Point2D bottomRight = WindowPlacement.centered(new Rectangle2D(1300, 800, 300, 200), 600, 400, LAPTOP);
        assertEquals(1440 - 600, bottomRight.getX());
        assertEquals(900 - 400, bottomRight.getY());
    }

    @Test
    void usesTheScreenTheOwnerIsOnNotThePrimary() {
        Rectangle2D primary = new Rectangle2D(0, 0, 1920, 1080);
        Rectangle2D second = new Rectangle2D(1920, 0, 1280, 720);
        Rectangle2D owner = new Rectangle2D(2000, 50, 1100, 600);
        Rectangle2D picked = WindowPlacement.pick(owner, List.of(primary, second));
        assertEquals(second, picked);
        Point2D at = WindowPlacement.centered(owner, 1180, 662, picked);
        assertTrue(at.getX() >= second.getMinX() && at.getX() + 1180 <= second.getMaxX());
        // An owner straddling two screens belongs to the one showing most of it.
        assertEquals(primary, WindowPlacement.pick(new Rectangle2D(1000, 0, 1000, 600), List.of(primary, second)));
    }

    @Test
    void sizeIsLimitedToAFractionOfTheScreen() {
        Dimension2D small = WindowPlacement.clampSize(1180, 940, new Rectangle2D(0, 0, 1024, 600), 0.92);
        assertEquals(1024 * 0.92, small.getWidth(), 0.01);
        assertEquals(600 * 0.92, small.getHeight(), 0.01);
        Dimension2D big = WindowPlacement.clampSize(1180, 940, new Rectangle2D(0, 0, 3840, 2160), 0.92);
        assertEquals(1180, big.getWidth());
        assertEquals(940, big.getHeight());
    }
}
