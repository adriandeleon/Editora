package com.editora.editops;

import com.editora.editops.AutoFill.Break;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the pure auto-fill engine (no toolkit). */
class AutoFillTest {

    /** Applies a break to {@code line} and returns the resulting (possibly multi-line) text. */
    private static String apply(String line, int fillColumn, String prefix) {
        Break b = AutoFill.compute(line, fillColumn, prefix);
        if (b == null) {
            return line;
        }
        return line.substring(0, b.at()) + b.insert() + line.substring(b.at() + b.removeLen());
    }

    @Test
    void shortLineIsNotBroken() {
        assertNull(AutoFill.compute("hello world", 20, ""));
    }

    @Test
    void aLineAtExactlyTheColumnIsNotBroken() {
        assertNull(AutoFill.compute("123456", 6, ""));
    }

    @Test
    void breaksAtTheLastSpaceWithinTheColumn() {
        // fill column 10: "the quick " fits; "brown" pushes over → break at the space before "brown".
        assertEquals("the quick\nbrown fox", apply("the quick brown fox", 10, ""));
    }

    @Test
    void collapsesTheWhitespaceRunAtTheBreak() {
        assertEquals("one two\nthree", apply("one two   three", 8, ""), "the run of spaces becomes the newline");
    }

    @Test
    void carriesTheContinuationPrefix() {
        assertEquals("    aaa\n    bbb ccc", apply("    aaa bbb ccc", 7, "    "), "the wrapped line keeps the indent");
    }

    @Test
    void carriesACommentPrefixForACommentLine() {
        assertEquals("// aaa\n// bbb ccc", apply("// aaa bbb ccc", 6, "// "), "a comment continuation repeats //");
    }

    @Test
    void breaksAfterTheColumnWhenNoSpaceFitsWithinIt() {
        // "superlongword" runs past column 5; the first space is after it → break there.
        assertEquals("superlongword\nnext", apply("superlongword next", 5, ""));
    }

    @Test
    void anUnbreakableLineIsLeftLong() {
        assertNull(AutoFill.compute("superlongwordwithnospaces", 5, ""), "no whitespace anywhere — cannot break");
    }

    @Test
    void doesNotBreakInsideLeadingIndentation() {
        assertNull(
                AutoFill.compute("        word", 4, ""), "the only space is before the first word — no useful break");
    }

    @Test
    void breaksOnlyOncePerCall() {
        // A very long line: one break at the fill point; the tail is left for the next keystroke.
        String out = apply("aaa bbb ccc ddd eee", 5, "");
        assertEquals("aaa\nbbb ccc ddd eee", out, "auto-fill inserts a single break, not a full reflow");
    }

    @Test
    void handlesTabsAsBreakPoints() {
        assertEquals("aaa\nbbb", apply("aaa\tbbb", 4, ""));
    }
}
