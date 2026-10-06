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
        assertTrue(LoadHeapGuard.fits(49 * MB, HEAP, 70 * MB));
        assertEquals(49 * MB, LoadHeapGuard.cap(49 * MB, HEAP, 70 * MB));
    }

    @Test
    void theDocumentCostNotTheFileSizeIsWhatMustFit() {
        // 600 MB are free: twelve times the file, yet not the fifteen its document needs plus the margin.
        long used = HEAP - 600 * MB;
        assertFalse(LoadHeapGuard.fits(49 * MB, HEAP, used));
        long cap = LoadHeapGuard.cap(49 * MB, HEAP, used);
        assertEquals((600 * MB - LoadHeapGuard.MARGIN_BYTES) / LoadHeapGuard.COST_PER_BYTE, cap);
        assertTrue(LoadHeapGuard.fits(cap, HEAP, used), "what the cap allows does fit");
    }

    @Test
    void anExhaustedHeapStillShowsASmallSlice() {
        assertFalse(LoadHeapGuard.fits(6 * MB, HEAP, HEAP - 10 * MB));
        assertEquals(LoadHeapGuard.MIN_CAP_BYTES, LoadHeapGuard.cap(6 * MB, HEAP, HEAP - 10 * MB));
        assertEquals(LoadHeapGuard.MIN_CAP_BYTES, LoadHeapGuard.cap(6 * MB, HEAP, HEAP + MB), "used above max");
    }

    @Test
    void theCapNeverExceedsTheDocument() {
        assertEquals(100, LoadHeapGuard.cap(100, HEAP, HEAP));
    }
}
