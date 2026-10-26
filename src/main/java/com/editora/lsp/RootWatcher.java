package com.editora.lsp;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Watches a language server's workspace folder when nothing else does — a window with no project, or a server
 * rooted outside the project — and reports what changed on disk as {@code workspace/didChangeWatchedFiles}
 * events (Python review B7). Without it a module created, deleted or edited by git, a terminal or another
 * window stayed unknown to the server until a restart: the Project panel's watcher was the only source.
 *
 * <p>Folders a server never analyses are skipped — hidden ones ({@code .git}, {@code .venv}), {@code
 * node_modules}, {@code __pycache__}, and any virtual environment — and at most {@link #MAX_DIRS} folders are
 * watched, so a server rooted at a huge tree costs a bounded number of watches. Events are coalesced for
 * {@link #COALESCE_MS} and merged per path ({@link LspManager#mergeWatched}). One virtual thread per watcher.
 */
final class RootWatcher implements AutoCloseable {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(RootWatcher.class.getName());

    /** The most folders one watcher registers. */
    static final int MAX_DIRS = 2000;

    /** The most folders a polling fallback watches: each is listed every couple of seconds. */
    static final int MAX_POLLED_DIRS = 300;

    /** How long a burst of events is gathered before it is reported. */
    static final long COALESCE_MS = 300;

    private final Path root;
    private final WatchService ws;
    private final Map<WatchKey, Path> dirs = new HashMap<>();
    private final Consumer<List<LspManager.WatchedFile>> sink;
    private volatile boolean closed;

    private RootWatcher(Path root, WatchService ws, Consumer<List<LspManager.WatchedFile>> sink) {
        this.root = root;
        this.ws = ws;
        this.sink = sink;
    }

    /** Starts watching {@code root}; {@code sink} gets each coalesced batch on the watcher's thread. Null on failure. */
    static @Nullable RootWatcher start(Path root, Consumer<List<LspManager.WatchedFile>> sink) {
        try {
            if (!Files.isDirectory(root) || root.getFileSystem() != FileSystems.getDefault()) {
                return null;
            }
            RootWatcher w = new RootWatcher(root, com.editora.io.WatchServices.open(), sink);
            try {
                w.registerTree(root);
            } catch (IOException | RuntimeException e) {
                w.ws.close(); // a watcher that is not returned is never closed: it held an inotify instance
                throw e;
            }
            Thread.ofVirtual().name("lsp-root-watcher").start(w::loop);
            return w;
        } catch (IOException | RuntimeException e) {
            LOG.log(java.util.logging.Level.FINE, "cannot watch " + root, e);
            return null;
        }
    }

    Path root() {
        return root;
    }

    /** How many folders are watched (tests). */
    int watchedDirs() {
        synchronized (dirs) {
            return dirs.size();
        }
    }

    /**
     * Whether a folder is one a language server never analyses: hidden, packages, caches, environments. Pure.
     * Eclipse's {@code .settings} is watched: a formatter profile kept there and edited outside Editora was never
     * re-read (fourth review DN4).
     */
    static boolean skipped(Path dir) {
        Path name = dir.getFileName();
        String n = name == null ? "" : name.toString();
        return (n.startsWith(".") && !n.equals(".settings"))
                || n.equals("node_modules")
                || n.equals("__pycache__")
                || Files.isRegularFile(dir.resolve("pyvenv.cfg"));
    }

    private void registerTree(Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!dir.equals(root) && skipped(dir)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                synchronized (dirs) {
                    if (dirs.size() >= (com.editora.io.WatchServices.isPolling(ws) ? MAX_POLLED_DIRS : MAX_DIRS)) {
                        return FileVisitResult.TERMINATE;
                    }
                    try {
                        dirs.put(
                                com.editora.io.WatchServices.register(
                                        dir,
                                        ws,
                                        StandardWatchEventKinds.ENTRY_CREATE,
                                        StandardWatchEventKinds.ENTRY_DELETE,
                                        StandardWatchEventKinds.ENTRY_MODIFY),
                                dir);
                    } catch (IOException | RuntimeException e) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void loop() {
        try {
            while (!closed) {
                WatchKey first = ws.take();
                Map<Path, LspManager.WatchedKind> batch = new LinkedHashMap<>();
                drain(first, batch);
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COALESCE_MS);
                long left;
                while ((left = until - System.nanoTime()) > 0) {
                    WatchKey more = ws.poll(left, TimeUnit.NANOSECONDS);
                    if (more == null) {
                        break;
                    }
                    drain(more, batch);
                }
                if (!batch.isEmpty() && !closed) {
                    List<LspManager.WatchedFile> out = new ArrayList<>();
                    batch.forEach((p, k) -> out.add(new LspManager.WatchedFile(p, k)));
                    sink.accept(out);
                }
            }
        } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
            // closed
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.FINE, "root watcher stopped for " + root, e);
        }
    }

    private void drain(WatchKey key, Map<Path, LspManager.WatchedKind> batch) {
        Path dir;
        synchronized (dirs) {
            dir = dirs.get(key);
        }
        for (WatchEvent<?> ev : key.pollEvents()) {
            if (dir == null || !(ev.context() instanceof Path child)) {
                continue; // OVERFLOW: nothing to name
            }
            Path path = dir.resolve(child);
            LspManager.WatchedKind kind = ev.kind() == StandardWatchEventKinds.ENTRY_CREATE
                    ? LspManager.WatchedKind.CREATED
                    : ev.kind() == StandardWatchEventKinds.ENTRY_DELETE
                            ? LspManager.WatchedKind.DELETED
                            : LspManager.WatchedKind.CHANGED;
            if (kind == LspManager.WatchedKind.CREATED && Files.isDirectory(path) && !skipped(path)) {
                try {
                    registerTree(path); // a new package folder: watch it (and say what is already in it)
                    try (var children = Files.list(path)) {
                        children.forEach(c -> batch.merge(c, LspManager.WatchedKind.CREATED, LspManager::mergeWatched));
                    }
                } catch (IOException | RuntimeException e) {
                    // vanished already
                }
            }
            LspManager.WatchedKind merged = LspManager.mergeWatched(batch.get(path), kind);
            if (merged == null) {
                batch.remove(path);
            } else {
                batch.put(path, merged);
            }
        }
        if (!key.reset()) {
            synchronized (dirs) {
                dirs.remove(key);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            ws.close();
        } catch (IOException e) {
            // closing anyway
        }
    }
}
