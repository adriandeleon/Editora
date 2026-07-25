package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the pure matching-bracket finder. */
class BraceMatcherTest {

    private static int[] match(String text, int caret) {
        return BraceMatcher.match(text, caret, BraceMatcher.DEFAULT_MAX_SCAN);
    }

    @Test
    void matchesFromOpenerToTheLeftOfCaret() {
        assertArrayEquals(new int[] {1, 3}, match("a(b)c", 2)); // caret just after '('
    }

    @Test
    void matchesFromCloserToTheLeftOfCaret() {
        assertArrayEquals(new int[] {1, 3}, match("a(b)c", 4)); // caret just after ')'
    }

    @Test
    void matchesBracketToTheRightWhenNoneToLeft() {
        assertArrayEquals(new int[] {1, 3}, match("a(b)c", 1)); // caret just before '('
    }

    @Test
    void nesting() {
        assertArrayEquals(new int[] {0, 4}, match("((x))", 1));
        assertArrayEquals(new int[] {1, 3}, match("((x))", 2));
    }

    @Test
    void mixedDelimiters() {
        assertArrayEquals(new int[] {0, 6}, match("{ [a] }", 1)); // outer braces
        assertArrayEquals(new int[] {2, 4}, match("{ [a] }", 3)); // inner brackets
    }

    @Test
    void noMatch() {
        assertNull(match("(", 1)); // unmatched opener
        assertNull(match("abc", 2)); // caret not next to a bracket
        assertNull(match("a(b)c", 0)); // nothing adjacent
    }

    @Test
    void scanCapReturnsNullWhenTooFar() {
        assertNull(BraceMatcher.match("(abcdef)", 1, 3)); // match is past the 3-char scan budget
    }

    // --- jumpTarget (go-to-matching-bracket) ---

    private static int jump(String text, int caret) {
        return BraceMatcher.jumpTarget(text, caret, BraceMatcher.DEFAULT_MAX_SCAN);
    }

    @Test
    void jumpFromJustAfterOpenerLandsJustAfterCloser() {
        assertEquals(5, jump("(abc)", 1)); // just after '(' → just after ')'
    }

    @Test
    void jumpFromJustBeforeOpenerLandsOnCloser() {
        assertEquals(4, jump("(abc)", 0)); // just before '(' → on ')'
    }

    @Test
    void jumpFromCloserGoesBackToTheOpener() {
        assertEquals(1, jump("(abc)", 5)); // just after ')' → just after '('
        assertEquals(0, jump("(abc)", 4)); // just before ')' → on '('
    }

    @Test
    void repeatedJumpsToggleBetweenThePair() {
        int a = jump("(abc)", 1); // → 5
        assertEquals(1, jump("(abc)", a), "jumping back returns to the start");
    }

    @Test
    void jumpHandlesNesting() {
        assertEquals(7, jump("f(g(x))", 2)); // just after the outer '(' → just after the outer ')'
    }

    @Test
    void jumpReturnsMinusOneWhenNoBracketAdjacent() {
        assertEquals(-1, jump("abc", 1));
        assertEquals(-1, jump("a(b", 2), "an unmatched opener has no mate");
    }

    // --- selectSpan (select-to-bracket, brackets included) ---

    @Test
    void selectSpanIncludesBothBrackets() {
        assertArrayEquals(new int[] {1, 5}, BraceMatcher.selectSpan("a(bc)d", 2, BraceMatcher.DEFAULT_MAX_SCAN));
    }

    @Test
    void selectSpanIsNullWhenNoBracketAdjacent() {
        assertNull(BraceMatcher.selectSpan("abc", 1, BraceMatcher.DEFAULT_MAX_SCAN));
    }
}
