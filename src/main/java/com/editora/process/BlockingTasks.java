package com.editora.process;

import java.util.concurrent.Executor;

/**
 * Where a one-off blocking task runs when it has no executor of its own: a read or write of a file, a wait on
 * a process, a lookup that may touch a slow mount.
 *
 * <p>{@code CompletableFuture.supplyAsync} and {@code runAsync} without an executor use the common
 * {@code ForkJoinPool}, which has about one thread per core and is shared with everything else that never
 * names one (parallel streams among them). A task that blocks there holds one of those few threads, and a
 * handful of them stall work that has nothing to do with them. Pass {@link #named} instead: each task then
 * gets a virtual thread of its own, named so it can be found in a thread dump.
 */
public final class BlockingTasks {

    private BlockingTasks() {}

    /** An executor that starts every task on a new virtual thread called {@code name}. */
    public static Executor named(String name) {
        return task -> Thread.ofVirtual().name(name).start(task);
    }
}
