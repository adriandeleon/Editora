package com.editora.ui;

import com.editora.dap.DapModels;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a debugger completion looks like and what accepting it does (follow-up 5), on java-debug's answers. */
class DebugCompletionTextTest {

    private static DapModels.CompletionTarget target(String label, String text) {
        return new DapModels.CompletionTarget(label, text, "function", null, "1", -1, -1);
    }

    @Test
    void aMethodShowsItsParametersWithTheReturnTypeAsDetailAndATypeItsPackage() {
        assertEquals(
                new DebugCompletionText.Shown("subList(int fromIndex, int toIndex)", "List<String>"),
                DebugCompletionText.shown("subList(int fromIndex, int toIndex) : List<String>"));
        assertEquals(
                new DebugCompletionText.Shown("Name", "javax.naming"),
                DebugCompletionText.shown("Name - javax.naming"));
        assertEquals(new DebugCompletionText.Shown("names", ""), DebugCompletionText.shown("names"));
        assertTrue(DebugCompletionText.hasParameters("get(Object key) : Integer"));
        assertFalse(DebugCompletionText.hasParameters("size() : int"));
    }

    @Test
    void acceptingReplacesTheNameBeforeTheCaretAndPutsTheCaretWhereTheArgumentsGo() {
        DapModels.CompletionTarget get = target("get(Object key) : Integer", "get()");
        assertEquals(
                new DebugCompletionText.Edit("lengths.get()", 12),
                DebugCompletionText.apply("lengths.ge", 10, get, true),
                "between the parentheses");
        DapModels.CompletionTarget size = target("size() : int", "size()");
        assertEquals(
                new DebugCompletionText.Edit("names.size() + 1", 12),
                DebugCompletionText.apply("names.si + 1", 8, size, false),
                "after them, the rest kept");
        assertEquals(
                new DebugCompletionText.Edit("names.size()", 12),
                DebugCompletionText.apply("names.sizzle", 8, size, false),
                "the rest of the name is replaced too");
        DapModels.CompletionTarget ranged = new DapModels.CompletionTarget("x", "xyz", "", null, "", 0, 2);
        assertEquals(
                new DebugCompletionText.Edit("xyz.q", 3),
                DebugCompletionText.apply("ab.q", 1, ranged, false),
                "an adapter's own range wins");
        assertEquals("na", DebugCompletionText.prefix("x + na", 6));
    }

    @Test
    void inALogMessageOnlyTheBracedExpressionAtTheCaretIsCode() {
        assertEquals(3, DebugCompletionText.braceStart("n={na", 5));
        assertEquals(-1, DebugCompletionText.braceStart("n={n} and na", 12), "after the closing brace: text");
        assertEquals(-1, DebugCompletionText.braceStart("plain", 3));
    }
}
