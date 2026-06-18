package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlameHeatmapTest {

    @Test
    void newestIsOneOldestIsZero() {
        assertEquals(1.0, BlameHeatmap.intensity(200, 100, 200), 1e-9);
        assertEquals(0.0, BlameHeatmap.intensity(100, 100, 200), 1e-9);
    }

    @Test
    void midpointInterpolates() {
        assertEquals(0.5, BlameHeatmap.intensity(150, 100, 200), 1e-9);
        assertEquals(0.25, BlameHeatmap.intensity(125, 100, 200), 1e-9);
    }

    @Test
    void degenerateRangeIsRecent() {
        // A single commit (or all lines the same age) has no spread → treat as fully recent.
        assertEquals(1.0, BlameHeatmap.intensity(100, 100, 100), 1e-9);
        assertEquals(1.0, BlameHeatmap.intensity(50, 200, 100), 1e-9); // inverted range guarded
    }

    @Test
    void outOfRangeIsClamped() {
        assertEquals(0.0, BlameHeatmap.intensity(50, 100, 200), 1e-9);
        assertEquals(1.0, BlameHeatmap.intensity(250, 100, 200), 1e-9);
    }

    @Test
    void monotonicAcrossRange() {
        double a = BlameHeatmap.intensity(120, 100, 200);
        double b = BlameHeatmap.intensity(160, 100, 200);
        assertTrue(a < b, "newer commit must have a higher intensity");
    }

    @Test
    void heatmapColorIsAWarmRgbaWhoseOpacityGrowsWithNewness() {
        // Newer (higher intensity) → higher alpha; light/dark use different base colors.
        assertEquals("rgba(240,165,70,0.040)", BlameHeatmap.heatmapColor(0.0, false));
        assertEquals("rgba(240,165,70,0.340)", BlameHeatmap.heatmapColor(1.0, false));
        assertEquals("rgba(255,190,90,0.050)", BlameHeatmap.heatmapColor(0.0, true));
        assertEquals("rgba(255,190,90,0.300)", BlameHeatmap.heatmapColor(1.0, true));
    }

    @Test
    void heatmapColorClampsIntensity() {
        assertEquals(BlameHeatmap.heatmapColor(0.0, false), BlameHeatmap.heatmapColor(-1.0, false));
        assertEquals(BlameHeatmap.heatmapColor(1.0, false), BlameHeatmap.heatmapColor(2.0, false));
    }
}
