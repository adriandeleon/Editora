package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ThrottlePolicy}: prompt first run, then at most one per interval however often it is asked. */
class ThrottlePolicyTest {

    private static final long INTERVAL = 100;

    private final ThrottlePolicy policy = new ThrottlePolicy(INTERVAL);

    @Test
    void theFirstRequestRunsAtOnce() {
        assertEquals(0, policy.request(5));
        assertTrue(policy.scheduled());
    }

    @Test
    void requestsWhileARunIsDueScheduleNothingMore() {
        policy.request(0);
        assertEquals(ThrottlePolicy.ALREADY_SCHEDULED, policy.request(1));
        assertEquals(ThrottlePolicy.ALREADY_SCHEDULED, policy.request(99));
    }

    @Test
    void aRequestSoonAfterARunWaitsOutTheInterval() {
        policy.request(1_000);
        policy.ran(1_000);

        assertEquals(70, policy.request(1_030), "30 into the interval: 70 to go");
        assertEquals(ThrottlePolicy.ALREADY_SCHEDULED, policy.request(1_060));
    }

    @Test
    void aRequestAfterAQuietIntervalRunsAtOnce() {
        policy.request(1_000);
        policy.ran(1_000);

        assertEquals(0, policy.request(1_100));
        policy.ran(1_100);
        assertEquals(0, policy.request(9_999));
    }

    /**
     * The property a trailing-edge debounce lacks: requests arriving faster than the interval, without a
     * pause, still get a run every interval instead of none until they stop.
     */
    @Test
    void aSteadyStreamRunsOncePerIntervalInsteadOfStarving() {
        int runs = 0;
        long due = -1;
        for (long now = 0; now <= 1_000; now += 10) { // a request every 10, for ten intervals
            if (due >= 0 && now >= due) {
                policy.ran(now);
                runs++;
                due = -1;
            }
            long delay = policy.request(now);
            if (delay != ThrottlePolicy.ALREADY_SCHEDULED) {
                due = now + delay;
                if (delay == 0) {
                    policy.ran(now);
                    runs++;
                    due = -1;
                }
            }
        }
        assertEquals(11, runs, "one at the start, then one per interval — not 101, and not zero");
    }

    @Test
    void aCancelledRunCanBeRequestedAgain() {
        policy.request(0);
        policy.cancel();

        assertFalse(policy.scheduled());
        assertEquals(0, policy.request(1));
    }
}
