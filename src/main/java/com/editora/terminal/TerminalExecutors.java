package com.editora.terminal;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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

    /**
     * Immediate teardown, for closing a session without waiting on queued work.
     *
     * <p>And without waiting for the threads either. This runs on the FX thread when a tab closes, and it used to
     * await termination for up to half a second per tab — a stall that closing a window with several terminals
     * turned into a visible freeze. Nothing needs the threads gone: they are daemons, interrupted here, and whatever
     * they were doing was for a session that no longer exists.
     */
    public void shutdownNow() {
        scheduled.shutdownNow();
        unbounded.shutdownNow();
    }
}
