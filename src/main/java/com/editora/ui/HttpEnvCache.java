package com.editora.ui;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

/**
 * The env files of each {@code .http} file as last read, so the FX thread never reads them or walks folders for them
 * ({@link HttpEnvironments} does the finding and reading). The FX thread only takes what was last read
 * ({@link #last}) — for the environment pill and menu, the "Running …" label, the {@code {{variable}}} marks — and asks for
 * a fresh read when the files may have changed ({@link #refresh}: the panel was attached, the tab was switched to,
 * the window regained focus), which runs on a background thread and answers on the FX thread. A run reads them
 * itself, on its worker ({@link #readNow}), so what is sent always uses the files as they are when it starts.
 *
 * <p>Keyed by the file and the window's project root, which together decide the folder. Thread-safe.
 */
final class HttpEnvCache {

    /** One file's env folder and its two env files, as read; {@link #NONE} before the first read. */
    record Snapshot(@Nullable Path dir, HttpEnvironments.EnvFiles files) {
        static final Snapshot NONE = new Snapshot(null, HttpEnvironments.read(null));
    }

    private record Key(@Nullable Path file, @Nullable Path project) {}

    /** Reads env files, and writes saved responses, off the FX thread, in order; idles out. */
    static final ExecutorService IO = io();

    private static ExecutorService io() {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, "http-files");
            t.setDaemon(true);
            return t;
        });
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    private final Map<Key, Snapshot> last = new ConcurrentHashMap<>();

    /** What was last read for {@code file} (null: untitled) in {@code project}; {@link Snapshot#NONE} if nothing yet. */
    Snapshot last(@Nullable Path file, @Nullable Path project) {
        return last.getOrDefault(new Key(file, project), Snapshot.NONE);
    }

    /**
     * Finds and reads the env files for {@code file} in {@code project} now, and remembers them. Reads files and walks
     * folders: never on the FX thread.
     */
    Snapshot readNow(@Nullable Path file, @Nullable Path project) {
        Path dir = HttpEnvironments.forFile(file, project);
        Snapshot read = new Snapshot(dir, HttpEnvironments.read(dir));
        last.put(new Key(file, project), read);
        return read;
    }

    /** Reads the env files for {@code file} in {@code project} in the background, then hands them to {@code onFx}. */
    void refresh(@Nullable Path file, @Nullable Path project, Consumer<Snapshot> onFx) {
        IO.execute(() -> {
            Snapshot read;
            try {
                read = readNow(file, project);
            } catch (RuntimeException unreadable) {
                read = Snapshot.NONE; // HttpEnvironments reports unreadable files itself; this is the unexpected
            }
            Snapshot answer = read;
            Platform.runLater(() -> onFx.accept(answer));
        });
    }

    /** Forgets what was read for {@code file} (a closed buffer); other buffers of the same file read it again. */
    void forget(@Nullable Path file, @Nullable Path project) {
        last.remove(new Key(file, project));
    }

    /** Waits until every read asked for so far has run and answered on the FX thread (tests). */
    static void awaitIdleForTest() throws Exception {
        IO.submit(() -> {}).get(30, TimeUnit.SECONDS);
        java.util.concurrent.CompletableFuture<Void> fx = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> fx.complete(null));
        fx.get(30, TimeUnit.SECONDS);
    }
}
