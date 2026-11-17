package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a stack frame's file is on the disk, looked at off the JavaFX thread (PERF-01, Pauta 6.0.1). A stop's
 * frames are looked at on the adapter's reader thread as they arrive, before the FX thread picks the frame to
 * show; the FX thread answers from what was looked at. A file nothing looked at answers {@code true} there (it
 * may be shown: opening a missing file says so), never hiding a frame, and is looked at behind the answer.
 */
public final class FrameFiles {

    private static final int MAX = 4096;

    private static final Map<Path, Boolean> KNOWN = new ConcurrentHashMap<>();

    private FrameFiles() {}

    /** Looks at every frame's file now, on this thread (the adapter's reader): never the FX thread. */
    public static void warm(@org.jspecify.annotations.Nullable List<DapModels.StackFrameInfo> frames) {
        if (frames == null) {
            return;
        }
        for (DapModels.StackFrameInfo f : frames) {
            if (f != null && f.file() != null) {
                lookNow(f.file());
            }
        }
    }

    /** Whether {@code file} is a regular file: from what was looked at on the FX thread, else looked at now. */
    public static boolean isFile(Path file) {
        Boolean known = KNOWN.get(file);
        if (known != null) {
            return known;
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
        KNOWN.put(file, is);
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
