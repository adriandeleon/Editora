package com.editora.editor.minimap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The minimap's edit-driven render pacing: a long document re-renders less often, never not at all. */
class MinimapPacingTest {

    private static final long MS = 1_000_000L;

    @Test
    void aShortDocumentIsNotPaced() {
        assertEquals(0, MinimapPacing.intervalMillis(0));
        assertEquals(0, MinimapPacing.intervalMillis(1));
        assertEquals(0, MinimapPacing.intervalMillis(MinimapPacing.FREE_LINES));
        assertEquals(0, MinimapPacing.delayMillis(600, 0), "even right after a render");
    }

    @Test
    void theIntervalGrowsWithTheDocumentUpToACap() {
        assertEquals(200, MinimapPacing.intervalMillis(MinimapPacing.FREE_LINES + 1), 1);
        assertEquals(500, MinimapPacing.intervalMillis(5_000));
        assertEquals(1_000, MinimapPacing.intervalMillis(10_000));
        assertEquals(MinimapPacing.MAX_INTERVAL_MILLIS, MinimapPacing.intervalMillis(20_000));
        assertEquals(MinimapPacing.MAX_INTERVAL_MILLIS, MinimapPacing.intervalMillis(1_000_000));
    }

    @Test
    void theDelayIsWhatRemainsOfTheInterval() {
        assertEquals(1_000, MinimapPacing.delayMillis(10_000, 0));
        assertEquals(400, MinimapPacing.delayMillis(10_000, 600 * MS));
        assertEquals(0, MinimapPacing.delayMillis(10_000, 1_000 * MS));
        assertEquals(0, MinimapPacing.delayMillis(10_000, Long.MAX_VALUE / 2), "a first render never waits");
    }
}
