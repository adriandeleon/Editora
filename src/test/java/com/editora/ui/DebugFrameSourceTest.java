package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.dap.DapModels.StackFrameInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D2-2: which frame a new stop shows when the top frame is in code that has no source to open. */
class DebugFrameSourceTest {

    private static final Path MAIN = Path.of("/proj/Main.java");

    private static StackFrameInfo frame(int id, Path file) {
        return new StackFrameInfo(id, "f" + id, file, 3, 0);
    }

    @Test
    @DisabledOnOs(
            value = OS.WINDOWS,
            disabledReason = "a jdt: URI is not a legal Windows path; DapClient.sourcePath yields no file for it there")
    void theTopmostFrameWithSourceIsShown() {
        List<StackFrameInfo> frames = List.of(
                frame(1, Path.of("jdt:/contents/java.base/java.lang/Thread.java")), frame(2, null), frame(3, MAIN));
        assertEquals(2, DebugCoordinator.firstFrameWithSource(frames, f -> MAIN.equals(f.file())));
    }

    @Test
    void theTopFrameStaysWhenItHasSourceOrNoFrameDoes() {
        List<StackFrameInfo> frames = List.of(frame(1, MAIN), frame(2, MAIN));
        assertEquals(0, DebugCoordinator.firstFrameWithSource(frames, f -> true));
        assertEquals(0, DebugCoordinator.firstFrameWithSource(frames, f -> false));
        assertEquals(0, DebugCoordinator.firstFrameWithSource(List.of(), f -> true));
    }

    private static final String STRING_CLASS =
            "jdt://contents/java.base/java.lang/String.class?=proj/%3Cjava.lang%28String.class";

    @Test
    void aStepIntoLibraryCodeShowsThatCodeOtherStopsTheNearestUserFrame() {
        // B5: Step Into a JDK method showed the caller's line again, so the step seemed to do nothing
        List<StackFrameInfo> frames =
                List.of(new StackFrameInfo(1, "String.length()", null, 1801, 0, STRING_CLASS), frame(2, MAIN));
        assertEquals(0, DebugCoordinator.frameToShow("step", frames, f -> MAIN.equals(f.file())));
        assertEquals(1, DebugCoordinator.frameToShow("pause", frames, f -> MAIN.equals(f.file())));
        assertEquals(1, DebugCoordinator.frameToShow("exception", frames, f -> MAIN.equals(f.file())));
        List<StackFrameInfo> noText = List.of(frame(1, null), frame(2, MAIN));
        assertEquals(1, DebugCoordinator.frameToShow("step", noText, f -> MAIN.equals(f.file())), "nothing to show");
    }
}
