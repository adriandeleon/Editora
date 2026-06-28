package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
}
