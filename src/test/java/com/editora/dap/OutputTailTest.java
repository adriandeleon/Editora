package com.editora.dap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OutputTailTest {

    private static final long MS = 1_000_000;

    @Test
    void noOutputEndsAtOnce() {
        assertEquals(0, OutputTail.waitMillis(5_000 * MS, 0, 5_000 * MS));
    }

    @Test
    void outputThatHasStoppedEndsAtOnce() {
        assertEquals(0, OutputTail.waitMillis(5_000 * MS, 4_000 * MS, 5_000 * MS));
    }

    @Test
    void outputStillArrivingWaitsForTheQuietPeriod() {
        assertEquals(OutputTail.QUIET_MILLIS - 100, OutputTail.waitMillis(5_100 * MS, 5_000 * MS, 5_000 * MS));
    }

    @Test
    void neverWaitsLongerThanTheCap() {
        long done = 1_000 * MS;
        long now = done + OutputTail.MAX_WAIT_MILLIS * MS;
        assertEquals(0, OutputTail.waitMillis(now, now - 10 * MS, done));
        long almost = done + (OutputTail.MAX_WAIT_MILLIS - 50) * MS;
        assertEquals(50, OutputTail.waitMillis(almost, almost, done));
    }
}
