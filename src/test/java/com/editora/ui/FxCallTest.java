package com.editora.ui;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A call reported as timed out must not be applied afterwards. The stand-in for the FX thread is a
 * single-threaded executor the test can hold busy.
 */
class FxCallTest {

    private final ExecutorService fx = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "fake-fx");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void stop() {
        fx.shutdownNow();
    }

    @Test
    void aPromptCallReturnsItsResultAndItsFailure() throws Exception {
        assertEquals("done", FxCall.call(fx, () -> false, () -> "done", 5_000));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> FxCall.call(
                        fx,
                        () -> false,
                        () -> {
                            throw new IllegalStateException("boom");
                        },
                        5_000));
        assertEquals("boom", failure.getMessage());
        assertEquals("inline", FxCall.call(fx, () -> true, () -> "inline", 1), "already on the thread: no queueing");
    }

    @Test
    void aCallThatTimedOutBeforeItStartedIsNeverApplied() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch holding = new CountDownLatch(1);
        fx.execute(() -> {
            holding.countDown();
            try {
                busy.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(holding.await(10, TimeUnit.SECONDS));
        AtomicInteger applied = new AtomicInteger();

        FxCall.NotStarted timeout =
                assertThrows(FxCall.NotStarted.class, () -> FxCall.call(fx, () -> false, applied::incrementAndGet, 50));

        assertTrue(timeout.getMessage().contains("will not be applied"), timeout.getMessage());
        busy.countDown(); // the thread frees up: the abandoned task is next in its queue
        CountDownLatch drained = new CountDownLatch(1);
        fx.execute(drained::countDown);
        assertTrue(drained.await(10, TimeUnit.SECONDS));
        assertEquals(0, applied.get(), "the caller was told it failed, so it must not happen later");
    }

    @Test
    void aCallAlreadyRunningIsWaitedForAndOtherwiseReportedAsStillRunning() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger applied = new AtomicInteger();
        // Started within the timeout, finished within the second period: the real result, not a timeout.
        assertEquals(
                1,
                FxCall.call(
                        fx,
                        () -> false,
                        () -> {
                            sleep(450);
                            return applied.incrementAndGet();
                        },
                        300));

        FxCall.StillRunning running = assertThrows(
                FxCall.StillRunning.class,
                () -> FxCall.call(
                        fx,
                        () -> false,
                        () -> {
                            try {
                                release.await();
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return applied.incrementAndGet();
                        },
                        50));
        assertTrue(running.getMessage().contains("do not repeat it"), running.getMessage());
        release.countDown();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
