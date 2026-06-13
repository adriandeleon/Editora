package com.editora.editor;

import javafx.geometry.Bounds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure arithmetic/tab-detection of {@link OverlayMetrics} (no toolkit needed). */
class OverlayMetricsTest {

    @Test
    void hasTabBefore_detects_tab_in_prefix_only() {
        assertFalse(OverlayMetrics.hasTabBefore("hello world", 11));
        assertTrue(OverlayMetrics.hasTabBefore("\tindented", 3));
        assertTrue(OverlayMetrics.hasTabBefore("a\tb", 3));
        // tab is AT or AFTER endCol -> not in the [0,endCol) prefix, fast path still valid
        assertFalse(OverlayMetrics.hasTabBefore("ab\tc", 2));
        assertFalse(OverlayMetrics.hasTabBefore("", 5));
    }

    @Test
    void hasTabBefore_clamps_endCol_to_line_length() {
        assertFalse(OverlayMetrics.hasTabBefore("abc", 100));
        assertTrue(OverlayMetrics.hasTabBefore("ab\t", 100));
    }

    @Test
    void rangeBounds_is_arithmetic_in_columns() {
        // contentLeftX=40, charWidth=8, line at y=100 height=16; columns [3,7)
        Bounds b = OverlayMetrics.rangeBounds(40, 8, 100, 16, 3, 7);
        assertEquals(40 + 3 * 8, b.getMinX(), 1e-9); // 64
        assertEquals(100, b.getMinY(), 1e-9);
        assertEquals(4 * 8, b.getWidth(), 1e-9);     // 32
        assertEquals(16, b.getHeight(), 1e-9);
        assertEquals(40 + 7 * 8, b.getMaxX(), 1e-9); // 96
    }

    @Test
    void rangeBounds_single_column_is_one_char_wide() {
        Bounds b = OverlayMetrics.rangeBounds(0, 7.5, 0, 14, 10, 11);
        assertEquals(75, b.getMinX(), 1e-9);
        assertEquals(7.5, b.getWidth(), 1e-9);
    }
}
