package com.editora.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import javafx.application.Platform;

/**
 * Runs a task on the FX thread on behalf of a caller that waits for the answer on another thread — an MCP
 * HTTP worker, the ACP agent's request thread — with a timeout that <em>means</em> what it reports.
 *
 * <p>The plain {@code runLater} + {@code future.get(timeout)} construction abandons the wait, not the task: the
 * caller is told the call failed, and the queued edit, save or command still runs once the FX thread frees up.
 * A client that retries then runs it twice; an agent whose buffer write "failed" writes the file some other
 * way underneath a buffer that is about to change.
 *
 * <p>Here a task that has not <em>started</em> when the timeout expires is cancelled, and never runs
 * ({@link NotStarted}: nothing happened, a retry is safe). A task that has started cannot be taken back; the
 * caller waits a second, equal period for its real outcome and is otherwise told the truth ({@link
 * StillRunning}: it is being applied, do not repeat it).
 */
final class FxCall {

    /** The FX thread never got to the task; it has been cancelled and will not run. */
    static final class NotStarted extends TimeoutException {
        private static final long serialVersionUID = 1L;

        NotStarted(long millis) {
            super("The editor was busy and did not start this request within " + seconds(millis)
                    + " s. It was cancelled and will not be applied; it is safe to retry.");
        }
    }

    /** The task is running (typically behind a dialog the user has not answered); its outcome is unknown. */
    static final class StillRunning extends TimeoutException {
        private static final long serialVersionUID = 1L;

        StillRunning(long millis) {
            super("The editor started this request but has not finished it after " + seconds(millis)
                    + " s (it may be waiting for the user). It was not cancelled: do not repeat it; check the"
                    + " result first.");
        }
    }

    private static final int PENDING = 0;
    private static final int STARTED = 1;
    private static final int CANCELLED = 2;

    private FxCall() {}

    /** Runs {@code task} on the FX thread (inline when already on it) and returns its result. */
    static <T> T call(Supplier<T> task, long timeoutMillis) throws Exception {
        return call(Platform::runLater, Platform::isFxApplicationThread, task, timeoutMillis);
    }

    /** As {@link #call(Supplier, long)}, against an arbitrary single-threaded executor (the test seam). */
    static <T> T call(Executor fx, BooleanSupplier onFxThread, Supplier<T> task, long timeoutMillis) throws Exception {
        if (onFxThread.getAsBoolean()) {
            return task.get();
        }
        AtomicInteger state = new AtomicInteger(PENDING);
        CompletableFuture<T> result = new CompletableFuture<>();
        fx.execute(() -> {
            if (!state.compareAndSet(PENDING, STARTED)) {
                return; // the caller gave up and was told so: applying it now would contradict that answer
            }
            try {
                result.complete(task.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            try {
                return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException notYet) {
                if (state.compareAndSet(PENDING, CANCELLED)) {
                    throw new NotStarted(timeoutMillis);
                }
            }
            try {
                return result.get(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillRunning) {
                throw new StillRunning(2 * timeoutMillis);
            }
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw failed;
        }
    }

    private static long seconds(long millis) {
        return Math.max(1, millis / 1000);
    }
}
