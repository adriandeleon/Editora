package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The shared worker and change stamp behind every {@link BuildCoordinator}'s marker-file detection.
 *
 * <p>Detection re-runs on each tab switch, save, focus-regain and settings apply, for every enabled build
 * tool. It used to start a thread per tool per trigger and re-parse the build file every time, although
 * between two tabs of one project nothing it reads has changed. One daemon worker now serves every tool and
 * window, and a {@link Stamp} of what a parse reads lets an unchanged project skip the parse — and the
 * FX-thread tree rebuild that followed it — altogether.
 */
final class BuildDetection {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(BuildDetection.class.getName());
    private static final AtomicInteger THREADS_STARTED = new AtomicInteger();
    private static final AtomicLong PARSES = new AtomicLong();

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "build-detect");
        t.setDaemon(true);
        THREADS_STARTED.incrementAndGet();
        return t;
    });

    private BuildDetection() {}

    static void execute(Runnable detect) {
        EXECUTOR.execute(() -> {
            try {
                detect.run();
            } catch (RuntimeException e) {
                // One tool's failed detection must not take the shared worker (and every later detection)
                // down with it; what was applied before simply stays.
                LOG.log(java.util.logging.Level.WARNING, "build detection failed", e);
            }
        });
    }

    /** Counts a build-file parse that actually ran (a detection the stamp could not skip). */
    static void countParse() {
        PARSES.incrementAndGet();
    }

    static long parses() {
        return PARSES.get();
    }

    static int threadsStarted() {
        return THREADS_STARTED.get();
    }

    /**
     * What a detection result depends on: the marker directory, each marker file's size and modification
     * time, and the directory's own modification time (a lockfile or wrapper script appearing beside the
     * build file changes what the parse reports without touching the build file).
     */
    record Stamp(Path root, List<Long> markers, long directoryModified) {

        /** Stats {@code root}'s marker files; blocking, so off the FX thread. {@code root} may be null. */
        static Stamp of(Path root, List<String> markerNames) {
            if (root == null) {
                return new Stamp(null, List.of(), 0);
            }
            List<Long> markers = new ArrayList<>(markerNames.size() * 2);
            for (String name : markerNames) {
                try {
                    BasicFileAttributes a = Files.readAttributes(root.resolve(name), BasicFileAttributes.class);
                    markers.add(a.size());
                    markers.add(a.lastModifiedTime().toMillis());
                } catch (IOException | RuntimeException e) {
                    markers.add(-1L); // absent (or unreadable): its appearing later is a change
                    markers.add(-1L);
                }
            }
            long directory;
            try {
                directory = Files.getLastModifiedTime(root).toMillis();
            } catch (IOException | RuntimeException e) {
                directory = -1;
            }
            return new Stamp(root, List.copyOf(markers), directory);
        }
    }
}
