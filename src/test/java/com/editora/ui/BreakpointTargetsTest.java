package com.editora.ui;

import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What one breakpoint asks of the adapter: its line, and each lambda it stops in at that lambda's column now. */
class BreakpointTargetsTest {

    private static final String LINE = "    a.forEach(x -> b.forEach(y -> use(x, y)));";
    private static final int X = LINE.indexOf("x ->") + 1; // DAP columns start at 1
    private static final int Y = LINE.indexOf("y ->") + 1;

    private static List<Integer> columns(List<DapModels.LineBreakpoint> sent) {
        return sent.stream().map(DapModels.LineBreakpoint::column).toList();
    }

    @Test
    void aPlainBreakpointIsItsLine() {
        Breakpoint bp = new Breakpoint(4, "x > 1", "", true, "", "3");
        List<DapModels.LineBreakpoint> sent = BreakpointTargets.of(bp, 9, LINE, true);
        assertEquals(List.of(new DapModels.LineBreakpoint(9, "x > 1", "", "3", 0)), sent);
    }

    @Test
    void theLineAndEachLambdaItStopsInEachWithItsCondition() {
        Breakpoint bp = new Breakpoint(4, "x > 1", "", true, "", "").withLambdas(List.of(2, 1), false);
        List<DapModels.LineBreakpoint> sent = BreakpointTargets.of(bp, 4, LINE, true);
        assertEquals(List.of(0, X, Y), columns(sent));
        assertTrue(sent.stream().allMatch(s -> s.line() == 4 && "x > 1".equals(s.condition())));
        assertEquals(List.of(Y), columns(BreakpointTargets.of(bp.withLambdas(List.of(2), true), 4, LINE, true)));
    }

    @Test
    void withNoLambdaLeftItStopsOnTheLine() {
        Breakpoint only = Breakpoint.plain(4, "").withLambdas(List.of(3), true);
        assertEquals(List.of(0), columns(BreakpointTargets.of(only, 4, LINE, true)), "lambda 3 was edited away");
        assertEquals(List.of(0), columns(BreakpointTargets.of(only, 4, null, true)), "its text unknown");
        assertEquals(List.of(0), columns(BreakpointTargets.of(only, 4, LINE, false)), "not Java");
        Breakpoint both = Breakpoint.plain(4, "").withLambdas(List.of(1, 5), true);
        assertEquals(List.of(X), columns(BreakpointTargets.of(both, 4, LINE, true)), "the lambda still there");
    }

    @Test
    void onlyJavaHasLambdaBreakpoints() {
        assertTrue(BreakpointTargets.javaLanguage("java"));
        assertFalse(BreakpointTargets.javaLanguage("kotlin"));
        assertFalse(BreakpointTargets.javaLanguage(null));
    }

    @Test
    void aRecordingBreakpointIsSentRecordingOnItsLineAndInItsLambdas() {
        Breakpoint bp =
                new Breakpoint(4, "", "", true, "", "").withRecording(true).withLambdas(List.of(1), false);
        List<DapModels.LineBreakpoint> sent = BreakpointTargets.of(bp, 4, LINE, true);
        assertEquals(List.of(0, X), columns(sent));
        assertTrue(sent.stream().allMatch(DapModels.LineBreakpoint::recording));
        assertTrue(
                BreakpointTargets.of(bp.withLogMessage("m"), 4, LINE, true).stream()
                        .noneMatch(DapModels.LineBreakpoint::recording),
                "a logpoint never stops");
        assertTrue(BreakpointTargets.of(bp.withRecording(false), 4, LINE, true).stream()
                .noneMatch(DapModels.LineBreakpoint::recording));
    }
}
