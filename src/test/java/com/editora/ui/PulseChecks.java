package com.editora.ui;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import com.editora.testkit.Await;

/**
 * {@link Await} for FX tests whose condition reads the scene graph or calls {@code callOnFx}: the check may throw,
 * and {@link #untilOnFx} evaluates it on the FX thread. Like {@code Await}, it re-checks after each FX pulse and
 * never sleeps (QUAL-07, Pauta 6.0.1).
 */
final class PulseChecks {

    /** A condition that may throw, such as one that calls {@code FxTestSupport.callOnFx}. */
    @FunctionalInterface
    interface Check {

        boolean holds() throws Exception;
    }

    private PulseChecks() {}

    /** Waits until {@code check} holds, checked on the calling thread, failing with {@code what}. */
    static void until(Check check, String what) throws InterruptedException {
        Await.until(() -> unchecked(check), what);
    }

    /** As {@link #until(Check, String)}, for at most {@code timeout}. */
    static void until(Check check, String what, Duration timeout) throws InterruptedException {
        Await.until(() -> unchecked(check), what, timeout);
    }

    /** Waits until {@code check} holds or {@code timeout} passes, and says which. */
    static boolean holdsWithin(Check check, Duration timeout) throws InterruptedException {
        return Await.holdsWithin(() -> unchecked(check), timeout);
    }

    /** Waits until {@code condition}, evaluated on the FX thread, holds, failing with {@code what}. */
    static void untilOnFx(BooleanSupplier condition, String what) throws InterruptedException {
        until(() -> FxTestSupport.callOnFx(condition::getAsBoolean), what);
    }

    /** As {@link #untilOnFx(BooleanSupplier, String)}, for at most {@code timeout}. */
    static void untilOnFx(BooleanSupplier condition, String what, Duration timeout) throws InterruptedException {
        until(() -> FxTestSupport.callOnFx(condition::getAsBoolean), what, timeout);
    }

    /** Waits until {@code condition}, evaluated on the FX thread, holds or {@code timeout} passes; says which. */
    static boolean holdsOnFxWithin(BooleanSupplier condition, Duration timeout) throws InterruptedException {
        return holdsWithin(() -> FxTestSupport.callOnFx(condition::getAsBoolean), timeout);
    }

    /**
     * Waits for {@code count} FX pulses, then for the FX queue: for a test that waits on CSS and layout, which run in
     * a pulse, and on what the FX thread queued meanwhile. The pulse is the event, not a fixed time.
     */
    static void pulses(int count) throws Exception {
        java.util.concurrent.CountDownLatch seen = new java.util.concurrent.CountDownLatch(count);
        javafx.animation.AnimationTimer counter = new javafx.animation.AnimationTimer() {
            @Override
            public void handle(long now) {
                seen.countDown();
            }
        };
        FxTestSupport.runOnFx(counter::start);
        try {
            if (!seen.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new AssertionError("the FX pulses stopped");
            }
        } finally {
            FxTestSupport.runOnFx(counter::stop);
        }
        FxTestSupport.drainFx();
    }

    private static boolean unchecked(Check check) {
        try {
            return check.holds();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
