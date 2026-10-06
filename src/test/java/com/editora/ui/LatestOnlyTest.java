package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The generation guard behind the find bar's background search: only the latest request ever delivers. */
class LatestOnlyTest {

    /** An executor that runs nothing until told to. */
    private static final class Held implements java.util.concurrent.Executor {
        final Deque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            queue.add(task);
        }

        void runAll() {
            while (!queue.isEmpty()) {
                queue.poll().run();
            }
        }
    }

    @Test
    void aResultIsDeliveredOnTheDeliveryExecutorNotWhereTheWorkRan() {
        Held worker = new Held();
        Held deliver = new Held();
        LatestOnly latest = new LatestOnly(worker, deliver);
        List<String> results = new ArrayList<>();

        latest.submit(() -> "found", results::add);
        assertTrue(latest.pending());
        assertEquals(1, worker.queue.size(), "the work is handed to the worker, not run by the caller");
        assertTrue(results.isEmpty());

        worker.runAll();
        assertTrue(results.isEmpty(), "the worker only computes");
        assertTrue(latest.pending());

        deliver.runAll();
        assertEquals(List.of("found"), results);
        assertFalse(latest.pending());
    }

    @Test
    void aSupersededSearchIsDroppedEvenIfItFinishes() {
        Held worker = new Held();
        Held deliver = new Held();
        LatestOnly latest = new LatestOnly(worker, deliver);
        List<String> results = new ArrayList<>();
        List<String> ran = new ArrayList<>();

        // The first search has finished computing and its delivery is queued when the second is asked for.
        latest.submit(
                () -> {
                    ran.add("first");
                    return "first";
                },
                results::add);
        worker.runAll();
        latest.submit(
                () -> {
                    ran.add("second");
                    return "second";
                },
                results::add);
        worker.runAll();
        deliver.runAll();

        assertEquals(List.of("first", "second"), ran);
        assertEquals(List.of("second"), results, "only the latest request delivers");
        assertFalse(latest.pending());
    }

    @Test
    void aSupersededSearchThatHasNotStartedNeverRuns() {
        Held worker = new Held();
        Held deliver = new Held();
        LatestOnly latest = new LatestOnly(worker, deliver);
        List<String> ran = new ArrayList<>();
        List<String> results = new ArrayList<>();

        latest.submit(
                () -> {
                    ran.add("first");
                    return "first";
                },
                results::add);
        latest.submit(
                () -> {
                    ran.add("second");
                    return "second";
                },
                results::add);
        worker.runAll();
        deliver.runAll();

        assertEquals(List.of("second"), ran, "the cancelled task is skipped by the worker");
        assertEquals(List.of("second"), results);
    }

    @Test
    void cancellingDropsTheResultAndInterruptsTheWork() throws Exception {
        Held deliver = new Held();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            LatestOnly latest = new LatestOnly(pool, deliver);
            List<String> results = new ArrayList<>();
            latest.submit(
                    () -> {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await(); // a search that would never finish by itself
                        } catch (InterruptedException e) {
                            interrupted.set(true);
                        }
                        stopped.countDown();
                        return "late";
                    },
                    results::add);
            assertTrue(started.await(10, TimeUnit.SECONDS));

            latest.cancel();
            assertFalse(latest.pending());
            assertTrue(stopped.await(10, TimeUnit.SECONDS), "the running work was interrupted");
            assertTrue(interrupted.get());
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            deliver.runAll();
            assertTrue(results.isEmpty(), "and what it returned is never delivered");
        } finally {
            pool.shutdownNow();
        }
    }
}
