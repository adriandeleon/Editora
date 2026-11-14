package com.editora.process;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A stream is handed to the UI thread in batches a frame apart, never one hand-over per item. */
class PacedDrainTest {

    @Test
    void aBurstOfRequestsSharesOneHandOverAndTheFirstRunsAtOnce() {
        List<Runnable> handed = new ArrayList<>();
        int[] drained = {0};
        PacedDrain drain = new PacedDrain(() -> drained[0]++, handed::add);

        for (int i = 0; i < 1000; i++) {
            drain.request();
        }
        assertEquals(1, handed.size(), "a thousand items, one task on the UI thread's queue");
        assertEquals(0, drained[0], "handed over, not run yet");
        handed.get(0).run();
        assertEquals(1, drained[0]);
        assertEquals(1, drain.drains());
    }

    @Test
    void theNextDrainWaitsOutTheRestOfTheFrame() throws Exception {
        List<Long> starts = new CopyOnWriteArrayList<>();
        CountDownLatch second = new CountDownLatch(2);
        PacedDrain[] drain = new PacedDrain[1];
        drain[0] = new PacedDrain(
                () -> {
                    starts.add(System.nanoTime());
                    second.countDown();
                },
                Runnable::run); // the hand-over runs where it is made: the pacing is what is measured

        drain[0].request(); // after a quiet spell: at once
        assertEquals(1, starts.size());
        drain[0].request(); // straight after: not before the frame is over
        assertTrue(second.await(5, TimeUnit.SECONDS), "the second drain ran");
        long apartMillis = (starts.get(1) - starts.get(0)) / 1_000_000;
        assertTrue(
                apartMillis >= OutputPump.MIN_DRAIN_INTERVAL_MILLIS - 1,
                "two drains " + apartMillis + " ms apart, less than a frame");
    }

    @Test
    void anItemAddedWhileADrainRunsAsksForADrainOfItsOwn() throws Exception {
        List<Runnable> handed = new CopyOnWriteArrayList<>();
        PacedDrain[] drain = new PacedDrain[1];
        boolean[] again = {true};
        drain[0] = new PacedDrain(
                () -> {
                    if (again[0]) {
                        again[0] = false;
                        drain[0].request(); // an item that arrived while this drain was emptying the buffer
                    }
                },
                handed::add);
        drain[0].request();
        handed.get(0).run();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (handed.size() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertEquals(2, handed.size(), "it is not lost: a second hand-over follows");
    }
}
