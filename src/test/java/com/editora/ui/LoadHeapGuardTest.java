package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadHeapGuardTest {

    private static final long MB = 1L << 20;
    private static final long HEAP = 2048 * MB;

    @Test
    void aLargeFileFitsAnEmptyHeap() {
        long cost = LoadHeapGuard.cost(49 * MB, 700_000, false);
        assertTrue(LoadHeapGuard.fits(cost, HEAP, 70 * MB));
        assertEquals(49 * MB, LoadHeapGuard.cap(49 * MB, cost, HEAP, 70 * MB));
    }

    @Test
    void theDocumentCostNotTheFileSizeIsWhatMustFit() {
        // 600 MB are free: twelve times the file, yet not what its characters and lines need plus the margin.
        long used = HEAP - 600 * MB;
        long cost = LoadHeapGuard.cost(49 * MB, 700_000, false);
        assertFalse(LoadHeapGuard.fits(cost, HEAP, used));
        long cap = LoadHeapGuard.cap(49 * MB, cost, HEAP, used);
        assertEquals((long) (49 * MB * ((double) (600 * MB - LoadHeapGuard.MARGIN_BYTES) / cost)), cap);
        assertTrue(
                LoadHeapGuard.fits(LoadHeapGuard.cost(cap, 700_000L * cap / (49 * MB), false), HEAP, used),
                "what the cap allows does fit");
    }

    /** The same bytes in short lines cost more: pretty-printed JSON needed 24 bytes of heap per file byte. */
    @Test
    void linesCostAsWellAsCharacters() {
        long chars = 5 * MB;
        long proseLines = chars / 70;
        long jsonLines = chars / 16;
        assertTrue(LoadHeapGuard.cost(chars, jsonLines, false)
                > 2 * LoadHeapGuard.cost(chars, proseLines, false) * 9 / 10);
        assertTrue(
                LoadHeapGuard.cost(chars, jsonLines, true) > LoadHeapGuard.cost(chars, jsonLines, false),
                "highlighting keeps more per line");
        assertEquals(chars * LoadHeapGuard.COST_PER_CHAR, LoadHeapGuard.cost(chars, 0, true), "no lines, no line cost");
    }

    @Test
    void anExhaustedHeapStillShowsASmallSlice() {
        long cost = LoadHeapGuard.cost(6 * MB, 100_000, true);
        assertFalse(LoadHeapGuard.fits(cost, HEAP, HEAP - 10 * MB));
        assertEquals(LoadHeapGuard.MIN_CAP_BYTES, LoadHeapGuard.cap(6 * MB, cost, HEAP, HEAP - 10 * MB));
        assertEquals(LoadHeapGuard.MIN_CAP_BYTES, LoadHeapGuard.cap(6 * MB, cost, HEAP, HEAP + MB), "used above max");
    }

    @Test
    void theCapNeverExceedsTheDocument() {
        assertEquals(100, LoadHeapGuard.cap(100, LoadHeapGuard.cost(100, 1, true), HEAP, HEAP));
        assertEquals(100, LoadHeapGuard.cap(100, 0, HEAP, HEAP));
    }
}
