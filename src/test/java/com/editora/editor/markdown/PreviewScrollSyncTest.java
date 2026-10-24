package com.editora.editor.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pure decisions behind SPLIT-mode scroll sync: telling a preview vvalue change caused by the pane
 * re-anchoring to grown content apart from one caused by the user scrolling.
 *
 * <p>A {@code ScrollPane}'s vvalue moves for both reasons and {@code isHover()} can't distinguish them, so a
 * progressively-rendered preview (typst pages, diagrams, images resolving) used to drag the editor around:
 * measured at ~80 viewport moves over two seconds, the editor drifting ~100 lines down and back, with the
 * mouse merely resting over the preview. With these guards it's 2 — the initial layout, same as a buffer
 * with no preview at all.
 */
class PreviewScrollSyncTest {

    @Test
    void aResizeBeyondTheEpsilonCountsAsLayoutDriven() {
        assertTrue(PreviewScrollSync.previewHeightChanged(1000, 1400)); // a page rendered in
        assertTrue(PreviewScrollSync.previewHeightChanged(-1, 1000)); // first measurement
        assertTrue(PreviewScrollSync.previewHeightChanged(1000, 998));
    }

    @Test
    void subPixelLayoutNoiseIsNotAResize() {
        assertFalse(PreviewScrollSync.previewHeightChanged(1000, 1000));
        assertFalse(PreviewScrollSync.previewHeightChanged(1000, 1000.4));
        assertFalse(PreviewScrollSync.previewHeightChanged(1000, 999.6));
    }

    @Test
    void vvalueMovesWithinTheSettleWindowAreLayout() {
        long settle = 250_000_000L;
        assertTrue(PreviewScrollSync.previewSettling(0, settle)); // the resize pulse itself
        assertTrue(PreviewScrollSync.previewSettling(100_000_000L, settle)); // a follow-up pulse
    }

    @Test
    void oncePastTheWindowAVvalueMoveIsTheUserScrolling() {
        long settle = 250_000_000L;
        assertFalse(PreviewScrollSync.previewSettling(settle, settle));
        assertFalse(PreviewScrollSync.previewSettling(2_000_000_000L, settle));
    }

    @Test
    void thePreviewFollowsTheEditorsTopLineAndBothReachTheirEndsTogether() {
        assertEquals(0, PreviewScrollSync.previewTarget(0, 5000, 0), "top of the editor: top of the preview");
        assertEquals(0, PreviewScrollSync.previewTarget(0, 0, 0), "nothing to scroll");
        assertEquals(120, PreviewScrollSync.previewTarget(2400, 5000, 120), "in between: the first visible line");
        assertTrue(Double.isInfinite(PreviewScrollSync.previewTarget(5000, 5000, 240)), "bottom: the preview's end");
    }
}
