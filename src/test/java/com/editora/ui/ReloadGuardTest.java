package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReloadGuardTest {

    @Test
    void anUntouchedCleanBufferMayBeReloaded() {
        assertTrue(new ReloadGuard(7, false).stillHolds(7, false));
    }

    @Test
    void anEditMadeWhileTheReadWasInFlightStopsTheReload() {
        assertFalse(new ReloadGuard(7, false).stillHolds(8, true));
    }

    @Test
    void anEditThatWasUndoneStillStopsTheReloadBecauseTheUndoHistoryIsTheUsers() {
        assertFalse(new ReloadGuard(7, false).stillHolds(9, false));
    }

    @Test
    void aBufferThatBecameUnsavedWithoutAnEditIsNotReloaded() {
        assertFalse(new ReloadGuard(7, false).stillHolds(7, true));
    }

    @Test
    void aChosenReloadOfAnUnsavedBufferAppliesToExactlyThatState() {
        assertTrue(new ReloadGuard(7, true).stillHolds(7, true));
        assertFalse(new ReloadGuard(7, true).stillHolds(8, true));
    }
}
