package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import com.editora.editor.Transposer.Edit;

/** Unit tests for the pure Emacs transpose engine (no toolkit). */
class TransposerTest {

    /** Applies an Edit to {@code text} and returns the resulting text with a {@code |} at the caret. */
    private static String apply(String text, Edit e) {
        if (e == null) {
            return null;
        }
        String out = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return out.substring(0, e.caret()) + "|" + out.substring(e.caret());
    }

    private static String chars(String textWithCaret) {
        int caret = textWithCaret.indexOf('|');
        String text = textWithCaret.replace("|", "");
        return apply(text, Transposer.transposeChars(text, caret));
    }

    private static String words(String textWithCaret) {
        int caret = textWithCaret.indexOf('|');
        String text = textWithCaret.replace("|", "");
        return apply(text, Transposer.transposeWords(text, caret));
    }

    private static String lines(String textWithCaret) {
        int caret = textWithCaret.indexOf('|');
        String text = textWithCaret.replace("|", "");
        return apply(text, Transposer.transposeLines(text, caret));
    }

    // --- transpose-chars (C-t) ---

    @Test
    void charsMidline() {
        // "ab|cd": swap b and c, caret moves forward → "acb|d"
        assertEquals("acb|d", chars("ab|cd"));
    }

    @Test
    void charsAtEndOfLineSwapsPreceding() {
        // "teh|" → "the|" (fix a typo at end of line, caret stays)
        assertEquals("the|", chars("teh|"));
    }

    @Test
    void charsAtEndOfLineWithNewline() {
        assertEquals("the|\nx", chars("teh|\nx"));
    }

    @Test
    void charsNoOpAtBufferStart() {
        assertNull(Transposer.transposeChars("abc", 0));
    }

    @Test
    void charsNoOpAtLineStart() {
        assertNull(Transposer.transposeChars("ab\ncd", 3)); // caret at start of "cd" line
    }

    // --- transpose-words (M-t) ---

    @Test
    void wordsCaretBetween() {
        assertEquals("bar foo|", words("foo |bar"));
        assertEquals("bar foo|", words("foo| bar"));
    }

    @Test
    void wordsCaretInsideFirstWord() {
        assertEquals("bar foo|", words("f|oo bar"));
    }

    @Test
    void wordsAtEndOfBufferSwapsLastTwo() {
        assertEquals("bar foo|", words("foo bar|"));
    }

    @Test
    void wordsPreservePunctuationSeparator() {
        // separators between the words stay put; only the words move
        assertEquals("bar, foo|", words("foo, |bar"));
    }

    @Test
    void wordsNoOpWithSingleWord() {
        assertNull(Transposer.transposeWords("foo", 3));
        assertNull(Transposer.transposeWords("   ", 1));
    }

    // --- transpose-lines (C-x C-t) ---

    @Test
    void linesSwapWithPrevious() {
        // caret on line 2 ("two"): swap with line 1, caret to start of line 3
        assertEquals("two\none\n|three", lines("one\ntw|o\nthree"));
    }

    @Test
    void linesLastLineNoTrailingNewline() {
        assertEquals("two\none|", lines("one\ntw|o"));
    }

    @Test
    void linesNoOpOnFirstLine() {
        assertNull(Transposer.transposeLines("one\ntwo", 1));
    }
}
