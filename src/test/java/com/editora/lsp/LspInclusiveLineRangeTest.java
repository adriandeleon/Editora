package com.editora.lsp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LspManager.inclusiveLineRange} — the LSP range for a line window, clamped to the document (#715).
 *
 * <p>An inclusive window {@code [start..end]} normally becomes the exclusive end {@code Position(end+1, 0)},
 * but for the <em>last</em> line that names a line the document does not have — and <b>jdtls answers an
 * out-of-range range with an empty list, not an error</b>, which is indistinguishable from "no hints here".
 * Measured on jdtls 1.60 with a 27-line file (last line index 26): end {@code Position(27,0)} → 0 hints,
 * {@code Position(26,0)} → 6 hints. That single off-by-one is why inlay hints never appeared.
 */
class LspInclusiveLineRangeTest {

    /** The #715 case: the whole of a 27-line file whose last line is empty (the file ends in a newline). */
    @Test
    void theWholeDocumentEndsAtTheDocumentEndNotPastIt() {
        var r = LspManager.inclusiveLineRange(0, 26, 27, 0);
        assertEquals(0, r.getStart().getLine());
        assertEquals(0, r.getStart().getCharacter());
        assertEquals(26, r.getEnd().getLine(), "must not name line 27 — the document has lines 0..26");
        assertEquals(0, r.getEnd().getCharacter());
    }

    /** A file with no trailing newline: the last line has content, so the end must span it. */
    @Test
    void aLastLineWithContentIsCoveredToItsEnd() {
        var r = LspManager.inclusiveLineRange(0, 9, 10, 42);
        assertEquals(9, r.getEnd().getLine());
        assertEquals(42, r.getEnd().getCharacter(), "the last line must be covered in full");
    }

    /** Away from the end, the ordinary exclusive-next-line form is kept (it covers the line in full). */
    @Test
    void anInteriorWindowUsesTheNextLineAsTheExclusiveEnd() {
        var r = LspManager.inclusiveLineRange(300, 700, 5000, 12);
        assertEquals(300, r.getStart().getLine());
        assertEquals(701, r.getEnd().getLine());
        assertEquals(0, r.getEnd().getCharacter());
    }

    /** The padded window the coordinator produces for a short file must still land inside the document. */
    @Test
    void aPaddedWindowOverAShortFileStaysInsideTheDocument() {
        // 27-line file, viewport padded far past the end (what LspCoordinator.paddedWindow feeds in).
        var r = LspManager.inclusiveLineRange(0, 226, 27, 0);
        assertEquals(26, r.getEnd().getLine());
        assertEquals(0, r.getEnd().getCharacter());
    }

    @Test
    void singleLineAndEmptyDocumentsAreWellFormed() {
        var one = LspManager.inclusiveLineRange(0, 0, 1, 17);
        assertEquals(0, one.getStart().getLine());
        assertEquals(0, one.getEnd().getLine());
        assertEquals(17, one.getEnd().getCharacter());

        var empty = LspManager.inclusiveLineRange(0, 0, 0, 0);
        assertEquals(0, empty.getStart().getLine());
        assertEquals(0, empty.getEnd().getLine());
        assertEquals(0, empty.getEnd().getCharacter());
    }

    /** Negatives and inverted inputs must never produce a negative or inverted range. */
    @Test
    void garbageInputsStillYieldAWellFormedRange() {
        int[][] inputs = {{-5, -1}, {10, 3}, {0, 0}, {26, 26}, {-1, 400}};
        int[][] docs = {{1, 0}, {27, 0}, {10, 42}, {5000, 3}};
        for (int[] in : inputs) {
            for (int[] doc : docs) {
                var r = LspManager.inclusiveLineRange(in[0], in[1], doc[0], doc[1]);
                String at = java.util.Arrays.toString(in) + " in " + java.util.Arrays.toString(doc) + " → " + r;
                int lastLine = Math.max(0, doc[0] - 1);
                assertTrue(r.getStart().getLine() >= 0, "negative start: " + at);
                assertTrue(r.getStart().getCharacter() >= 0, "negative start char: " + at);
                assertTrue(r.getEnd().getCharacter() >= 0, "negative end char: " + at);
                assertTrue(r.getEnd().getLine() <= lastLine, "end past the last line: " + at);
                assertTrue(
                        r.getEnd().getLine() > r.getStart().getLine()
                                || (r.getEnd().getLine() == r.getStart().getLine()
                                        && r.getEnd().getCharacter()
                                                >= r.getStart().getCharacter()),
                        "inverted range: " + at);
            }
        }
    }
}
