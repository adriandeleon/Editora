package org.adriandeleon.editora.editor;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressiveHighlightSupportTest {

    @Test
    void expandsVisibleWindowWithBufferedParagraphs() {
        ProgressiveHighlightSupport.ParagraphWindow window = ProgressiveHighlightSupport.windowAroundVisibleParagraphs(
                120,
                25,
                400,
                80,
                120
        );

        assertEquals(40, window.startParagraph());
        assertEquals(225, window.endParagraphExclusive());
    }

    @Test
    void clampsVisibleWindowAtDocumentBounds() {
        ProgressiveHighlightSupport.ParagraphWindow window = ProgressiveHighlightSupport.windowAroundVisibleParagraphs(
                4,
                10,
                12,
                80,
                120
        );

        assertEquals(0, window.startParagraph());
        assertEquals(12, window.endParagraphExclusive());
    }

    @Test
    void usesFallbackVisibleParagraphCountBeforeFirstLayout() {
        ProgressiveHighlightSupport.ParagraphWindow window = ProgressiveHighlightSupport.windowAroundVisibleParagraphs(
                0,
                0,
                300,
                20,
                90
        );

        assertEquals(0, window.startParagraph());
        assertEquals(110, window.endParagraphExclusive());
    }

    @Test
    void plansNeighborPrefetchWindowsAroundVisibleRange() {
        List<ProgressiveHighlightSupport.ParagraphWindow> windows = ProgressiveHighlightSupport.neighboringParagraphWindows(
                new ProgressiveHighlightSupport.ParagraphWindow(180, 240),
                500,
                100,
                100,
                ProgressiveHighlightSupport.ScrollDirection.NONE
        );

        assertEquals(2, windows.size());
        assertEquals(240, windows.getFirst().startParagraph());
        assertEquals(340, windows.getFirst().endParagraphExclusive());
        assertEquals(80, windows.get(1).startParagraph());
        assertEquals(180, windows.get(1).endParagraphExclusive());
    }

    @Test
    void omitsOutOfBoundsPrefetchWindowsNearDocumentEdges() {
        List<ProgressiveHighlightSupport.ParagraphWindow> topWindows = ProgressiveHighlightSupport.neighboringParagraphWindows(
                new ProgressiveHighlightSupport.ParagraphWindow(0, 60),
                150,
                80,
                80,
                ProgressiveHighlightSupport.ScrollDirection.NONE
        );
        List<ProgressiveHighlightSupport.ParagraphWindow> bottomWindows = ProgressiveHighlightSupport.neighboringParagraphWindows(
                new ProgressiveHighlightSupport.ParagraphWindow(100, 150),
                150,
                80,
                80,
                ProgressiveHighlightSupport.ScrollDirection.NONE
        );

        assertEquals(1, topWindows.size());
        assertEquals(60, topWindows.getFirst().startParagraph());
        assertEquals(140, topWindows.getFirst().endParagraphExclusive());
        assertEquals(1, bottomWindows.size());
        assertEquals(20, bottomWindows.getFirst().startParagraph());
        assertEquals(100, bottomWindows.getFirst().endParagraphExclusive());
    }

    @Test
    void prefersLowerNeighborWhenScrollingDown() {
        List<ProgressiveHighlightSupport.ParagraphWindow> windows = ProgressiveHighlightSupport.neighboringParagraphWindows(
                new ProgressiveHighlightSupport.ParagraphWindow(180, 240),
                500,
                100,
                100,
                ProgressiveHighlightSupport.ScrollDirection.DOWN
        );

        assertEquals(240, windows.getFirst().startParagraph());
        assertEquals(340, windows.getFirst().endParagraphExclusive());
    }

    @Test
    void prefersUpperNeighborWhenScrollingUp() {
        List<ProgressiveHighlightSupport.ParagraphWindow> windows = ProgressiveHighlightSupport.neighboringParagraphWindows(
                new ProgressiveHighlightSupport.ParagraphWindow(180, 240),
                500,
                100,
                100,
                ProgressiveHighlightSupport.ScrollDirection.UP
        );

        assertEquals(80, windows.getFirst().startParagraph());
        assertEquals(180, windows.getFirst().endParagraphExclusive());
    }

    @Test
    void infersScrollDirectionFromViewportMovement() {
        assertEquals(
                ProgressiveHighlightSupport.ScrollDirection.DOWN,
                ProgressiveHighlightSupport.inferScrollDirection(
                        new ProgressiveHighlightSupport.ParagraphWindow(40, 70),
                        new ProgressiveHighlightSupport.ParagraphWindow(55, 85)
                )
        );
        assertEquals(
                ProgressiveHighlightSupport.ScrollDirection.UP,
                ProgressiveHighlightSupport.inferScrollDirection(
                        new ProgressiveHighlightSupport.ParagraphWindow(55, 85),
                        new ProgressiveHighlightSupport.ParagraphWindow(40, 70)
                )
        );
        assertEquals(
                ProgressiveHighlightSupport.ScrollDirection.NONE,
                ProgressiveHighlightSupport.inferScrollDirection(
                        new ProgressiveHighlightSupport.ParagraphWindow(40, 70),
                        new ProgressiveHighlightSupport.ParagraphWindow(40, 70)
                )
        );
    }

    @Test
    void infersViewportMotionMagnitudeFromWindowMovement() {
        ProgressiveHighlightSupport.ViewportMotion motion = ProgressiveHighlightSupport.inferViewportMotion(
                new ProgressiveHighlightSupport.ParagraphWindow(40, 70),
                new ProgressiveHighlightSupport.ParagraphWindow(65, 95)
        );

        assertEquals(ProgressiveHighlightSupport.ScrollDirection.DOWN, motion.direction());
        assertEquals(25, motion.paragraphDelta());
    }

    @Test
    void keepsBasePrefetchForStationaryViewport() {
        int size = ProgressiveHighlightSupport.adaptiveForwardPrefetchParagraphs(
                160,
                new ProgressiveHighlightSupport.ParagraphWindow(40, 70),
                new ProgressiveHighlightSupport.ViewportMotion(ProgressiveHighlightSupport.ScrollDirection.NONE, 0)
        );

        assertEquals(160, size);
    }

    @Test
    void increasesForwardPrefetchForMediumAndFastMovement() {
        ProgressiveHighlightSupport.ParagraphWindow viewport = new ProgressiveHighlightSupport.ParagraphWindow(40, 70);

        int medium = ProgressiveHighlightSupport.adaptiveForwardPrefetchParagraphs(
                20,
                viewport,
                new ProgressiveHighlightSupport.ViewportMotion(ProgressiveHighlightSupport.ScrollDirection.DOWN, 15)
        );
        int fast = ProgressiveHighlightSupport.adaptiveForwardPrefetchParagraphs(
                20,
                viewport,
                new ProgressiveHighlightSupport.ViewportMotion(ProgressiveHighlightSupport.ScrollDirection.DOWN, 30)
        );

        assertEquals(60, medium);
        assertEquals(90, fast);
    }

    @Test
    void appliesExpandedForwardSizeOnlyInScrollDirection() {
        List<ProgressiveHighlightSupport.ParagraphWindow> windows = ProgressiveHighlightSupport.neighboringParagraphWindows(
                new ProgressiveHighlightSupport.ParagraphWindow(180, 240),
                500,
                100,
                220,
                ProgressiveHighlightSupport.ScrollDirection.DOWN
        );

        assertEquals(240, windows.getFirst().startParagraph());
        assertEquals(460, windows.getFirst().endParagraphExclusive());
        assertEquals(80, windows.get(1).startParagraph());
        assertEquals(180, windows.get(1).endParagraphExclusive());
    }
}

