package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The restore cadence's policy: the selected file first, then tab order, time-boxed batches, out-of-order claims. */
class RestoreScheduleTest {

    /** A clock that advances {@code stepNanos} on every build, so a budget is spent after a known count. */
    private static final class Clock {
        long now;
        final long stepNanos;

        Clock(long stepNanos) {
            this.stepNanos = stepNanos;
        }

        long read() {
            return now;
        }

        boolean build(List<Integer> built, int index) {
            built.add(index);
            now += stepNanos;
            return true;
        }
    }

    @Test
    void theSelectedEntryComesFirstThenTabOrder() {
        RestoreSchedule schedule = new RestoreSchedule(5, 3);
        assertEquals(3, schedule.first());
        assertEquals(5, schedule.size());
        List<Integer> seen = new ArrayList<>();
        while (schedule.nextUnclaimed() >= 0) {
            int next = schedule.nextUnclaimed();
            seen.add(next);
            assertTrue(schedule.claim(next));
        }
        assertEquals(List.of(3, 0, 1, 2, 4), seen);
        assertTrue(schedule.isExhausted());
        assertEquals(0, schedule.remaining());
    }

    @Test
    void noSelectedEntryMeansPlainTabOrder() {
        assertEquals(0, new RestoreSchedule(3, -1).first());
        assertEquals(0, new RestoreSchedule(3, 7).first(), "an out-of-range first is ignored");
        assertEquals(-1, new RestoreSchedule(0, 0).first(), "nothing to restore");
        assertTrue(new RestoreSchedule(0, -1).isExhausted());
        assertThrows(IllegalArgumentException.class, () -> new RestoreSchedule(-1, 0));
    }

    @Test
    void aBatchBuildsUntilTheBudgetIsSpentButAlwaysAtLeastOne() {
        RestoreSchedule schedule = new RestoreSchedule(6, 2);
        Clock clock = new Clock(3); // three "nanoseconds" per build
        List<Integer> built = new ArrayList<>();

        // Budget 7: the first build lands at 3, the second at 6, the third at 9 ≥ 7 → three per batch.
        List<Integer> first = schedule.batch(clock::read, 7, i -> clock.build(built, i));
        assertEquals(List.of(2, 0, 1), first);
        assertEquals(3, schedule.remaining());

        // A budget smaller than one build still builds exactly one.
        List<Integer> second = schedule.batch(clock::read, 1, i -> clock.build(built, i));
        assertEquals(List.of(3), second);

        List<Integer> rest = schedule.batch(clock::read, Long.MAX_VALUE, i -> clock.build(built, i));
        assertEquals(List.of(4, 5), rest);
        assertTrue(schedule.isExhausted());
        assertEquals(List.of(2, 0, 1, 3, 4, 5), built, "every entry built exactly once, in fill order");
        assertEquals(List.of(), schedule.batch(clock::read, 1, i -> clock.build(built, i)), "nothing left");
    }

    @Test
    void anEntryClaimedOutOfOrderIsSkippedByTheCadence() {
        RestoreSchedule schedule = new RestoreSchedule(4, 0);
        assertTrue(schedule.claim(2), "the user clicked tab 2 before its turn");
        assertFalse(schedule.claim(2), "a second claim of the same entry is refused");
        assertTrue(schedule.isClaimed(2));
        assertFalse(schedule.isClaimed(1));
        List<Integer> built = new ArrayList<>();
        schedule.batch(() -> 0L, Long.MAX_VALUE, i -> {
            built.add(i);
            return true;
        });
        assertEquals(List.of(0, 1, 3), built);
        assertTrue(schedule.isExhausted());
    }

    @Test
    void anEntryThatHadNothingToBuildIsClaimedButNotReported() {
        RestoreSchedule schedule = new RestoreSchedule(3, 1);
        // The builder answers false for a tab that was closed before its turn: it counts as handled (no
        // retry), but the batch does not list it as a load to wait for.
        List<Integer> started = schedule.batch(() -> 0L, Long.MAX_VALUE, i -> i != 0);
        assertEquals(List.of(1, 2), started);
        assertTrue(schedule.isClaimed(0));
        assertTrue(schedule.isExhausted());
    }
}
