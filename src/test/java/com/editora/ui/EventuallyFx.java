package com.editora.ui;

import java.time.Duration;
import java.util.concurrent.Callable;

import com.editora.testkit.Await;

/**
 * {@link Await} for a check that may throw, such as one that reads the scene with {@link FxTestSupport#callOnFx}.
 * The check runs on the test thread, at once and again after each FX pulse; what it throws ends the wait and is
 * rethrown as it was.
 */
final class EventuallyFx {

    /** A condition that may throw. */
    @FunctionalInterface
    interface Check {

        boolean holds() throws Exception;
    }

    private static final class Thrown extends RuntimeException {

        Thrown(Exception cause) {
            super(cause);
        }
    }

    private EventuallyFx() {}

    /** Waits until {@code check} holds, failing with {@code what} after {@code timeout}. */
    static void until(String what, Duration timeout, Check check) throws Exception {
        if (!holdsWithin(timeout, check)) {
            throw new AssertionError("Timed out after " + timeout.toMillis() + " ms waiting for " + what);
        }
    }

    /** Waits until {@code check} holds, failing with {@code what} after {@link Await#DEFAULT_TIMEOUT}. */
    static void until(String what, Check check) throws Exception {
        until(what, Await.DEFAULT_TIMEOUT, check);
    }

    /** Waits until {@code condition}, read on the FX thread, holds; fails with {@code what} after {@code timeout}. */
    static void untilOnFx(String what, Duration timeout, Callable<Boolean> condition) throws Exception {
        until(what, timeout, () -> Boolean.TRUE.equals(FxTestSupport.callOnFx(condition)));
    }

    /** Waits until {@code value}, read on the FX thread, is not null and returns it. */
    static <T> T valueOnFx(String what, Duration timeout, Callable<T> value) throws Exception {
        Object[] found = new Object[1];
        until(what, timeout, () -> (found[0] = FxTestSupport.callOnFx(value)) != null);
        @SuppressWarnings("unchecked")
        T t = (T) found[0];
        return t;
    }

    /** Waits until {@code check} holds or {@code timeout} passes, and says which. */
    static boolean holdsWithin(Duration timeout, Check check) throws Exception {
        try {
            return Await.holdsWithin(
                    () -> {
                        try {
                            return check.holds();
                        } catch (RuntimeException | Error e) {
                            throw e;
                        } catch (Exception e) {
                            throw new Thrown(e);
                        }
                    },
                    timeout);
        } catch (Thrown thrown) {
            throw (Exception) thrown.getCause();
        }
    }
}
