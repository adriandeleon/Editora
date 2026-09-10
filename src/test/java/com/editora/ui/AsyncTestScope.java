package com.editora.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Per-test ownership for asynchronous UI scenarios. It provides bounded worker and FX completion barriers,
 * closes owned resources even after an assertion failure, and turns failures from test-owned threads or
 * unobserved FX callbacks into failures of the owning test.
 */
final class AsyncTestScope implements AutoCloseable {

    @FunctionalInterface
    interface CheckedRunnable {

        void run() throws Exception;
    }

    private record FxExceptionState(Thread thread, Thread.UncaughtExceptionHandler previous) {}

    private static final long TIMEOUT_SECONDS = 10;

    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private final Deque<CheckedRunnable> shutdownSignals = new ArrayDeque<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Queue<Throwable> asynchronousFailures = new ConcurrentLinkedQueue<>();
    private final FxExceptionState fxExceptionState;

    AsyncTestScope() throws Exception {
        fxExceptionState = FxTestSupport.callOnFx(() -> {
            Thread fxThread = Thread.currentThread();
            Thread.UncaughtExceptionHandler previous = fxThread.getUncaughtExceptionHandler();
            fxThread.setUncaughtExceptionHandler((thread, failure) -> asynchronousFailures.add(failure));
            return new FxExceptionState(fxThread, previous);
        });
    }

    <T extends AutoCloseable> T own(T resource) {
        resources.push(resource);
        return resource;
    }

    void onClose(CheckedRunnable cleanup) {
        shutdownSignals.push(cleanup);
    }

    Thread start(String name, CheckedRunnable task) {
        Thread thread = Thread.ofVirtual().name(name).unstarted(() -> {
            try {
                task.run();
            } catch (Throwable failure) {
                asynchronousFailures.add(failure);
            }
        });
        threads.add(thread);
        thread.start();
        return thread;
    }

    void await(CountDownLatch latch, String description) throws Exception {
        if (!latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new AssertionError("Timed out waiting for " + description);
        }
        assertNoAsynchronousFailures();
    }

    <T> T await(Future<T> future) throws Exception {
        try {
            T result = future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNoAsynchronousFailures();
            return result;
        } catch (ExecutionException e) {
            rethrow(e.getCause());
            throw new AssertionError("unreachable");
        }
    }

    void awaitWorker(ExecutorService worker) throws Exception {
        await(worker.submit(() -> null));
    }

    void awaitFx() throws Exception {
        FxTestSupport.drainFx();
        assertNoAsynchronousFailures();
    }

    void assertNoAsynchronousFailures() throws Exception {
        Throwable first = asynchronousFailures.poll();
        if (first == null) {
            return;
        }
        Throwable next;
        while ((next = asynchronousFailures.poll()) != null) {
            first.addSuppressed(next);
        }
        rethrow(first);
    }

    @Override
    public void close() throws Exception {
        Throwable failure = null;
        while (!shutdownSignals.isEmpty()) {
            try {
                shutdownSignals.pop().run();
            } catch (Throwable cleanupFailure) {
                failure = combine(failure, cleanupFailure);
            }
        }
        for (Thread thread : threads) {
            try {
                thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                if (thread.isAlive()) {
                    failure = combine(failure, new AssertionError("Timed out joining test thread " + thread.getName()));
                    thread.interrupt();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                failure = combine(failure, interrupted);
            }
        }
        try {
            FxTestSupport.drainFx();
        } catch (Throwable fxFailure) {
            failure = combine(failure, fxFailure);
        }
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
            } catch (Throwable cleanupFailure) {
                failure = combine(failure, cleanupFailure);
            }
        }
        try {
            FxTestSupport.drainFx();
        } catch (Throwable fxFailure) {
            failure = combine(failure, fxFailure);
        }
        try {
            FxTestSupport.runOnFx(
                    () -> fxExceptionState.thread().setUncaughtExceptionHandler(fxExceptionState.previous()));
        } catch (Throwable restoreFailure) {
            failure = combine(failure, restoreFailure);
        }
        Throwable asynchronousFailure;
        while ((asynchronousFailure = asynchronousFailures.poll()) != null) {
            failure = combine(failure, asynchronousFailure);
        }
        if (failure != null) {
            rethrow(failure);
        }
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof Exception exception) {
            throw exception;
        }
        throw new RuntimeException(failure);
    }
}
