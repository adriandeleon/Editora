package com.editora.github;

import com.editora.github.ChecksParser.Overall;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChecksPollTest {

    @Test
    void onlyAPendingRollUpIsPolled() {
        assertEquals(ChecksPoll.Next.WAIT, ChecksPoll.after(Overall.PENDING, 0));
        assertEquals(ChecksPoll.Next.STOP, ChecksPoll.after(Overall.PASS, 0));
        assertEquals(ChecksPoll.Next.STOP, ChecksPoll.after(Overall.FAIL, 0));
        assertEquals(ChecksPoll.Next.STOP, ChecksPoll.after(Overall.NONE, 0));
    }

    @Test
    void pollingStopsAtTheCap() {
        assertEquals(ChecksPoll.Next.WAIT, ChecksPoll.after(Overall.PENDING, ChecksPoll.MAX_ATTEMPTS - 1));
        assertEquals(ChecksPoll.Next.STOP, ChecksPoll.after(Overall.PENDING, ChecksPoll.MAX_ATTEMPTS));
    }

    @Test
    void theIntervalBacksOffAndThenStaysPut() {
        long previous = 0;
        for (int attempt = 0; attempt < 5; attempt++) {
            long delay = ChecksPoll.delaySeconds(attempt);
            assertTrue(delay > previous, "attempt " + attempt + " waits longer than the one before");
            previous = delay;
        }
        assertEquals(15, ChecksPoll.delaySeconds(0));
        assertEquals(300, ChecksPoll.delaySeconds(4));
        assertEquals(300, ChecksPoll.delaySeconds(400));
        assertEquals(15, ChecksPoll.delaySeconds(-1));
    }

    @Test
    void theRunIdComesFromAnActionsJobLink() {
        assertEquals(
                37658234247L, ChecksPoll.runId("https://github.com/o/r/actions/runs/37658234247/job/112918782075"));
        assertEquals(12L, ChecksPoll.runId("https://ghe.example.com/o/r/actions/runs/12"));
        assertEquals(-1, ChecksPoll.runId("https://ci.example.com/build/99"), "an external status has no run");
        assertEquals(-1, ChecksPoll.runId("https://github.com/o/r/actions/runs/"));
        assertEquals(-1, ChecksPoll.runId("https://github.com/o/r/actions/runs/99999999999999999999"));
        assertEquals(-1, ChecksPoll.runId(""));
        assertEquals(-1, ChecksPoll.runId(null));
    }
}
