package com.editora.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UpdateStatusTest {

    @Test
    void noAnswerYetClaimsNothing() {
        assertEquals(UpdateStatus.Verdict.NOT_CHECKED, UpdateStatus.NONE.verdict("1.0.0"));
        assertEquals(UpdateStatus.Verdict.NOT_CHECKED, new UpdateStatus(" ", "", 5).verdict("1.0.0"));
        assertNull(UpdateStatus.NONE.update("1.0.0"));
    }

    @Test
    void theRunningReleaseOrAnOlderOneIsUpToDate() {
        assertEquals(UpdateStatus.Verdict.UP_TO_DATE, new UpdateStatus("1.0.0", "", 5).verdict("1.0.0"));
        assertEquals(UpdateStatus.Verdict.UP_TO_DATE, new UpdateStatus("0.9.0", "", 5).verdict("1.0.0"));
        // A snapshot of the next release is ahead of the newest published one.
        assertEquals(UpdateStatus.Verdict.UP_TO_DATE, new UpdateStatus("1.0.0", "", 5).verdict("1.1.0-SNAPSHOT"));
        assertNull(new UpdateStatus("1.0.0", "", 5).update("1.0.0"));
    }

    @Test
    void aNewerReleaseIsOfferedWithItsPage() {
        UpdateStatus status = new UpdateStatus("1.2.0", "https://example.test/r/1.2.0", 5);
        assertEquals(UpdateStatus.Verdict.UPDATE_AVAILABLE, status.verdict("1.0.0"));
        assertEquals(new ReleaseInfo("1.2.0", "https://example.test/r/1.2.0", ""), status.update("1.0.0"));
    }

    @Test
    void anUpgradeToTheReleaseFoundMakesItUpToDate() {
        // The answer outlives the check: after updating to what it found, the same answer reads as current.
        UpdateStatus status = new UpdateStatus("1.2.0", "", 5);
        assertEquals(UpdateStatus.Verdict.UPDATE_AVAILABLE, status.verdict("1.1.0"));
        assertEquals(UpdateStatus.Verdict.UP_TO_DATE, status.verdict("1.2.0"));
    }
}
