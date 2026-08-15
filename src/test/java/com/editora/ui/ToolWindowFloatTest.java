package com.editora.ui;

import java.util.Arrays;
import java.util.List;

import javafx.geometry.Rectangle2D;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWindowFloatTest {

    private static final List<Rectangle2D> ONE_SCREEN = List.of(new Rectangle2D(0, 0, 1920, 1080));

    @Test
    void roundTripsBounds() {
        ToolWindowFloat.Bounds b = ToolWindowFloat.fromList(ToolWindowFloat.toList(10, 20, 300, 400));
        assertNotNull(b);
        assertTrue(b.x() == 10 && b.y() == 20 && b.width() == 300 && b.height() == 400);
    }

    @Test
    void rejectsTheWrongShape() {
        assertNull(ToolWindowFloat.fromList(null));
        assertNull(ToolWindowFloat.fromList(List.of()));
        assertNull(ToolWindowFloat.fromList(List.of(1.0, 2.0, 3.0)));
        assertNull(ToolWindowFloat.fromList(Arrays.asList(1.0, 2.0, 300.0, null)));
    }

    @Test
    void rejectsNonFiniteNumbers() {
        assertNull(ToolWindowFloat.fromList(List.of(Double.NaN, 0.0, 300.0, 400.0)));
        assertNull(ToolWindowFloat.fromList(List.of(0.0, 0.0, Double.POSITIVE_INFINITY, 400.0)));
    }

    /** A stage this small is a title bar with nothing under it — better to fall back to a default size. */
    @Test
    void rejectsASizeTooSmallToBeUsable() {
        assertNull(ToolWindowFloat.fromList(List.of(0.0, 0.0, 10.0, 400.0)));
        assertNull(ToolWindowFloat.fromList(List.of(0.0, 0.0, 400.0, 10.0)));
    }

    @Test
    void boundsOnTheScreenAreReachable() {
        assertTrue(ToolWindowFloat.isReachable(new ToolWindowFloat.Bounds(100, 100, 400, 500), ONE_SCREEN));
    }

    /**
     * The case this exists for: bounds saved on a second monitor that is no longer attached. Restoring
     * them would put the stage somewhere the user cannot reach, with no way back but editing the session.
     */
    @Test
    void boundsOnADetachedMonitorAreNotReachable() {
        ToolWindowFloat.Bounds onSecondScreen = new ToolWindowFloat.Bounds(2400, 300, 400, 500);
        assertFalse(ToolWindowFloat.isReachable(onSecondScreen, ONE_SCREEN));
        assertTrue(
                ToolWindowFloat.isReachable(
                        onSecondScreen, List.of(ONE_SCREEN.get(0), new Rectangle2D(1920, 0, 1920, 1080))),
                "plugging the monitor back in makes them reachable again");
    }

    /** A sliver hanging onto the screen edge is not a window the user can grab. */
    @Test
    void aSliverOverlappingTheEdgeIsNotReachable() {
        assertFalse(ToolWindowFloat.isReachable(new ToolWindowFloat.Bounds(-390, 100, 400, 500), ONE_SCREEN));
        assertTrue(ToolWindowFloat.isReachable(new ToolWindowFloat.Bounds(-300, 100, 400, 500), ONE_SCREEN));
    }

    @Test
    void nothingIsReachableWithoutScreens() {
        assertFalse(ToolWindowFloat.isReachable(new ToolWindowFloat.Bounds(0, 0, 400, 500), List.of()));
        assertFalse(ToolWindowFloat.isReachable(null, ONE_SCREEN));
    }
}
