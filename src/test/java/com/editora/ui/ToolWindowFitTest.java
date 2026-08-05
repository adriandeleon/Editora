package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a first open puts the divider when the panel's content overflows it. */
class ToolWindowFitTest {

    private static final double EPS = 1e-9;

    @Test
    void aRightPanelGrowsAsTheFractionShrinks() {
        // wants 400 of a 1000 split, currently 200 → panel takes 40%, so the divider sits at 60%.
        assertEquals(0.6, ToolWindowFit.fraction(400, 200, 1000, false), EPS);
    }

    @Test
    void aLeftPanelGrowsWithTheFraction() {
        assertEquals(0.4, ToolWindowFit.fraction(400, 200, 1000, true), EPS);
    }

    @Test
    void theCapBoundsHowMuchIsTakenFromTheEditor() {
        // A pathologically wide row must not swallow the editor.
        assertEquals(1 - ToolWindowFit.MAX_FRACTION, ToolWindowFit.fraction(9999, 200, 1000, false), EPS);
        assertEquals(ToolWindowFit.MAX_FRACTION, ToolWindowFit.fraction(9999, 200, 1000, true), EPS);
    }

    @Test
    void itOnlyEverWidens() {
        // A virtualized list reports a constant preferred width; when that is below the panel's current
        // width, honouring it would SHRINK a window the user is looking at.
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.fraction(100, 300, 1000, false), EPS);
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.fraction(300, 300, 1000, false), EPS);
    }

    @Test
    void aPanelAlreadyAtTheCapIsLeftAlone() {
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.fraction(9999, 500, 1000, false), EPS);
    }

    @Test
    void degenerateGeometryChangesNothing() {
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.fraction(400, 200, 0, false), EPS);
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.fraction(400, 0, 1000, false), EPS);
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.fraction(Double.NaN, 200, 1000, false), EPS);
    }
}
