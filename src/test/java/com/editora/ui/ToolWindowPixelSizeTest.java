package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pixel/position arithmetic a docked side uses to keep its size across window resizes. */
class ToolWindowPixelSizeTest {

    @Test
    void pixelsAreMeasuredFromTheSidesOwnEdge() {
        assertEquals(240, ToolWindowFit.pixels(0.2, 1200, true), 0.001);
        assertEquals(240, ToolWindowFit.pixels(0.8, 1200, false), 0.001);
    }

    @Test
    void aPixelSizeBecomesThePositionForTheCurrentSplit() {
        assertEquals(0.9, ToolWindowFit.positionFor(240, 2400, false), 0.001);
        assertEquals(0.1, ToolWindowFit.positionFor(240, 2400, true), 0.001);
    }

    @Test
    void theEditorKeepsAShareOnASplitNarrowerThanTheRememberedSize() {
        assertEquals(0.1, ToolWindowFit.positionFor(1500, 1000, false), 0.001);
        assertEquals(0.9, ToolWindowFit.positionFor(1500, 1000, true), 0.001);
    }

    @Test
    void anUnknownSizeOrSplitChangesNothing() {
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.positionFor(0, 1000, true));
        assertEquals(ToolWindowFit.NO_CHANGE, ToolWindowFit.positionFor(240, 0, true));
    }
}
