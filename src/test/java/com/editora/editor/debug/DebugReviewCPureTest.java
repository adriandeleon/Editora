package com.editora.editor.debug;

import java.util.List;
import java.util.Set;

import com.editora.config.Breakpoint;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure editor-side decisions of the Java-debugging review's UI fixes (review C). */
class DebugReviewCPureTest {

    // --- C3: a lambda's own parameters ----------------------------------------------------------------

    @Test
    void lambdaParametersOfJavaJsAndPythonLambdas() {
        assertEquals(Set.of("n"), DebugIdentifiers.lambdaParameters("names.forEach(n -> {"));
        assertEquals(
                Set.of("a", "b"), DebugIdentifiers.lambdaParameters("map.forEach((String a, int b) -> run(a, b));"));
        assertEquals(Set.of("x"), DebugIdentifiers.lambdaParameters("const f = x => x + 1;"));
        assertEquals(Set.of("p", "q"), DebugIdentifiers.lambdaParameters("items.sort(key=lambda p, q=1: p)"));
        assertEquals(Set.of(), DebugIdentifiers.lambdaParameters("if (x > 0 && y >= 1) {"));
        assertEquals(Set.of(), DebugIdentifiers.lambdaParameters("int total = sum(values);"));
    }

    // --- C1 / B6: revealing the execution line --------------------------------------------------------

    @Test
    void aLineInPlainViewIsNotScrolledTo() {
        assertEquals(-1, ExecutionLine.revealTop(20, 10, 40, 1, t -> t), "stepping inside the view never scrolls");
    }

    @Test
    void aLineUnderThePinnedHeadersIsScrolledClearOfThem() {
        // The viewport starts at 10 with two pinned header rows: lines 10 and 11 are covered (review B6).
        int top = ExecutionLine.revealTop(11, 10, 40, 2, t -> Math.max(0, t - 2));
        assertTrue(top >= 0 && top <= 11 - 2, "the stopped line sits below the two headers, top=" + top);
    }

    @Test
    void aLineOutOfViewGetsContextAboveIt() {
        assertEquals(100 - 10, ExecutionLine.revealTop(100, 0, 30, 0, t -> t), "a third of a 30-row screen above it");
        assertEquals(50 - ExecutionLine.MIN_CONTEXT, ExecutionLine.revealTop(50, -1, -1, 0, t -> t), "not laid out");
        assertEquals(0, ExecutionLine.revealTop(1, 20, 50, 0, t -> t));
    }

    // --- C11: what a breakpoint is, in words -----------------------------------------------------------

    @Test
    void aBreakpointsConditionLogMessageHitCountAndStateAreDescribed() {
        Breakpoint bp = new Breakpoint(3, "n > 2", "n={n}", false, "x", "5");
        assertEquals(
                List.of(
                        tr("debug.breakpoint.disabled"),
                        tr("debug.breakpoint.logMessage", "n={n}"),
                        tr("debug.breakpoint.condition", "n > 2"),
                        tr("debug.breakpoint.hitCondition", "5")),
                BreakpointDescription.lines(bp));
        assertNull(BreakpointDescription.tooltip(Breakpoint.plain(1, "x"), null), "a plain one has no hover text");
        assertEquals(
                tr("debug.breakpoint.condition", "a") + "\nNot verified",
                BreakpointDescription.tooltip(Breakpoint.plain(1, "x").withCondition("a"), "Not verified"));
    }

    // --- C3 / B14: which lines carry inline values -------------------------------------------------------

    private static final List<String> SOURCE = List.of(
            "    public static void main(String[] args) {", // 0
            "        for (String n : names) {", // 1
            "            lengths.put(n, n.length());", // 2  <- stopped here
            "        }", // 3
            "        names.forEach(n -> {", // 4
            "            System.out.println(n);", // 5
            "        });", // 6
            "        int total = sum(lengths);", // 7
            "    }"); // 8

    private static List<String> on(int p, int frameLine) {
        java.util.function.IntFunction<String> lineAt = i -> i >= 0 && i < SOURCE.size() ? SOURCE.get(i) : "";
        int header = InlineValueScope.headerOf(lineAt, frameLine);
        return InlineValuesOverlay.namesOn(
                SOURCE.get(p), p, frameLine, header, Set.of("n", "names", "lengths", "total"), c -> false, lineAt);
    }

    @Test
    void noValuesBelowTheStopLineAndALambdaParameterIsNotTheLoopVariable() {
        assertEquals(List.of("n", "names"), on(1, 2));
        assertEquals(List.of("lengths", "n"), on(2, 2));
        assertEquals(List.of(), on(4, 2), "below the stop: has not run yet (B14), and n there is the lambda's");
        assertEquals(List.of(), on(7, 2));
    }

    @Test
    void stoppedAboveTheLambdaLineItsParameterIsStillLeftOut() {
        assertEquals(List.of("names"), on(4, 7), "the forEach line: names, but not the lambda's n (C3)");
        assertEquals(List.of("total", "lengths"), on(7, 7));
    }

    @Test
    void stoppedInsideTheLambdaItsHeaderShowsTheParameter() {
        assertEquals(List.of("names", "n"), on(4, 5), "in the lambda, n is the frame's own variable");
        assertEquals(List.of("n"), on(5, 5));
    }
}
