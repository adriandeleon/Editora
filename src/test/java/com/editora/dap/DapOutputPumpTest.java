package com.editora.dap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Debug console's output pump: bounded, batched, and never out of order. */
class DapOutputPumpTest {

    /** Stands in for {@code Platform.runLater}: scheduled work waits until the test runs a "pulse". */
    private final ArrayDeque<Runnable> fxQueue = new ArrayDeque<>();

    private final List<String> delivered = new ArrayList<>();
    private final DapOutputPump pump = new DapOutputPump(
            fxQueue::add, (epoch, text, category) -> delivered.add(epoch + "|" + category + "|" + text));

    private void pulse() {
        Runnable next = fxQueue.poll();
        assertTrue(next != null, "nothing was scheduled");
        next.run();
    }

    /** Each event used to be its own runLater and its own console append. */
    @Test
    void aBurstOfEventsIsOneScheduledDrainAndOneAppend() {
        for (int i = 0; i < 100; i++) {
            pump.offer(1, "line " + i + "\n", "stdout");
        }
        assertEquals(1, fxQueue.size(), "a hundred events must not queue a hundred FX tasks");

        pulse();

        assertEquals(1, delivered.size(), "neighbouring output of one kind is a single append");
        assertTrue(delivered.get(0).startsWith("1|stdout|line 0\n"));
        assertTrue(delivered.get(0).endsWith("line 99\n"));
        assertTrue(fxQueue.isEmpty());
    }

    @Test
    void orderAndCategoriesArePreservedAcrossRuns() {
        pump.offer(1, "a", "stdout");
        pump.offer(1, "b", "stdout");
        pump.offer(1, "oops", "stderr");
        pump.offer(1, "c", "stdout");

        pulse();

        assertEquals(List.of("1|stdout|ab", "1|stderr|oops", "1|stdout|c"), delivered);
    }

    @Test
    void outputOfDifferentSessionsIsNeverJoined() {
        pump.offer(1, "old", "stdout");
        pump.offer(2, "new", "stdout");

        pulse();

        assertEquals(List.of("1|stdout|old", "2|stdout|new"), delivered);
    }

    @Test
    void aPulseTakesABoundedSliceAndReschedulesForTheRest() {
        int events = DapOutputPump.MAX_EVENTS_PER_PULSE + 10;
        for (int i = 0; i < events; i++) {
            pump.offer(1, "x", "stdout");
        }

        pulse();
        assertEquals(
                "x".repeat(DapOutputPump.MAX_EVENTS_PER_PULSE), delivered.get(0).substring("1|stdout|".length()));
        assertEquals(1, fxQueue.size(), "the remainder waits for the next pulse instead of starving the UI");

        pulse();
        assertEquals("1|stdout|" + "x".repeat(10), delivered.get(1));
        assertTrue(fxQueue.isEmpty());
    }

    /** A debuggee that outruns the UI loses its oldest output, with one notice, instead of the heap. */
    @Test
    void theBacklogIsBoundedAndSaysWhenItDropped() {
        String chunk = "y".repeat(1024);
        int offered = DapOutputPump.MAX_PENDING_CHARS / 1024 + 50;
        for (int i = 0; i < offered; i++) {
            pump.offer(1, chunk, "stdout");
        }

        while (!fxQueue.isEmpty()) {
            pulse();
        }

        assertEquals("1|console|" + DapOutputPump.DROPPED, delivered.get(0));
        int shown = delivered.stream()
                .skip(1)
                .mapToInt(s -> s.length() - "1|stdout|".length())
                .sum();
        assertTrue(shown <= DapOutputPump.MAX_PENDING_CHARS, "kept " + shown + " chars");
        assertTrue(shown >= DapOutputPump.MAX_PENDING_CHARS - 1024, "only the overflow is dropped");
    }

    @Test
    void anEnormousSingleEventIsTruncated() {
        pump.offer(1, "z".repeat(DapOutputPump.MAX_EVENT_CHARS + 500), "stdout");

        pulse();

        String text = delivered.get(0).substring("1|stdout|".length());
        assertEquals(DapOutputPump.MAX_EVENT_CHARS + DapOutputPump.EVENT_TRUNCATED.length(), text.length());
        assertTrue(text.endsWith(DapOutputPump.EVENT_TRUNCATED));
    }

    /** A session's last lines must be shown before the session ends, however much is waiting. */
    @Test
    void flushDeliversEverythingWaitingAtOnce() {
        int events = DapOutputPump.MAX_EVENTS_PER_PULSE * 3;
        for (int i = 0; i < events; i++) {
            pump.offer(1, "x", "stdout");
        }

        pump.flush();

        assertEquals(List.of("1|stdout|" + "x".repeat(events)), delivered);
        pulse(); // the drain that was already scheduled finds nothing left
        assertEquals(1, delivered.size());
        assertTrue(fxQueue.isEmpty());
    }

    @Test
    void emptyOutputIsIgnored() {
        pump.offer(1, "", "stdout");
        pump.offer(1, null, "stdout");
        assertTrue(fxQueue.isEmpty());
    }
}
