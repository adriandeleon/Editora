package com.editora.process;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * Hands a stream to the FX thread in batches a frame apart. The thread that receives the stream adds each item
 * to a buffer of its own and calls {@link #request}; the FX thread then runs the drain, which empties that
 * buffer, at once after a quiet spell and otherwise no sooner than {@link OutputPump#MIN_DRAIN_INTERVAL_MILLIS}
 * after the drain before it began. However fast the items arrive, the FX queue holds at most one drain.
 *
 * <p>{@link OutputPump} does this for the output of a process; this is the same pacing for a stream that is not
 * one (an agent's reply as it is written, the progress lines of a Git command).
 */
public final class PacedDrain {

    private static final long MIN_INTERVAL_NANOS = OutputPump.MIN_DRAIN_INTERVAL_MILLIS * 1_000_000L;

    /** Wakes the FX thread for a drain that has to wait out the interval. */
    private static final class Pacer {
        static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "paced-drain");
            t.setDaemon(true);
            return t;
        });
    }

    private final Runnable drain;
    private final Consumer<Runnable> fx;
    private final AtomicBoolean scheduled = new AtomicBoolean();
    /** When the last drain began; far enough in the past at first for the first request to run at once. */
    private volatile long lastDrainNanos = System.nanoTime() - MIN_INTERVAL_NANOS;

    private volatile int drains;

    /** @param drain empties the caller's buffer and applies it; always run on the FX thread */
    public PacedDrain(Runnable drain) {
        this(drain, Platform::runLater);
    }

    /** As above, with the hand-over to the UI thread given: a test has no toolkit. */
    PacedDrain(Runnable drain, Consumer<Runnable> fx) {
        this.drain = drain;
        this.fx = fx;
    }

    /** Something was added to the buffer: a drain will run. Any thread; a request while one is queued adds nothing. */
    public void request() {
        if (!scheduled.compareAndSet(false, true)) {
            return;
        }
        long wait = MIN_INTERVAL_NANOS - (System.nanoTime() - lastDrainNanos);
        if (wait <= 0) {
            fx.accept(this::run);
        } else {
            Pacer.TIMER.schedule(() -> fx.accept(this::run), wait, TimeUnit.NANOSECONDS);
        }
    }

    private void run() {
        lastDrainNanos = System.nanoTime();
        drains++;
        scheduled.set(false); // before draining: an item added from here on asks for a drain of its own
        drain.run();
    }

    /** How many drains have run: lets a test show that a burst shared one. */
    public int drains() {
        return drains;
    }
}
