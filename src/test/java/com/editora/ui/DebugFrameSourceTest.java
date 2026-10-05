package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.dap.DapModels.StackFrameInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D2-2: which frame a new stop shows when the top frame is in code that has no source to open. */
class DebugFrameSourceTest {

    private static final Path MAIN = Path.of("/proj/Main.java");

    private static StackFrameInfo frame(int id, Path file) {
        return new StackFrameInfo(id, "f" + id, file, 3, 0);
    }

    @Test
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
}
