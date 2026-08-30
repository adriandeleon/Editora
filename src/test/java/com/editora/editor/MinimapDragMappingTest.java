package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimap drag mapping. The property that matters is <b>continuity</b>: a drag that advances the
 * document in whole-line steps with stalls in between is what reads as choppy, and it is invisible to any
 * assertion that only checks the endpoints.
 */
class MinimapDragMappingTest {

    // This repo's CLAUDE.md, measured: 592 paragraphs, 16 px per line, in a 900 px column.
    private static final int LINES = 592;
    private static final double TOTAL_HEIGHT = LINES * 16.0;
    private static final double COLUMN = 900;
    private static final double ROW_HEIGHT = Math.min(3.0, COLUMN / LINES);

    @Test
    void aColumnPixelMapsToTheDocumentPixelDrawnAtIt() {
        // Row i is drawn at i*rowHeight, so the pixel at 100*rowHeight must scroll line 100 to the top.
        double y = Minimap.documentScrollY(100 * ROW_HEIGHT, ROW_HEIGHT, LINES, TOTAL_HEIGHT);
        assertEquals(100 * 16.0, y, 1e-6);
    }

    @Test
    void theTopOfTheColumnIsTheTopOfTheDocument() {
        assertEquals(0, Minimap.documentScrollY(0, ROW_HEIGHT, LINES, TOTAL_HEIGHT), 1e-9);
    }

    @Test
    void everyPixelOfTravelMovesTheDocument() {
        // The bug: showParagraphAtTop quantised to a line, so 1 px of travel scrolled 16 px or nothing.
        double ideal = (TOTAL_HEIGHT / LINES) / ROW_HEIGHT; // document px per column px
        double prev = Minimap.documentScrollY(300, ROW_HEIGHT, LINES, TOTAL_HEIGHT);
        for (int px = 301; px < 400; px++) {
            double now = Minimap.documentScrollY(px, ROW_HEIGHT, LINES, TOTAL_HEIGHT);
            double delta = now - prev;
            assertTrue(delta > 0, "a pixel of travel must always move the document, at px=" + px);
            assertEquals(ideal, delta, 1e-6, "and by an even amount, at px=" + px);
            prev = now;
        }
    }

    @Test
    void aPressInsideTheViewportBoxGrabsIt() {
        assertTrue(Minimap.withinBox(120, 100, 60));
        assertTrue(Minimap.withinBox(100, 100, 60), "the top edge counts as a grab");
        assertTrue(Minimap.withinBox(160, 100, 60), "so does the bottom edge");
    }

    @Test
    void aPressOutsideTheViewportBoxIsAJumpNotAGrab() {
        assertTrue(!Minimap.withinBox(40, 100, 60));
        assertTrue(!Minimap.withinBox(400, 100, 60));
    }

    @Test
    void aBoxTooThinToHitIsStillGrabbable() {
        // On a long file the box is the viewport's share of the document — here ~2 px, which no mouse
        // lands on; without the slop the drag would be unusable on exactly the files it matters for.
        assertTrue(Minimap.withinBox(100, 100, 2));
        assertTrue(Minimap.withinBox(96, 100, 2), "within the grab slop above a hairline box");
        assertTrue(Minimap.withinBox(106, 100, 2), "and below it");
        assertTrue(!Minimap.withinBox(80, 100, 2), "but the slop is not a licence to grab from anywhere");
    }

    @Test
    void unmeasurableGeometryIsReportedRatherThanGuessed() {
        // Before the first layout there is no height estimate; the caller falls back to the paragraph jump.
        assertTrue(Minimap.documentScrollY(300, ROW_HEIGHT, LINES, 0) < 0);
        assertTrue(Minimap.documentScrollY(300, 0, LINES, TOTAL_HEIGHT) < 0);
        assertTrue(Minimap.documentScrollY(300, ROW_HEIGHT, 0, TOTAL_HEIGHT) < 0);
    }
}
