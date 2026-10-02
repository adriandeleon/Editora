package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class SaveRefusalTest {

    @Test
    void anOrdinaryBufferMayBeSaved() {
        assertEquals(SaveRefusal.NONE, SaveRefusal.of(false, false, false, false));
        assertNull(SaveRefusal.NONE.messageKey());
    }

    @Test
    void eachIncompleteStateRefusesWithItsOwnMessage() {
        assertEquals(SaveRefusal.LOADING, SaveRefusal.of(false, true, false, false));
        assertEquals(SaveRefusal.TRUNCATED, SaveRefusal.of(false, false, true, false));
        assertEquals(SaveRefusal.LOG_TRIMMED, SaveRefusal.of(false, false, false, true));
        assertNotNull(SaveRefusal.LOADING.messageKey());
        assertNotNull(SaveRefusal.TRUNCATED.messageKey());
        assertNotNull(SaveRefusal.LOG_TRIMMED.messageKey());
    }

    @Test
    void aClosedBufferIsRefusedSilently() {
        assertEquals(SaveRefusal.DISPOSED, SaveRefusal.of(true, false, false, false));
        assertNull(SaveRefusal.DISPOSED.messageKey(), "there is no window left to report to");
    }

    @Test
    void theMostFundamentalReasonWins() {
        assertEquals(SaveRefusal.DISPOSED, SaveRefusal.of(true, true, true, true));
        assertEquals(SaveRefusal.LOADING, SaveRefusal.of(false, true, true, true));
        assertEquals(SaveRefusal.TRUNCATED, SaveRefusal.of(false, false, true, true));
    }
}
