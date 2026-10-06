package com.editora.git;

import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** After a status timeout the repository is left alone for a while, longer each time, until it answers. */
class GitStatusBackoffTest {

    private static final Path REPO = Path.of("/repo");
    private static final long S = 1_000_000_000L;

    @Test
    void aTimeoutStartsAWaitThatDoublesAndIsCapped() {
        assertEquals(Duration.ofSeconds(30), GitStatusBackoff.delay(1));
        assertEquals(Duration.ofSeconds(60), GitStatusBackoff.delay(2));
        assertEquals(Duration.ofSeconds(120), GitStatusBackoff.delay(3));
        assertEquals(Duration.ofMinutes(5), GitStatusBackoff.delay(5));
        assertEquals(Duration.ofMinutes(5), GitStatusBackoff.delay(500));
    }

    @Test
    void theRepositoryIsLeftAloneOnlyUntilTheWaitEnds() {
        GitStatusBackoff backoff = new GitStatusBackoff();
        assertFalse(backoff.waiting(REPO, 0));
        backoff.timedOut(REPO, 100 * S);
        assertTrue(backoff.waiting(REPO, 101 * S));
        assertTrue(backoff.waiting(REPO, 129 * S));
        assertFalse(backoff.waiting(REPO, 130 * S), "time to ask again");
        assertFalse(backoff.waiting(Path.of("/other"), 101 * S), "another repository is not held back");

        backoff.timedOut(REPO, 130 * S); // it timed out again: wait twice as long
        assertTrue(backoff.waiting(REPO, 189 * S));
        assertFalse(backoff.waiting(REPO, 190 * S));
    }

    @Test
    void anAnswerEndsTheWaitAndResetsTheDoubling() {
        GitStatusBackoff backoff = new GitStatusBackoff();
        backoff.timedOut(REPO, 0);
        backoff.timedOut(REPO, 0);
        backoff.succeeded(REPO);
        assertFalse(backoff.waiting(REPO, 1));
        backoff.timedOut(REPO, 0);
        assertFalse(backoff.waiting(REPO, 30 * S), "back to the first, short wait");
    }

    @Test
    void theClockMayWrap() {
        GitStatusBackoff backoff = new GitStatusBackoff();
        long nearWrap = Long.MAX_VALUE - 5 * S;
        backoff.timedOut(REPO, nearWrap);
        assertTrue(backoff.waiting(REPO, nearWrap + S));
        assertFalse(backoff.waiting(REPO, nearWrap + 31 * S));
    }
}
