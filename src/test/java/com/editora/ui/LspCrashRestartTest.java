package com.editora.ui;

import java.util.ArrayDeque;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sliding-window auto-restart decision behind {@code LspCoordinator.onSessionCrashed} (#666): a crashed
 * language server is auto-restarted, but a crash-looping one must not be re-forked forever — restarts are
 * allowed while the window holds at most {@code maxRestarts} crashes, and old crashes age out so a one-off
 * crash long after a bad spell restarts again.
 */
class LspCrashRestartTest {

    private static final long WINDOW = 1_000; // nanos, scaled down for the test
    private static final int MAX = 2;

    @Test
    void firstCrashesRestartThenTheLoopIsCut() {
        var times = new ArrayDeque<Long>();
        assertTrue(LspCoordinator.recordCrashAndDecide(times, 0, WINDOW, MAX), "1st crash → restart");
        assertTrue(LspCoordinator.recordCrashAndDecide(times, 100, WINDOW, MAX), "2nd crash → restart");
        assertFalse(LspCoordinator.recordCrashAndDecide(times, 200, WINDOW, MAX), "3rd crash in window → give up");
        assertFalse(LspCoordinator.recordCrashAndDecide(times, 300, WINDOW, MAX), "…and stays given up in-window");
    }

    @Test
    void crashesAgeOutOfTheWindowSoALaterCrashRestartsAgain() {
        var times = new ArrayDeque<Long>();
        LspCoordinator.recordCrashAndDecide(times, 0, WINDOW, MAX);
        LspCoordinator.recordCrashAndDecide(times, 100, WINDOW, MAX);
        assertFalse(LspCoordinator.recordCrashAndDecide(times, 200, WINDOW, MAX));
        // Much later: the earlier crashes are outside the window — a fresh crash restarts again.
        assertTrue(LspCoordinator.recordCrashAndDecide(times, 200 + WINDOW + 1, WINDOW, MAX));
        assertEquals(1, times.size(), "aged-out entries are pruned, only the fresh crash remains");
    }

    /** A slow crash loop — e.g. a 60 s initialize-timeout wedge — must still be caught by the window. */
    @Test
    void aSlowLoopSpacedInsideTheWindowIsStillCut() {
        var times = new ArrayDeque<Long>();
        long spacing = WINDOW / 4; // crashes slower than back-to-back but well inside the window
        assertTrue(LspCoordinator.recordCrashAndDecide(times, 0, WINDOW, MAX));
        assertTrue(LspCoordinator.recordCrashAndDecide(times, spacing, WINDOW, MAX));
        assertFalse(LspCoordinator.recordCrashAndDecide(times, 2 * spacing, WINDOW, MAX));
    }
}
