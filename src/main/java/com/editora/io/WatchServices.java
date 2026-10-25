package com.editora.io;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * Opens the folder watchers Editora uses (the project tree, a language server's workspace), falling back to a
 * {@link PollingWatchService} when the operating system refuses one.
 *
 * <p>On Linux each JDK watcher is an inotify instance, and a user may have only {@code
 * fs.inotify.max_user_instances} of them (128 by default) across every program they run — editors, IDEs, file
 * managers, sync clients, test runs. Past that, {@code newWatchService()} throws "User limit of inotify
 * instances reached or too many open files". The project tree then silently stopped following the disk; now
 * it polls instead, the first fallback is logged, and the Doctor's File watching row says why and what to do.
 */
public final class WatchServices {

    private static final Logger LOG = Logger.getLogger(WatchServices.class.getName());

    /** How a native watcher is opened; replaceable so a test can make it fail. */
    @FunctionalInterface
    public interface Opener {
        WatchService open() throws IOException;
    }

    private static volatile Opener opener = () -> FileSystems.getDefault().newWatchService();

    /** Why the last native watcher could not be opened, or null while every one could. */
    private static volatile @Nullable String fallbackReason;

    private WatchServices() {}

    /** A watcher for this machine's file system: the operating system's, or a polling one when it refuses. */
    public static WatchService open() {
        try {
            WatchService native_ = opener.open();
            fallbackReason = null;
            return native_;
        } catch (IOException | RuntimeException e) {
            String reason = reason(e);
            if (fallbackReason == null) {
                LOG.log(
                        Level.WARNING,
                        "No file watcher from the system (" + reason + "); checking folders every "
                                + PollingWatchService.INTERVAL_MS / 1000 + " s instead");
            }
            fallbackReason = reason;
            return new PollingWatchService();
        }
    }

    /** Registers {@code dir} with {@code ws}, whichever kind it is ({@code Path.register} takes only native ones). */
    public static WatchKey register(Path dir, WatchService ws, WatchEvent.Kind<?>... kinds) throws IOException {
        return ws instanceof PollingWatchService polling ? polling.register(dir, kinds) : dir.register(ws, kinds);
    }

    /** Whether {@code ws} is the polling fallback. */
    public static boolean isPolling(WatchService ws) {
        return ws instanceof PollingWatchService;
    }

    /** Why the most recent native watcher could not be opened, or null. */
    public static @Nullable String fallbackReason() {
        return fallbackReason;
    }

    /**
     * Tries to open (and at once closes) a native watcher: null when the system gives one, else the reason. For
     * the Doctor; it costs one watcher for an instant.
     */
    public static @Nullable String probe() {
        try (WatchService ws = opener.open()) {
            return null;
        } catch (IOException | RuntimeException e) {
            return reason(e);
        }
    }

    private static String reason(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }

    /** Replaces how native watchers are opened (tests); null restores the default. Returns the previous one. */
    public static Opener setOpenerForTest(Opener replacement) {
        Opener previous = opener;
        opener = replacement == null ? () -> FileSystems.getDefault().newWatchService() : replacement;
        fallbackReason = null;
        return previous;
    }
}
