package com.editora.editor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The wait-then-run-on-FX helper behind {@code EditorBuffer.releaseHiddenCaches}, with both seams held. */
class HiddenTabTimerTest {

    /** Collects what the "delayed executor" was given and what was hopped to the "FX thread". */
    private static final class Seams {
        final List<Runnable> delayed = new ArrayList<>();
        final List<Runnable> fx = new ArrayList<>();
        final List<Duration> delays = new ArrayList<>();

        HiddenTabTimer timer(Duration delay, Runnable action) {
            return new HiddenTabTimer(
                    delay,
                    action,
                    d -> {
                        delays.add(d);
                        return delayed::add;
                    },
                    fx::add);
        }

        /** The delay elapses, then the FX thread runs what was posted to it. */
        void elapse() {
            List<Runnable> due = new ArrayList<>(delayed);
            delayed.clear();
            due.forEach(Runnable::run);
            List<Runnable> posted = new ArrayList<>(fx);
            fx.clear();
            posted.forEach(Runnable::run);
        }
    }

    @Test
    void theActionRunsOnceTheDelayHasElapsedAndOnTheFxThread() {
        Seams seams = new Seams();
        AtomicInteger ran = new AtomicInteger();
        HiddenTabTimer timer = seams.timer(Duration.ofMinutes(3), ran::incrementAndGet);
        timer.start();
        assertEquals(List.of(Duration.ofMinutes(3)), seams.delays, "waits out the configured delay");
        assertEquals(0, ran.get(), "nothing before the delay");
        List<Runnable> due = new ArrayList<>(seams.delayed);
        seams.delayed.clear();
        due.forEach(Runnable::run);
        assertEquals(0, ran.get(), "the timer thread only posts to the FX thread");
        assertEquals(1, seams.fx.size());
        seams.fx.forEach(Runnable::run);
        assertEquals(1, ran.get());
    }

    @Test
    void aCancelBeforeTheDelayElapsesDropsTheAction() {
        Seams seams = new Seams();
        AtomicInteger ran = new AtomicInteger();
        HiddenTabTimer timer = seams.timer(Duration.ofSeconds(1), ran::incrementAndGet);
        timer.start();
        timer.cancel(); // the tab was shown again
        seams.elapse();
        assertEquals(0, ran.get(), "a stale callback finds the generation changed and does nothing");
    }

    @Test
    void aRestartSupersedesTheEarlierWaitSoTheActionRunsOnce() {
        Seams seams = new Seams();
        AtomicInteger ran = new AtomicInteger();
        HiddenTabTimer timer = seams.timer(Duration.ofSeconds(1), ran::incrementAndGet);
        timer.start();
        timer.start(); // hidden, shown, hidden again within the delay
        assertEquals(2, seams.delayed.size(), "both waits are pending");
        seams.elapse();
        assertEquals(1, ran.get(), "only the latest wait runs the action");
    }

    @Test
    void aPendingWaitDoesNotKeepADroppedTimerOrItsBufferReachable() throws Exception {
        Seams seams = new Seams();
        AtomicInteger ran = new AtomicInteger();
        java.lang.ref.WeakReference<HiddenTabTimer> dropped = startAndDrop(seams, ran);
        for (int i = 0; i < 20 && dropped.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertTrue(dropped.get() == null, "the waiting task holds the timer (and so the buffer) only weakly");
        seams.elapse();
        assertEquals(0, ran.get(), "a wait whose owner is gone runs nothing");
    }

    private static java.lang.ref.WeakReference<HiddenTabTimer> startAndDrop(Seams seams, AtomicInteger ran) {
        HiddenTabTimer timer = seams.timer(Duration.ofSeconds(1), ran::incrementAndGet);
        timer.start();
        return new java.lang.ref.WeakReference<>(timer);
    }

    @Test
    void theDefaultSeamsWaitOffThreadAndHopBack() throws Exception {
        // Only the hop is checked here (the real executor is CompletableFuture's delay scheduler, which needs
        // no thread of ours): a cancelled timer must leave nothing behind for a later start to trip over.
        AtomicInteger ran = new AtomicInteger();
        List<Runnable> fx = new ArrayList<>();
        HiddenTabTimer timer = new HiddenTabTimer(
                Duration.ofMillis(1),
                ran::incrementAndGet,
                d -> java.util.concurrent.CompletableFuture.delayedExecutor(
                        d.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                r -> {
                    synchronized (fx) {
                        fx.add(r);
                    }
                });
        timer.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            synchronized (fx) {
                if (!fx.isEmpty()) {
                    break;
                }
            }
            Thread.sleep(2);
        }
        List<Runnable> posted;
        synchronized (fx) {
            posted = new ArrayList<>(fx);
        }
        assertEquals(1, posted.size(), "the wait ended and posted to the FX seam");
        posted.forEach(Runnable::run);
        assertTrue(ran.get() == 1);
    }
}
