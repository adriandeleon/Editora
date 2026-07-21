package com.editora.editor;

import org.junit.jupiter.api.Test;

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
        assertTrue(EditorBuffer.previewHeightChanged(1000, 1400)); // a page rendered in
        assertTrue(EditorBuffer.previewHeightChanged(-1, 1000)); // first measurement
        assertTrue(EditorBuffer.previewHeightChanged(1000, 998));
    }

    @Test
    void subPixelLayoutNoiseIsNotAResize() {
        assertFalse(EditorBuffer.previewHeightChanged(1000, 1000));
        assertFalse(EditorBuffer.previewHeightChanged(1000, 1000.4));
        assertFalse(EditorBuffer.previewHeightChanged(1000, 999.6));
    }

    @Test
    void vvalueMovesWithinTheSettleWindowAreLayout() {
        long settle = 250_000_000L;
        assertTrue(EditorBuffer.previewSettling(0, settle)); // the resize pulse itself
        assertTrue(EditorBuffer.previewSettling(100_000_000L, settle)); // a follow-up pulse
    }

    @Test
    void oncePastTheWindowAVvalueMoveIsTheUserScrolling() {
        long settle = 250_000_000L;
        assertFalse(EditorBuffer.previewSettling(settle, settle));
        assertFalse(EditorBuffer.previewSettling(2_000_000_000L, settle));
    }
}
