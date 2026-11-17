package com.editora.testkit;

import java.time.Duration;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;

/**
 * Waits for a condition a test has no event for (QUAL-07, Pauta 6.0.1: tests do not sleep). A test that can wait on
 * the thing itself (a latch the code counts down, a {@code CompletableFuture}, a listener, {@code drainFx} after
 * the work landed) does that instead; this is for what only shows as state, such as a row the FX thread added
 * after a worker answered, or a line a fake server recorded.
 *
 * <p>The condition is checked on the calling thread, at once and again after each change that could have made it
 * true:
 *
 * <ul>
 *   <li>with the JavaFX toolkit running, after each FX pulse (a pulse ticker runs while anyone waits), so a value
 *       the FX thread sets is seen in the pulse after it was set;
 *   <li>whenever a fake calls {@link #changed()} (the fake language and debug servers, the fake processes do);
 *   <li>when neither comes, after at most {@link #IDLE_RECHECK}: the one place this waits on a clock, for state a
 *       background thread sets that nothing in the test can hear while there is no toolkit, or while a test holds the
 *       FX thread on purpose and so stops the pulses.
 * </ul>
 *
 * <p>It never sleeps: it waits on a condition variable with a deadline.
 */
public final class Await {

    /** How long a wait lasts unless the test says otherwise. Below the suite's five-minute test timeout. */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    /** The longest a waiter goes without re-checking when no pulse and no signal comes. */
    static final Duration IDLE_RECHECK = Duration.ofMillis(20);

    private static final ReentrantLock LOCK = new ReentrantLock();
    private static final Condition CHANGED = LOCK.newCondition();
    private static long generation;
    private static int waiters;
    private static AnimationTimer ticker;
    private static volatile boolean toolkitUp;

    private Await() {}

    /** Wakes every waiter to re-check its condition: a fake calls this when it records something. */
    public static void changed() {
        LOCK.lock();
        try {
            generation++;
            CHANGED.signalAll();
        } finally {
            LOCK.unlock();
        }
    }

    /** Waits until {@code condition} holds, failing with {@code what} after {@link #DEFAULT_TIMEOUT}. */
    public static void until(BooleanSupplier condition, String what) throws InterruptedException {
        until(condition, what, DEFAULT_TIMEOUT);
    }

    /** Waits until {@code condition} holds, failing with {@code what} after {@code timeout}. */
    public static void until(BooleanSupplier condition, String what, Duration timeout) throws InterruptedException {
        if (!holdsWithin(condition, timeout)) {
            throw new AssertionError("Timed out after " + timeout.toMillis() + " ms waiting for " + what);
        }
    }

    /**
     * Waits until {@code value} gives a non-null answer and returns it, failing with {@code what} after {@link
     * #DEFAULT_TIMEOUT}.
     */
    public static <T> T value(Supplier<T> value, String what) throws InterruptedException {
        Object[] found = new Object[1];
        until(() -> (found[0] = value.get()) != null, what);
        @SuppressWarnings("unchecked")
        T t = (T) found[0];
        return t;
    }

    /**
     * Waits until {@code condition} holds or {@code timeout} passes, and says which. For a test that goes on either
     * way and asserts afterwards, with its own message.
     */
    public static boolean holdsWithin(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        if (condition.getAsBoolean()) {
            return true;
        }
        if (onFxThread()) {
            throw new IllegalStateException("Await on the FX thread would stop the pulses it waits for");
        }
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean pulses = enter();
        try {
            while (true) {
                long seen;
                LOCK.lock();
                try {
                    seen = generation;
                } finally {
                    LOCK.unlock();
                }
                if (condition.getAsBoolean()) {
                    return true;
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                LOCK.lockInterruptibly();
                try {
                    long wait = Math.min(left, IDLE_RECHECK.toNanos());
                    while (generation == seen && wait > 0) {
                        wait = CHANGED.awaitNanos(wait);
                    }
                } finally {
                    LOCK.unlock();
                }
            }
        } finally {
            leave(pulses);
        }
    }

    private static boolean onFxThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Counts a waiter in; with the toolkit up, makes sure the pulse ticker runs. Says whether it does. */
    private static boolean enter() {
        if (!toolkitUp()) {
            return false;
        }
        LOCK.lock();
        try {
            if (waiters++ == 0) {
                Platform.runLater(Await::startTicker);
            }
        } finally {
            LOCK.unlock();
        }
        return true;
    }

    private static void leave(boolean pulses) {
        if (!pulses) {
            return;
        }
        LOCK.lock();
        try {
            if (--waiters == 0) {
                Platform.runLater(Await::stopTicker);
            }
        } finally {
            LOCK.unlock();
        }
    }

    /** On the FX thread: starts the ticker when someone is waiting (a stop and a start can arrive out of turn). */
    private static void startTicker() {
        LOCK.lock();
        try {
            if (waiters == 0) {
                return;
            }
        } finally {
            LOCK.unlock();
        }
        if (ticker == null) {
            ticker = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    changed();
                }
            };
        }
        ticker.start();
    }

    private static void stopTicker() {
        LOCK.lock();
        try {
            if (waiters > 0) {
                return;
            }
        } finally {
            LOCK.unlock();
        }
        if (ticker != null) {
            ticker.stop();
        }
    }

    /** Whether the JavaFX toolkit has been started in this JVM (a pure test never starts it). */
    private static boolean toolkitUp() {
        if (toolkitUp) {
            return true;
        }
        try {
            Platform.runLater(() -> {});
            toolkitUp = true;
        } catch (IllegalStateException notStarted) {
            return false;
        }
        return true;
    }
}
