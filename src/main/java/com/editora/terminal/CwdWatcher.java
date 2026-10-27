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
    private volatile @Nullable String lastProgram;

    private CwdWatcher(long pid, Consumer<String> onChange, @Nullable Consumer<String> onProgramChange) {
        task = Pool.INSTANCE.scheduleWithFixedDelay(
                () -> poll(pid, onChange, onProgramChange), 0, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Starts watching, reporting the directory immediately and then on every change.
     *
     * @return a handle to stop with; closing it is what keeps a closed tab from being polled
     *     forever, so it belongs on the same path that disposes the session
     */
    public static CwdWatcher watch(long pid, Consumer<String> onChange) {
        return new CwdWatcher(pid, onChange, null);
    }

    /**
     * As {@link #watch(long, Consumer)}, also reporting what the shell is running — its first child's name, or empty
     * at an idle prompt — immediately and then on every change. The directory half runs only where {@link
     * ProcessCwd#isSupported} says the OS can answer; the program half needs only {@link ProcessHandle}.
     */
    public static CwdWatcher watch(long pid, Consumer<String> onChange, Consumer<String> onProgramChange) {
        return new CwdWatcher(pid, onChange, onProgramChange);
    }

    private void poll(long pid, Consumer<String> onChange, @Nullable Consumer<String> onProgramChange) {
        try {
            if (ProcessCwd.isSupported()) pollCwd(pid, onChange);
            if (onProgramChange != null) pollProgram(pid, onProgramChange);
        } catch (Throwable t) {
            // A throw here would cancel the scheduled task silently and stop the watch for good.
        }
    }

    private void pollCwd(long pid, Consumer<String> onChange) {
        Optional<String> cwd = ProcessCwd.of(pid);
        // Empty means the process is gone, or this platform cannot say. Neither is a reason to
        // wipe a directory already on screen: a tab whose shell has exited should keep reading
        // as the place it was, not blank out.
        if (cwd.isEmpty()) return;
        String value = cwd.get();
        if (value.equals(last)) return;
        last = value;
        onChange.accept(value);
    }

    private void pollProgram(long pid, Consumer<String> onProgramChange) {
        String program = RunningProgram.first(pid).orElse("");
        if (program.equals(lastProgram)) return;
        lastProgram = program;
        onProgramChange.accept(program);
    }

    @Override
    public void close() {
        task.cancel(false);
    }
}
