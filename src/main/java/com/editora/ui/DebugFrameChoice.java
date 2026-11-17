package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import com.editora.dap.DapModels;

/** Pure: which frame a debugger stop selects (moved out of {@link DebugCoordinator}, which is at its size limit). */
final class DebugFrameChoice {

    private DebugFrameChoice() {}

    /** How many frames from the top a stop looks for one with source before settling for the top frame. */
    static final int MAX_SOURCE_SEARCH = 64;

    /**
     * Pure: the frame a stop selects. After a step into a JDK or library class whose text jdtls can supply (a
     * {@code jdt://} source), that frame: the user asked to go there, and showing the caller instead made Step
     * Into look as if it did nothing (B5). Any other stop: {@link #firstFrameWithSource}.
     */
    static int frameToShow(
            String reason, List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        if ("step".equals(reason)
                && !frames.isEmpty()
                && (frames.get(0).sourceUri() != null || frames.get(0).reference() != null)) {
            return 0; // a step into code the debugger has only in memory shows it too
        }
        return firstFrameWithSource(frames, has);
    }

    /**
     * Pure: the frame a core dump's stop selects — the topmost one whose source can be shown and is under one of
     * {@code own} (the project, the program's folder); the topmost with source when none is.
     */
    static int ownFrame(
            List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has, List<Path> own) {
        for (int i = 0; i < frames.size() && i < MAX_SOURCE_SEARCH; i++) {
            DapModels.StackFrameInfo f = frames.get(i);
            if (f.file() != null
                    && has.test(f)
                    && own.stream().anyMatch(f.file().toAbsolutePath()::startsWith)) {
                return i;
            }
        }
        return firstFrameWithSource(frames, has);
    }

    /**
     * Pure: the frame a new stop selects — the topmost one whose source can be shown, so pausing a thread that
     * sits in {@code Thread.sleep} lands on the caller's line rather than nowhere; the top frame when none can.
     */
    static int firstFrameWithSource(List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        for (int i = 0; i < frames.size() && i < MAX_SOURCE_SEARCH; i++) {
            if (has.test(frames.get(i))) {
                return i;
            }
        }
        return 0;
    }
}
