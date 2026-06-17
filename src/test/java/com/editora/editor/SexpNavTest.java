package com.editora.editor;

import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the pure structural navigation engine (sexp / defun / paragraph). */
class SexpNavTest {

    /** Runs a navigation op on text with a {@code |} caret marker; returns text with {@code |} at the result. */
    private static String nav(BiFunction<String, Integer, Integer> op, String withCaret) {
        int caret = withCaret.indexOf('|');
        String text = withCaret.replace("|", "");
        int pos = op.apply(text, caret);
        return text.substring(0, pos) + "|" + text.substring(pos);
    }

    // --- forward-sexp (C-M-f) ---
    @Test
    void forwardSexp() {
        assertEquals("foo| bar", nav(SexpNav::forward, "|foo bar")); // symbol run
        assertEquals(" foo| bar", nav(SexpNav::forward, " |foo bar")); // skips leading whitespace
        assertEquals("(a b)| c", nav(SexpNav::forward, "|(a b) c")); // balanced group
        assertEquals("(a (b) c)|", nav(SexpNav::forward, "|(a (b) c)")); // nested
        assertEquals("\"a b\"| c", nav(SexpNav::forward, "|\"a b\" c")); // string
        assertEquals("|) x", nav(SexpNav::forward, "|) x")); // before a closer: no-op
    }

    // --- backward-sexp (C-M-b) ---
    @Test
    void backwardSexp() {
        assertEquals("foo |bar", nav(SexpNav::backward, "foo bar|"));
        assertEquals("x |(a b)", nav(SexpNav::backward, "x (a b)|"));
        assertEquals("|(a (b) c)", nav(SexpNav::backward, "(a (b) c)|"));
    }

    // --- mark-sexp uses forward(); verify span endpoints ---
    @Test
    void sexpSpan() {
        assertEquals(5, SexpNav.forward("(a b) rest", 0));
        assertEquals(0, SexpNav.backward("(a b)", 5));
    }

    // --- beginning-of-defun / end-of-defun (C-M-a / C-M-e), heuristic ---
    @Test
    void defun() {
        String src = "class A {\n  void m() {}\n}\n\nclass B {\n}\n";
        int insideA = src.indexOf("void");
        assertEquals(0, SexpNav.beginningOfDefun(src, insideA)); // back to "class A"
        int classB = src.indexOf("class B");
        assertEquals(0, SexpNav.beginningOfDefun(src, classB - 1)); // from the blank line before B
        // end-of-defun from the start of A lands at/before the "class B" line.
        int end = SexpNav.endOfDefun(src, 0);
        assertEquals("class B", src.substring(end, end + 7));
    }

    // --- paragraph bounds (mark-paragraph, M-h) ---
    @Test
    void paragraphBounds() {
        String t = "a\nb\n\nc\nd";
        int[] first = SexpNav.paragraphBounds(t, 2); // caret on line "b"
        assertEquals(0, first[0]);
        assertEquals(3, first[1]); // "a\nb"
        int[] second = SexpNav.paragraphBounds(t, t.indexOf('c'));
        assertEquals(t.indexOf('c'), second[0]);
        assertEquals(t.length(), second[1]); // "c\nd"
    }
}
