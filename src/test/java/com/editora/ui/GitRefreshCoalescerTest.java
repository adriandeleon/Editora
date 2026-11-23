package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link GitRefreshCoalescer}: light triggers a moment after a full Git refresh keep its status (F2). */
class GitRefreshCoalescerTest {

    private static final long MS = 1_000_000L;

    @Test
    void theFirstTriggerRefreshesInFull() {
        assertEquals(-1, new GitRefreshCoalescer().lightDelay(0));
    }

    @Test
    void aTriggerWithinTheWindowWaitsForTheFullRefreshAtItsEnd() {
        GitRefreshCoalescer c = new GitRefreshCoalescer();
        c.fullStarted(1_000 * MS);
        assertEquals(GitRefreshCoalescer.WINDOW_NANOS - 300 * MS, c.lightDelay(1_300 * MS));
        assertEquals(GitRefreshCoalescer.WINDOW_NANOS - 1_900 * MS, c.lightDelay(2_900 * MS));
    }

    @Test
    void aTriggerAfterTheWindowRefreshesInFullAgain() {
        GitRefreshCoalescer c = new GitRefreshCoalescer();
        c.fullStarted(0);
        assertEquals(-1, c.lightDelay(GitRefreshCoalescer.WINDOW_NANOS));
        c.fullStarted(GitRefreshCoalescer.WINDOW_NANOS); // the trailing refresh starts a new window
        assertEquals(GitRefreshCoalescer.WINDOW_NANOS - MS, c.lightDelay(GitRefreshCoalescer.WINDOW_NANOS + MS));
    }

    @Test
    void aClockThatWentBackRefreshesInFull() {
        GitRefreshCoalescer c = new GitRefreshCoalescer();
        c.fullStarted(5_000 * MS);
        assertEquals(-1, c.lightDelay(4_000 * MS));
    }
}
