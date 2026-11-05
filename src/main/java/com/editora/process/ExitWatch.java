package com.editora.process;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link Process#onExit()} for a {@code Process} whose only way to learn of its end is a blocking wait, such as a
 * pty4j process or a wrapper around one.
 *
 * <p>The JDK's own {@code Process.onExit()} runs {@code waitFor()} as a task on {@code ForkJoinPool.commonPool()},
 * and that task keeps a pool worker until the process ends. Editora asks every process it tracks for its exit
 * ({@link ProcessRegistry#track}), so an open terminal held a common-pool worker for as long as it was open, and a
 * process that never finished exiting held one for good. The pool has one worker fewer than the machine has
 * cores, two on a three-core macOS runner. Once they were all waiting, nothing else in the JVM that runs on the
 * common pool ran again: every {@code CompletableFuture.supplyAsync}, {@code runAsync} and
 * {@code delayedExecutor} timer. Here one daemon thread of its own waits, started by the first call, and every
 * call shares what it learns.
 */
public final class ExitWatch {

    /** Blocks until the process has ended. */
    @FunctionalInterface
    public interface Waiter {
        void await() throws InterruptedException;
    }

    private final String threadName;
    private final Waiter waiter;
    private final CompletableFuture<Void> exited = new CompletableFuture<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public ExitWatch(String threadName, Waiter waiter) {
        this.threadName = threadName;
        this.waiter = waiter;
    }

    /**
     * What {@code process.onExit()} returns: a new future on each call, completed with {@code process} once it has
     * ended. Dependent actions run on the watching thread.
     */
    public CompletableFuture<Process> onExit(Process process) {
        if (started.compareAndSet(false, true)) {
            Thread.ofPlatform().daemon().name(threadName).start(this::watch);
        }
        return exited.thenApply(ignored -> process);
    }

    private void watch() {
        try {
            waiter.await();
            exited.complete(null);
        } catch (InterruptedException | RuntimeException e) {
            exited.completeExceptionally(e);
        }
    }
}
