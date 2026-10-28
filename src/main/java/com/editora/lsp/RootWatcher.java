package com.editora.lsp;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
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
 * node_modules}, {@code __pycache__}, any virtual environment, and those the project's {@code .gitignore} files
 * leave out ({@code target/}, {@code dist/}, …; Bash review B16) — and at most {@link #MAX_DIRS} folders are
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
    private final java.util.function.Predicate<Path> reported;
    private final boolean skipOutputs;
    /** The root's {@code .gitignore} rules: a folder they leave out is not watched. */
    private final com.editora.search.GitignoreFilter ignore;

    private volatile boolean closed;

    private RootWatcher(
            Path root,
            WatchService ws,
            Consumer<List<LspManager.WatchedFile>> sink,
            java.util.function.Predicate<Path> reported,
            boolean skipOutputs) {
        this.root = root;
        this.ws = ws;
        this.sink = sink;
        this.reported = reported;
        this.skipOutputs = skipOutputs;
        this.ignore = com.editora.search.GitignoreFilter.load(root);
    }

    /** Starts watching {@code root}; {@code sink} gets each coalesced batch on the watcher's thread. Null on failure. */
    static @Nullable RootWatcher start(Path root, Consumer<List<LspManager.WatchedFile>> sink) {
        return start(root, sink, path -> true, false);
    }

    /**
     * As above, reporting only the paths {@code reported} accepts (a grammar watcher's {@code *.xsd}, …), and not
     * watching a build's output folders, which hold no build files and used up the folder cap (thirteenth review CK3).
     */
    static @Nullable RootWatcher start(
            Path root, Consumer<List<LspManager.WatchedFile>> sink, java.util.function.Predicate<Path> reported) {
        return start(root, sink, reported, true);
    }

    private static @Nullable RootWatcher start(
            Path root,
            Consumer<List<LspManager.WatchedFile>> sink,
            java.util.function.Predicate<Path> reported,
            boolean skipOutputs) {
        try {
            if (!Files.isDirectory(root) || root.getFileSystem() != FileSystems.getDefault()) {
                return null;
            }
            RootWatcher w = new RootWatcher(root, com.editora.io.WatchServices.open(), sink, reported, skipOutputs);
            try {
                w.registerTree(root);
            } catch (RuntimeException e) {
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
     * Whether {@code dir} itself is watched: a tree past {@link #MAX_DIRS} folders is watched only in part, so a file
     * under the root is not reported for that alone (thirteenth review CK3).
     */
    boolean watches(Path dir) {
        synchronized (dirs) {
            return dirs.containsValue(dir);
        }
    }

    /** Whether {@code dir} is a build's output folder: {@code target}, {@code build}, {@code out} or {@code bin} beside a build file. */
    static boolean buildOutput(Path dir) {
        Path name = dir.getFileName();
        Path parent = dir.getParent();
        return name != null
                && parent != null
                && java.util.Set.of("target", "build", "out", "bin").contains(name.toString())
                && java.util.stream.Stream.of("pom.xml", "build.gradle", "build.gradle.kts")
                        .anyMatch(f -> Files.isRegularFile(parent.resolve(f)));
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

    /** The root-relative, {@code /}-separated path of {@code dir} ("" for the root). */
    private String relative(Path dir) {
        return root.relativize(dir).toString().replace('\\', '/');
    }

    /**
     * Registers {@code start}'s folders level by level: build files and sources sit near the top, so past
     * {@link #MAX_DIRS} folders a module's pom.xml is watched before a deep asset tree is (fourteenth check CJ6).
     * Skips hidden, dependency and (for a build-file watcher) build-output folders, and every folder a
     * {@code .gitignore} on the way down leaves out (Bash review B16).
     */
    private void registerTree(Path start) {
        // each folder's rules: its parent's, plus a .gitignore of its own (a monorepo keeps them per package)
        Map<Path, com.editora.search.GitignoreFilter> rules = new HashMap<>();
        java.util.ArrayDeque<Path> queue = new java.util.ArrayDeque<>(List.of(start));
        while (!queue.isEmpty()) {
            Path dir = queue.poll();
            if (!dir.equals(root)) {
                if (skipped(dir) || (skipOutputs && buildOutput(dir))) {
                    continue;
                }
                String rel = relative(dir);
                var parent = rules.getOrDefault(dir.getParent(), ignore);
                if (parent.ignored(rel, true)) {
                    continue;
                }
                rules.put(dir, parent.nested(dir, rel));
            }
            synchronized (dirs) {
                if (dirs.size() >= (com.editora.io.WatchServices.isPolling(ws) ? MAX_POLLED_DIRS : MAX_DIRS)) {
                    LOG.fine(() -> "watching only part of " + root + ": " + dirs.size() + " folders");
                    return;
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
                    continue;
                }
            }
            try (var children = Files.list(dir)) {
                children.filter(c -> Files.isDirectory(c, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        .sorted()
                        .forEach(queue::add);
            } catch (IOException | RuntimeException e) {
                // unreadable: its folders are not watched
            }
        }
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
                    batch.forEach((p, k) -> {
                        if (reported.test(p)) {
                            out.add(new LspManager.WatchedFile(p, k));
                        }
                    });
                    if (!out.isEmpty()) {
                        sink.accept(out);
                    }
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
            if (kind == LspManager.WatchedKind.CREATED
                    && Files.isDirectory(path)
                    && !skipped(path)
                    && !(skipOutputs && buildOutput(path))) {
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
