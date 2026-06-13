package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasGuardsTest {

    @Test
    void clampDimFloorsInvalidSizesToOne() {
        assertEquals(1, CanvasGuards.clampDim(0));
        assertEquals(1, CanvasGuards.clampDim(-50));
        assertEquals(1, CanvasGuards.clampDim(0.4)); // below one pixel
        assertEquals(1, CanvasGuards.clampDim(Double.NaN));
    }

    @Test
    void clampDimPassesValidSizesAndCapsHuge() {
        assertEquals(800, CanvasGuards.clampDim(800));
        assertEquals(1, CanvasGuards.clampDim(1));
        assertEquals(CanvasGuards.MAX_DIM, CanvasGuards.clampDim(CanvasGuards.MAX_DIM));
        assertEquals(CanvasGuards.MAX_DIM, CanvasGuards.clampDim(CanvasGuards.MAX_DIM + 5000));
        assertEquals(CanvasGuards.MAX_DIM, CanvasGuards.clampDim(Double.POSITIVE_INFINITY));
    }

    @Test
    void paintableTrueOnlyForRealInRangeSizes() {
        assertTrue(CanvasGuards.paintable(640, 480));
        assertTrue(CanvasGuards.paintable(1, 1));
    }

    @Test
    void paintableFalseForCollapsedNanOrOversize() {
        assertFalse(CanvasGuards.paintable(0, 480));
        assertFalse(CanvasGuards.paintable(640, 0));
        assertFalse(CanvasGuards.paintable(-3, 480));
        assertFalse(CanvasGuards.paintable(Double.NaN, 480));
        assertFalse(CanvasGuards.paintable(640, Double.POSITIVE_INFINITY));
        assertFalse(CanvasGuards.paintable(CanvasGuards.MAX_DIM + 1, 480));
    }
}
