package com.editora.perf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Startup work that can wait for the first painted frame.
 *
 * <p>A launch starts several probes that nothing on screen needs before the user sees their file: a login shell
 * to read the shell's PATH, {@code rg --version}, {@code gh --version}, the release check over HTTPS and the
 * Hunspell dictionary build. Each is off the FX thread, but together they compete with the file load for cores
 * and disk during the one stretch where that load is all the user is waiting for. So a launch {@linkplain
 * #hold() holds} this gate before its first window is built; work handed to {@link #run} meanwhile is queued,
 * and {@link #release()} — called once the first content has painted — runs the queue in order.
 *
 * <p>Outside a launch (a headless test building a window, a second window opened later, anything after the
 * release) {@link #run} runs its task at once, so no caller needs to know whether the gate exists. Tasks are
 * expected to be cheap starters (submit to an executor, start a thread): they run on whichever thread releases
 * the gate, normally the FX thread.
 */
public final class AfterFirstPaint {

    private static final Logger LOG = Logger.getLogger(AfterFirstPaint.class.getName());

    private static final Object LOCK = new Object();

    /** Guarded by {@link #LOCK}; {@code null} while not held. */
    private static List<Runnable> queue;

    /** The keys of {@link #runOnce} tasks queued during this hold. Guarded by {@link #LOCK}. */
    private static final Set<Object> QUEUED_KEYS = new HashSet<>();

    private AfterFirstPaint() {}

    /** Starts holding: from now until {@link #release()}, {@link #run} queues instead of running. Idempotent. */
    public static void hold() {
        synchronized (LOCK) {
            if (queue == null) {
                queue = new ArrayList<>();
            }
        }
    }

    /** Whether tasks are being held back right now. */
    public static boolean held() {
        synchronized (LOCK) {
            return queue != null;
        }
    }

    /** Runs {@code task} now, or after the first painted frame when the gate is held. */
    public static void run(Runnable task) {
        synchronized (LOCK) {
            if (queue != null) {
                queue.add(task);
                return;
            }
        }
        task.run();
    }

    /**
     * As {@link #run}, but while held at most one task per {@code key} is queued: the repeated requests a
     * session restore makes for one refresh collapse into the first. Runs {@code task} at once when not held.
     */
    public static void runOnce(Object key, Runnable task) {
        synchronized (LOCK) {
            if (queue != null) {
                if (QUEUED_KEYS.add(key)) {
                    queue.add(task);
                }
                return;
            }
        }
        task.run();
    }

    /**
     * Ends the hold and runs every queued task, in the order it was queued, on the calling thread. A task that
     * throws is logged and does not stop the rest. Idempotent: a second release does nothing.
     */
    public static void release() {
        List<Runnable> held;
        synchronized (LOCK) {
            held = queue;
            queue = null;
            QUEUED_KEYS.clear();
        }
        if (held == null) {
            return;
        }
        for (Runnable task : held) {
            try {
                task.run();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Deferred startup task failed", e);
            }
        }
    }
}
