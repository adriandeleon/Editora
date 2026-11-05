package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * File changes for a server that runs elsewhere — on an SSH host, in WSL or a container — whose folder no watcher of
 * this machine can follow (Python review 2, B108): a module that {@code git pull} created on the host stayed
 * "could not be resolved" until a restart, since the server watches nothing itself either.
 *
 * <p>The folder is listed again, and what changed since the last listing reported, when the window comes back to
 * the front (the user was in a terminal) and every {@link #INTERVAL_MS} while it stays there; never while it is in
 * the background. Each listing is bounded ({@link #MAX_DIRS} folders, {@link #MAX_FILES} files) and skips what a
 * server never reads ({@link RootWatcher#skipped}). One virtual thread per folder.
 */
final class RemoteRootScanner implements AutoCloseable {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(RemoteRootScanner.class.getName());

    /** The most folders one listing reads (each is a round trip on a remote host). */
    static final int MAX_DIRS = 300;

    /** The most files one listing remembers. */
    static final int MAX_FILES = 5000;

    /** How often a folder is listed again while the window stays in front. */
    static final long INTERVAL_MS = 60_000;

    /** How often the window's focus is looked at. */
    static final long FOCUS_POLL_MS = 1_500;

    private final Path root;
    private final Consumer<List<LspManager.WatchedFile>> sink;
    private final BooleanSupplier focused;
    private volatile boolean closed;
    private @Nullable Map<Path, Long> last;

    RemoteRootScanner(Path root, Consumer<List<LspManager.WatchedFile>> sink, BooleanSupplier focused) {
        this.root = root;
        this.sink = sink;
        this.focused = focused;
    }

    /** Starts the scanner's thread; the first listing is the baseline and reports nothing. */
    RemoteRootScanner start() {
        Thread.ofVirtual().name("lsp-remote-scan").start(this::loop);
        return this;
    }

    Path root() {
        return root;
    }

    private void loop() {
        boolean wasFocused = true;
        long lastScan = 0;
        try {
            while (!closed) {
                boolean now = focused.getAsBoolean();
                long at = System.currentTimeMillis();
                if (last == null || (now && (!wasFocused || at - lastScan >= INTERVAL_MS))) {
                    scanAndReport();
                    lastScan = System.currentTimeMillis();
                }
                wasFocused = now;
                Thread.sleep(FOCUS_POLL_MS);
            }
        } catch (InterruptedException e) {
            // closed
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.FINE, "remote scan stopped for " + root, e);
        }
    }

    /** Lists the folder and reports what changed since the last listing (the first one reports nothing). */
    void scanAndReport() {
        Map<Path, Long> now = list(root);
        Map<Path, Long> before = last;
        last = now;
        if (before == null || closed) {
            return;
        }
        List<LspManager.WatchedFile> changes = diff(before, now);
        if (!changes.isEmpty()) {
            sink.accept(changes);
        }
    }

    /** The files under {@code root} with their modification times, bounded. Touches the (possibly remote) disk. */
    static Map<Path, Long> list(Path root) {
        Map<Path, Long> files = new HashMap<>();
        java.util.ArrayDeque<Path> queue = new java.util.ArrayDeque<>(List.of(root));
        int dirs = 0;
        while (!queue.isEmpty() && dirs < MAX_DIRS && files.size() < MAX_FILES) {
            Path dir = queue.poll();
            if (!dir.equals(root) && RootWatcher.skipped(dir)) {
                continue;
            }
            dirs++;
            try (var children = Files.list(dir)) {
                for (Path child : (Iterable<Path>) children::iterator) {
                    var attrs = Files.readAttributes(
                            child,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isDirectory()) {
                        queue.add(child);
                    } else if (files.size() < MAX_FILES) {
                        files.put(child, attrs.lastModifiedTime().toMillis());
                    }
                }
            } catch (java.io.IOException | RuntimeException e) {
                // unreadable or gone: what it held is not reported
            }
        }
        return files;
    }

    /** What changed from {@code before} to {@code now}: created, deleted and modified files. Pure. */
    static List<LspManager.WatchedFile> diff(Map<Path, Long> before, Map<Path, Long> now) {
        List<LspManager.WatchedFile> out = new ArrayList<>();
        now.forEach((file, time) -> {
            Long was = before.get(file);
            if (was == null) {
                out.add(new LspManager.WatchedFile(file, LspManager.WatchedKind.CREATED));
            } else if (!was.equals(time)) {
                out.add(new LspManager.WatchedFile(file, LspManager.WatchedKind.CHANGED));
            }
        });
        before.keySet().stream()
                .filter(file -> !now.containsKey(file))
                .forEach(file -> out.add(new LspManager.WatchedFile(file, LspManager.WatchedKind.DELETED)));
        return out;
    }

    @Override
    public void close() {
        closed = true;
    }
}
