package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a stack frame's file is on the disk, looked at off the JavaFX thread (PERF-01, Pauta 6.2.0). A stop's
 * frames are looked at on the adapter's reader thread as they arrive, before the FX thread picks the frame to
 * show; the FX thread answers from what was looked at. A file nothing looked at answers {@code true} there (it
 * may be shown: opening a missing file says so), never hiding a frame, and is looked at behind the answer.
 *
 * <p>A stop looks at each of its files once (a recursive stack is one file a thousand times), and not at all when it
 * was looked at in the last {@link #FRESH_NANOS}; and not on the reader thread itself ({@link #warmAsync}): a
 * session on an SSH host stats over the network, and the reader handles no other message meanwhile (perf review
 * 2026-11-23, D2).
 */
public final class FrameFiles {

    private static final int MAX = 4096;

    /** How long a look stands for the stops after it: a step after a step looks again at nothing. */
    static final long FRESH_NANOS = 5_000_000_000L;

    /** Whether a file is a regular file, and when that was looked at ({@link System#nanoTime()}). */
    private record Look(boolean is, long at) {}

    private static final Map<Path, Look> KNOWN = new ConcurrentHashMap<>();

    private FrameFiles() {}

    /** Looks at the frames' files not looked at lately now, on this thread: never the FX thread. */
    public static void warm(@org.jspecify.annotations.Nullable List<DapModels.StackFrameInfo> frames) {
        for (Path file : toLook(frames)) {
            lookNow(file);
        }
    }

    /**
     * {@link #warm} on a thread of its own: done when every frame's file has been looked at — at once when none needs a
     * look. Never fails.
     */
    public static CompletableFuture<Void> warmAsync(
            @org.jspecify.annotations.Nullable List<DapModels.StackFrameInfo> frames) {
        Set<Path> files = toLook(frames);
        if (files.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> done = new CompletableFuture<>();
        Thread.ofVirtual().name("debug-frame-files").start(() -> {
            try {
                files.forEach(FrameFiles::lookNow);
            } finally {
                done.complete(null);
            }
        });
        return done;
    }

    /** The distinct files of {@code frames} not looked at in the last {@link #FRESH_NANOS}. */
    static Set<Path> toLook(@org.jspecify.annotations.Nullable List<DapModels.StackFrameInfo> frames) {
        if (frames == null || frames.isEmpty()) {
            return Set.of();
        }
        long now = System.nanoTime();
        Set<Path> files = new LinkedHashSet<>();
        for (DapModels.StackFrameInfo f : frames) {
            Path file = f == null ? null : f.file();
            if (file != null && !files.contains(file)) {
                Look known = KNOWN.get(file);
                if (known == null || now - known.at() > FRESH_NANOS) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    /** Whether {@code file} is a regular file: from what was looked at on the FX thread, else looked at now. */
    public static boolean isFile(Path file) {
        Look known = KNOWN.get(file);
        if (known != null) {
            return known.is();
        }
        if (onFx()) {
            Thread.ofVirtual().name("debug-frame-file").start(() -> lookNow(file));
            return true;
        }
        return lookNow(file);
    }

    private static boolean lookNow(Path file) {
        com.editora.perf.BlockingSites.enter("FrameFiles.look");
        boolean is;
        try {
            is = Files.isRegularFile(file);
        } catch (RuntimeException e) {
            is = false; // a remote file whose connection is gone
        }
        if (KNOWN.size() >= MAX) {
            KNOWN.clear();
        }
        KNOWN.put(file, new Look(is, System.nanoTime()));
        return is;
    }

    private static boolean onFx() {
        try {
            return javafx.application.Platform.isFxApplicationThread();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }
}
