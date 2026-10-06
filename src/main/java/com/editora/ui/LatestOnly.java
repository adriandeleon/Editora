package com.editora.ui;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs work off the calling thread and hands back the result of the <em>latest</em> request only — the
 * generation guard of {@code docs/performance.md}, as an object.
 *
 * <p>Every {@link #submit} supersedes the one before it: the earlier work is interrupted (a search that
 * checks its interrupt flag stops early instead of finishing a scan nobody wants) and, whether or not it
 * stops, its result is dropped rather than delivered. {@link #cancel} supersedes without a successor.
 *
 * <p>Confined to one thread — the one {@code deliver} runs on — except for the work itself: {@code submit},
 * {@code cancel} and {@code pending} are called there, and results arrive there.
 */
final class LatestOnly {

    private final Executor worker;
    private final Executor deliver;
    private long generation;
    private FutureTask<Void> running;

    /**
     * @param worker runs the work
     * @param deliver runs the result callback (the FX thread in the application)
     */
    LatestOnly(Executor worker, Executor deliver) {
        this.worker = worker;
        this.deliver = deliver;
    }

    /** Starts {@code work} on the worker; {@code onResult} gets its result unless a later call supersedes it. */
    <T> void submit(Supplier<T> work, Consumer<T> onResult) {
        cancel();
        long mine = generation;
        FutureTask<Void> task = new FutureTask<>(
                () -> {
                    T result;
                    try {
                        result = work.get();
                    } catch (RuntimeException | Error failure) {
                        deliver.execute(() -> {
                            finished(mine);
                            throw failure; // surface it where an uncaught exception is reported, not in the task
                        });
                        return;
                    }
                    deliver.execute(() -> {
                        if (finished(mine)) {
                            onResult.accept(result);
                        }
                    });
                },
                null);
        running = task;
        worker.execute(task);
    }

    /** Drops the request in flight, if any: its work is interrupted and its result never delivered. */
    void cancel() {
        generation++;
        if (running != null) {
            running.cancel(true);
            running = null;
        }
    }

    /** Whether a request is still in flight. */
    boolean pending() {
        return running != null;
    }

    private boolean finished(long mine) {
        if (mine != generation) {
            return false;
        }
        running = null;
        return true;
    }
}
