package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import com.editora.config.Breakpoint;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #473: a breakpoint in a <em>closed</em> file must still be sent to the debugger. Before the
 * fix {@code collectBreakpoints} read open tabs only, so a breakpoint set then had its tab closed was never
 * armed. {@link DebugCoordinator#closedFileBreakpoints} is the pure core of the fix — it re-anchors the
 * persisted breakpoints of files with no open tab against the file's current text.
 */
class DebugClosedBreakpointsTest {

    private static final Predicate<Path> ALL_LOCAL = p -> true;

    private static List<DapModels.FileBreakpoints> collect(
            Map<String, List<Breakpoint>> map, Set<String> open, Function<Path, List<String>> reader) {
        return DebugCoordinator.closedFileBreakpoints(map, open, ALL_LOCAL, reader);
    }

    @Test
    void closedFileBreakpointsAreCollectedAndReanchored() {
        // The breakpoint was stored on line 2 ("target();"), but the file grew a blank line above it, so
        // "target();" now sits on line 3. Re-anchoring must move the breakpoint from 2 to 3.
        String path = "/proj/App.java";
        Map<String, List<Breakpoint>> map = Map.of(path, List.of(Breakpoint.plain(2, "target();")));
        List<String> current = List.of("class App {", "", "", "  target();", "}");

        List<DapModels.FileBreakpoints> out = collect(map, Set.of(), p -> current);
        assertEquals(1, out.size());
        assertEquals(Path.of(path), out.get(0).file());
        assertEquals(1, out.get(0).breakpoints().size());
        assertEquals(3, out.get(0).breakpoints().get(0).line(), "re-anchored to the moved content line");
    }

    @Test
    void openFilesAreExcluded() {
        String path = "/proj/App.java";
        Map<String, List<Breakpoint>> map = Map.of(path, List.of(Breakpoint.plain(2, "x")));
        // The file has an open tab → its live buffer supplies the breakpoints; the closed set skips it.
        assertTrue(collect(map, Set.of(path), p -> List.of("a", "b", "c")).isEmpty());
    }

    @Test
    void remotePathsAreSkipped() {
        Map<String, List<Breakpoint>> map = Map.of("/proj/App.java", List.of(Breakpoint.plain(1, "y")));
        List<DapModels.FileBreakpoints> out =
                DebugCoordinator.closedFileBreakpoints(map, Set.of(), p -> false, p -> List.of("a", "b"));
        assertTrue(out.isEmpty(), "remote/!isLocal files aren't sent");
    }

    @Test
    void anUnreadableFileKeepsItsStoredLineIndex() {
        String path = "/proj/App.java";
        Map<String, List<Breakpoint>> map = Map.of(path, List.of(Breakpoint.plain(7, "target();")));
        // reader returns null (unreadable / too large / non-UTF-8) → fall back to the stored line, not drop it.
        List<DapModels.FileBreakpoints> out = collect(map, Set.of(), p -> null);
        assertEquals(1, out.size());
        assertEquals(7, out.get(0).breakpoints().get(0).line());
    }

    @Test
    void disabledBreakpointsAreNotEmitted() {
        String path = "/proj/App.java";
        Breakpoint disabled = new Breakpoint(3, "", "", false, "z");
        Map<String, List<Breakpoint>> map = Map.of(path, List.of(disabled));
        assertTrue(collect(map, Set.of(), p -> List.of("a", "b", "c", "z")).isEmpty());
    }
}
