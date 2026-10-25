package com.editora.terminal;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Watches one process's working directory and reports it when it changes.
 *
 * <p>Polled rather than pushed, because there is nothing to subscribe to: {@code chdir} is a
 * syscall the shell makes on its own behalf and no signal comes out of it. The cost is one
 * {@code readlink} per session per tick — set against the repaint timer already running at frame
 * rate for every open tab, it does not register. All watchers share a single daemon thread, so tab
 * count buys threads at zero.
 *
 * <p>The callback fires on that thread, never on the JavaFX one. It fires on the first successful
 * read and thereafter only on a change, so a binding driven from it does not churn.
 */
public final class CwdWatcher implements AutoCloseable {

    /**
     * Fast enough that the tab has caught up before the eye moves to it, slow enough to be free.
     * The directory only changes when the user runs a command, which is a human-speed event.
     */
    private static final long INTERVAL_MS = 750;

    private static final class Pool {
        static final ScheduledExecutorService INSTANCE = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "editora-terminal-cwd");
            t.setDaemon(true);
            return t;
        });
    }

    private final ScheduledFuture<?> task;
    private volatile @Nullable String last;

    private CwdWatcher(long pid, Consumer<String> onChange) {
        task = Pool.INSTANCE.scheduleWithFixedDelay(() -> poll(pid, onChange), 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts watching, reporting the directory immediately and then on every change.
     *
     * @return a handle to stop with; closing it is what keeps a closed tab from being polled
     *     forever, so it belongs on the same path that disposes the session
     */
    public static CwdWatcher watch(long pid, Consumer<String> onChange) {
        return new CwdWatcher(pid, onChange);
    }

    private void poll(long pid, Consumer<String> onChange) {
        try {
            Optional<String> cwd = ProcessCwd.of(pid);
            // Empty means the process is gone, or this platform cannot say. Neither is a reason to
            // wipe a directory already on screen: a tab whose shell has exited should keep reading
            // as the place it was, not blank out.
            if (cwd.isEmpty()) return;
            String value = cwd.get();
            if (value.equals(last)) return;
            last = value;
            onChange.accept(value);
        } catch (Throwable t) {
            // A throw here would cancel the scheduled task silently and stop the watch for good.
        }
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
