package com.editora.editor;

import java.util.function.BiFunction;

import com.editora.editor.EmacsEdits.Edit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Unit tests for the pure Emacs word/whitespace/line editing engine (no toolkit). */
class EmacsEditsTest {

    /** Applies an Edit to {@code text}, returning the result with {@code |} at the caret (null on no-op). */
    private static String apply(String text, Edit e) {
        if (e == null) {
            return null;
        }
        String out = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return out.substring(0, e.caret()) + "|" + out.substring(e.caret());
    }

    /** Runs a caret-based op on text written with a {@code |} caret marker. */
    private static String run(BiFunction<String, Integer, Edit> op, String withCaret) {
        int caret = withCaret.indexOf('|');
        String text = withCaret.replace("|", "");
        return apply(text, op.apply(text, caret));
    }

    // --- backward-kill-word (M-DEL) ---
    @Test
    void backwardKillWord() {
        assertEquals("foo |baz", run(EmacsEdits::backwardKillWord, "foo bar|baz"));
        assertEquals("|bar", run(EmacsEdits::backwardKillWord, "foo |bar"));
        assertEquals("foo |", run(EmacsEdits::backwardKillWord, "foo bar.|")); // skips '.', then kills "bar"
        assertNull(run(EmacsEdits::backwardKillWord, "|foo")); // start of buffer
    }

    // --- upcase / downcase / capitalize word (M-u / M-l / M-c) ---
    @Test
    void caseWord() {
        assertEquals("HELLO| world", run(EmacsEdits::upcaseWord, "|hello world"));
        assertEquals("hello| world", run(EmacsEdits::downcaseWord, "|HELLO world"));
        assertEquals("Hello| WORLD", run(EmacsEdits::capitalizeWord, "|hELLO WORLD"));
        // Caret mid-word: only from the caret to the word end is affected.
        assertEquals("fooBAR|", run(EmacsEdits::upcaseWord, "foo|bar"));
        // Leading whitespace before the word is preserved.
        assertEquals("   WORD|", run(EmacsEdits::upcaseWord, "|   word"));
        assertNull(run(EmacsEdits::upcaseWord, "hello|")); // no word ahead
    }

    // --- upcase / downcase region (C-x C-u / C-x C-l) ---
    @Test
    void caseRegion() {
        assertEquals("ABC", applyRegion(EmacsEdits.upcaseRegion("abc", 0, 3)));
        assertEquals("abc", applyRegion(EmacsEdits.downcaseRegion("ABC", 0, 3)));
        assertNull(EmacsEdits.upcaseRegion("abc", 2, 2)); // empty selection
        assertNull(EmacsEdits.upcaseRegion("ABC", 0, 3)); // already upper: no-op
    }

    private static String applyRegion(Edit e) {
        return e == null ? null : e.replacement();
    }

    // --- delete-indentation / join-line (M-^) ---
    @Test
    void deleteIndentation() {
        assertEquals("foo |bar", run(EmacsEdits::deleteIndentation, "foo\n   |bar"));
        assertEquals("foo |bar", run(EmacsEdits::deleteIndentation, "foo   \n   bar|"));
        // Empty previous line → no separating space.
        assertEquals("|bar", run(EmacsEdits::deleteIndentation, "\n|bar"));
        assertNull(run(EmacsEdits::deleteIndentation, "fo|o")); // first line
    }

    // --- delete-horizontal-space (M-\) / just-one-space (M-SPC) ---
    @Test
    void horizontalSpace() {
        assertEquals("foo|bar", run(EmacsEdits::deleteHorizontalSpace, "foo  |  bar"));
        assertNull(run(EmacsEdits::deleteHorizontalSpace, "foo|bar")); // none around caret
        assertEquals("foo |bar", run(EmacsEdits::justOneSpace, "foo  |  bar"));
        assertEquals("foo |bar", run(EmacsEdits::justOneSpace, "foo|bar")); // inserts one
        assertNull(run(EmacsEdits::justOneSpace, "foo |bar")); // already one space
    }

    // --- open-line (C-o) ---
    @Test
    void openLine() {
        assertEquals("foo|\nbar", run(EmacsEdits::openLine, "foo|bar"));
    }

    // --- kill-whole-line (C-S-DEL) ---
    @Test
    void killWholeLine() {
        assertEquals("a\n|c", run(EmacsEdits::killWholeLine, "a\nb|\nc"));
        assertEquals("|b", run(EmacsEdits::killWholeLine, "a|\nb")); // first line with newline
        assertEquals("a|", run(EmacsEdits::killWholeLine, "a\nb|")); // last line, no trailing newline
        assertNull(run(EmacsEdits::killWholeLine, "|")); // empty buffer
    }

    // --- zap-to-char (M-z) ---
    @Test
    void zapToChar() {
        assertEquals("|world", run((t, c) -> EmacsEdits.zapToChar(t, c, ' '), "|hello world"));
        assertEquals("| end", run((t, c) -> EmacsEdits.zapToChar(t, c, ','), "|hello, end")); // inclusive
        assertNull(EmacsEdits.zapToChar("hello", 0, 'z')); // not found
    }

    // --- delete-blank-lines (C-x C-o) ---
    @Test
    void deleteBlankLines() {
        // On a blank line within a run: collapse to one blank line.
        assertEquals("a\n|\nb", run(EmacsEdits::deleteBlankLines, "a\n|\n\n\nb"));
        // Isolated blank line: delete it.
        assertEquals("a\n|b", run(EmacsEdits::deleteBlankLines, "a\n|\nb"));
        // Non-blank line: delete the following blank lines.
        assertEquals("a|\nb", run(EmacsEdits::deleteBlankLines, "a|\n\n\nb"));
        assertNull(run(EmacsEdits::deleteBlankLines, "a|\nb")); // nothing to delete
    }
}
