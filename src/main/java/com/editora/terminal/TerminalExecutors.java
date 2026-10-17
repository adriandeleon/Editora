package com.editora.terminal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.jediterm.terminal.TerminalExecutorServiceManager;

/**
 * The thread pools JediTerm schedules its internal work on (resize debouncing, blinking, deferred
 * redraw requests).
 *
 * <p>Every thread is a daemon: these outlive an individual session, and a non-daemon pool would
 * keep the JVM alive after the last window closed.
 */
public final class TerminalExecutors implements TerminalExecutorServiceManager {

    private static final AtomicInteger POOL_ID = new AtomicInteger();

    private final ScheduledExecutorService scheduled;
    private final ExecutorService unbounded;

    public TerminalExecutors() {
        int id = POOL_ID.incrementAndGet();
        scheduled = Executors.newSingleThreadScheduledExecutor(daemon("editora-terminal-sched-" + id));
        unbounded = Executors.newCachedThreadPool(daemon("editora-terminal-worker-" + id));
    }

    private static ThreadFactory daemon(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    @Override
    public ScheduledExecutorService getSingleThreadScheduledExecutor() {
        return scheduled;
    }

    @Override
    public ExecutorService getUnboundedExecutorService() {
        return unbounded;
    }

    @Override
    public void shutdownWhenAllExecuted() {
        scheduled.shutdown();
        unbounded.shutdown();
    }

    /** Immediate teardown, for closing a session without waiting on queued work. */
    public void shutdownNow() {
        scheduled.shutdownNow();
        unbounded.shutdownNow();
        try {
            scheduled.awaitTermination(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
