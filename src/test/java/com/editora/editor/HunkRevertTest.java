package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Revert Hunk is one text replacement in the buffer. Each case names the {@code HEAD} text, the buffer text
 * and the hunk git reports between them, and expects the replacement to give {@code HEAD} back exactly —
 * including whether the document ends in a newline.
 */
class HunkRevertTest {

    private static String revert(String buffer, int line, int count, List<String> old, boolean oldUnterminated) {
        HunkRevert.Edit e = HunkRevert.plan(buffer, line, count, old, oldUnterminated);
        return buffer.substring(0, e.start()) + e.text() + buffer.substring(e.end());
    }

    @Test
    void aModifiedRunInTheMiddle() {
        assertEquals("a\nb\nc\nd\n", revert("a\nB1\nB2\nB3\nd\n", 1, 3, List.of("b", "c"), false));
    }

    @Test
    void aPureAdditionIsRemovedWithItsLineBreak() {
        assertEquals("a\nb\n", revert("a\nnew\nb\n", 1, 1, List.of(), false));
        assertEquals("a\n", revert("new 1\nnew 2\na\n", 0, 2, List.of(), false));
        assertEquals("a\n", revert("a\nnew\n", 1, 1, List.of(), false), "at the end, with a final newline");
        assertEquals("a\n", revert("a\nnew", 1, 1, List.of(), false), "at the end, without one");
        assertEquals("", revert("only\n", 0, 1, List.of(), false), "the whole file was added");
    }

    @Test
    void aPureDeletionGoesBackAboveTheLineItIsMarkedOn() {
        assertEquals("a\ngone 1\ngone 2\nb\n", revert("a\nb\n", 1, 0, List.of("gone 1", "gone 2"), false));
        assertEquals("gone\na\n", revert("a\n", 0, 0, List.of("gone"), false), "at the top");
        assertEquals("a\ngone\n", revert("a\n", 1, 0, List.of("gone"), false), "at the end: above the empty last line");
        assertEquals("gone\n", revert("", 0, 0, List.of("gone"), false), "everything was deleted");
    }

    @Test
    void theLastLineKeepsTheFinalNewlineHeadHad() {
        // HEAD "a\nb\n" — the buffer dropped the final newline while changing the last line.
        assertEquals("a\nb\n", revert("a\nB", 1, 1, List.of("b"), false));
        // HEAD "a\nb" (no final newline) — the buffer gained one.
        assertEquals("a\nb", revert("a\nB\n", 1, 1, List.of("b"), true));
        assertEquals("a\nb", revert("a\nb\nc\n", 1, 2, List.of("b"), true), "…and a line");
        assertEquals("a\nb", revert("a\nb\nc", 1, 2, List.of("b"), true), "neither side has one");
        assertEquals("a\nb", revert("a\n", 1, 0, List.of("b"), true), "an unterminated last line was deleted");
        // An unterminated old side further up than the end cannot happen; a terminated one at the end can.
        assertEquals("a\nb\nc\n", revert("a\nX", 1, 1, List.of("b", "c"), false));
    }

    @Test
    void aCrlfFilesCarriageReturnsStayOutOfTheBuffer() {
        // git prints a CRLF file's lines with their \r; the buffer holds bare \n and adds CRLF on save.
        assertEquals("a\nb\nc\n", revert("a\nB\nc\n", 1, 1, List.of("b\r"), false));
        assertEquals("a\nx\ny\nc\n", revert("a\nc\n", 1, 0, List.of("x\r", "y\r"), false));
    }

    @Test
    void anEmptyOldLineIsStillALine() {
        assertEquals("a\n\nb\n", revert("a\nb\n", 1, 0, List.of(""), false));
        assertEquals("a\n\nb\n", revert("a\nx\nb\n", 1, 1, List.of(""), false));
    }
}
