package com.editora.recovery;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryPolicyTest {

    @Test
    void onlyAnUnsavedUserDocumentIsCopied() {
        assertEquals(RecoveryPolicy.Skip.NONE, RecoveryPolicy.skip(true, false, false, false, 10));
        assertEquals(RecoveryPolicy.Skip.CLEAN, RecoveryPolicy.skip(false, false, false, false, 10));
        assertEquals(RecoveryPolicy.Skip.NOT_A_DOCUMENT, RecoveryPolicy.skip(true, true, false, false, 10));
        assertEquals(RecoveryPolicy.Skip.NOT_A_DOCUMENT, RecoveryPolicy.skip(true, false, true, false, 10));
        assertEquals(RecoveryPolicy.Skip.NOT_A_DOCUMENT, RecoveryPolicy.skip(true, false, false, true, 10));
    }

    @Test
    void theCapIsInclusive() {
        assertEquals(
                RecoveryPolicy.Skip.NONE, RecoveryPolicy.skip(true, false, false, false, RecoveryPolicy.MAX_CHARS));
        assertEquals(
                RecoveryPolicy.Skip.TOO_LARGE,
                RecoveryPolicy.skip(true, false, false, false, RecoveryPolicy.MAX_CHARS + 1));
    }

    @Test
    void aSmallBufferIsCopiedAtThePauseOrAfterTenSecondsOfTyping() {
        assertTrue(RecoveryPolicy.due(false, 1, 500), "paused");
        assertFalse(RecoveryPolicy.due(true, 9_999, 500), "still typing, last copy is recent");
        assertTrue(RecoveryPolicy.due(true, 10_000, 500), "still typing, last copy is old");
        assertTrue(RecoveryPolicy.due(true, Long.MAX_VALUE, 500), "never copied: at once");
    }

    @Test
    void aLargeBufferIsCopiedLessOften() {
        int eightMega = 8 * RecoveryPolicy.SMALL_CHARS;
        assertEquals(0, RecoveryPolicy.minIntervalMillis(RecoveryPolicy.SMALL_CHARS));
        assertEquals(16_000, RecoveryPolicy.minIntervalMillis(eightMega));
        assertEquals(32_000, RecoveryPolicy.maxWaitMillis(eightMega));
        assertFalse(RecoveryPolicy.due(false, 15_999, eightMega), "paused, but the last copy is too recent");
        assertTrue(RecoveryPolicy.due(false, 16_000, eightMega));
        assertFalse(RecoveryPolicy.due(true, 31_999, eightMega));
        assertTrue(RecoveryPolicy.due(true, 32_000, eightMega));
        assertEquals(64_000, RecoveryPolicy.maxWaitMillis(RecoveryPolicy.MAX_CHARS), "the worst case is bounded");
    }
}
