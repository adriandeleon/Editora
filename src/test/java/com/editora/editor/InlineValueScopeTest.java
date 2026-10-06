package com.editora.editor;

import java.util.List;
import java.util.Set;
import java.util.function.IntFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Inline debug values belong to the function the frame is stopped in, and to code — not to every line. */
class InlineValueScopeTest {

    private static IntFunction<String> lines(String text) {
        List<String> all = text.lines().toList();
        return i -> i >= 0 && i < all.size() ? all.get(i) : "";
    }

    private static final String PYTHON = """
            def work(n):
                x = n + 1
                if x:
                    x += 1
                return x


            def other(items):
                x = 'unrelated'
                n = len(items)
                return x, n

            x = work(0)
            """;

    @Test
    void anotherFunctionsLinesAreNotTheFramesScope() {
        IntFunction<String> at = lines(PYTHON);
        int frame = 1; // stopped on "x = n + 1" in work()
        assertTrue(InlineValueScope.sameScope(at, frame, 0), "the header names the parameters");
        assertTrue(InlineValueScope.sameScope(at, frame, 3), "a nested if block is the same function");
        assertTrue(InlineValueScope.sameScope(at, frame, 4));
        assertFalse(InlineValueScope.sameScope(at, frame, 8), "other() has its own x");
        assertFalse(InlineValueScope.sameScope(at, frame, 9));
        assertFalse(InlineValueScope.sameScope(at, frame, 12), "module code is not work()'s scope");
    }

    @Test
    void aModuleLevelFrameAnnotatesModuleCodeOnly() {
        IntFunction<String> at = lines(PYTHON);
        assertEquals(InlineValueScope.TOP_LEVEL, InlineValueScope.headerOf(at, 12));
        assertFalse(InlineValueScope.sameScope(at, 12, 1));
        assertTrue(InlineValueScope.sameScope(at, 12, 12));
    }

    private static final String JAVA = """
            class Demo {
                int total(int[] values,
                        int start) {
                    int sum = 0;
                    for (int i = start; i < values.length; i++) {
                        try {
                            sum += values[i];
                        } catch (RuntimeException e) {
                            sum = -1;
                        }
                    }
                    String text = describe(
                            sum);
                    return sum;
                }

                String describe(int sum) {
                    int i = sum * 2;
                    return "sum " + i;
                }
            }
            """;

    @Test
    void controlBlocksAndWrappedStatementsStayInTheirMethod() {
        IntFunction<String> at = lines(JAVA);
        assertEquals(1, InlineValueScope.headerOf(at, 3));
        assertEquals(1, InlineValueScope.headerOf(at, 6), "for + try are not scopes of their own");
        assertEquals(1, InlineValueScope.headerOf(at, 8), "nor is a catch block");
        assertEquals(1, InlineValueScope.headerOf(at, 12), "the second line of a wrapped call");
        assertEquals(16, InlineValueScope.headerOf(at, 17));
        assertFalse(InlineValueScope.sameScope(at, 6, 17), "describe()'s i and sum are other variables");
        assertTrue(InlineValueScope.sameScope(at, 6, 13));
    }

    @Test
    void anUnknownFrameLineDoesNotHideEverything() {
        assertTrue(InlineValueScope.sameScope(lines(PYTHON), -1, 8));
    }

    @Test
    void namesInCommentsAndStringsAreNotMatches() {
        String line = "    x = 'x marks the spot'  # x again";
        int comment = line.indexOf('#');
        int open = line.indexOf('\'');
        int close = line.lastIndexOf('\'');
        assertEquals(List.of("x", "spot", "again"), DebugIdentifiers.matchesIn(line, Set.of("x", "spot", "again")));
        assertEquals(
                List.of("x"),
                DebugIdentifiers.matchesIn(
                        line, Set.of("x", "spot", "again"), col -> col >= comment || (col > open && col < close)));
        assertEquals(
                List.of(),
                DebugIdentifiers.matchesIn("# x is a string here", Set.of("x"), col -> true),
                "a name that only occurs in a comment is no match at all");
    }
}
