package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NoteAnchorsTest {

    @Test
    void shiftOffsetBeforeInsideAndAfterAnEdit() {
        // edit at pos=10, removed 0, inserted 5 (typed 5 chars)
        assertEquals(5, NoteAnchors.shiftOffset(5, 10, 0, 5), "before the edit: unchanged");
        assertEquals(20, NoteAnchors.shiftOffset(15, 10, 0, 5), "after: moves by +5");
        // deletion at pos=10, removed 4
        assertEquals(10, NoteAnchors.shiftOffset(12, 10, 4, 0), "inside the deleted span: collapses to pos");
        assertEquals(10, NoteAnchors.shiftOffset(14, 10, 4, 0), "at end of deleted span: -4");
        assertEquals(10, NoteAnchors.shiftOffset(10, 10, 4, 0), "exactly at pos: unchanged");
    }

    @Test
    void shiftRangeNormalizesAndTracksBothEnds() {
        assertArrayEquals(new int[] {3, 8}, NoteAnchors.shiftRange(3, 8, 100, 0, 0), "edit after range: unchanged");
        assertArrayEquals(new int[] {6, 11}, NoteAnchors.shiftRange(3, 8, 0, 0, 3), "insert before: +3 both");
    }

    @Test
    void relocateExactAtSavedOffset() {
        String doc = "alpha beta gamma";
        int[] r = NoteAnchors.relocate(doc, 6, 10, "beta", "", "");
        assertArrayEquals(new int[] {6, 10}, r, "text still at the saved offset → keep");
    }

    @Test
    void relocateFindsMovedTextNearestToSaved() {
        // "beta" moved from offset 6 to offset 0 (text edited above).
        String doc = "beta alpha gamma";
        int[] r = NoteAnchors.relocate(doc, 6, 10, "beta", "", "");
        assertArrayEquals(new int[] {0, 4}, r, "relocated to the (only) occurrence");
    }

    @Test
    void relocateUsesContextToDisambiguateOccurrences() {
        // Two "x" occurrences; the saved offset (0) no longer holds "x", so occurrence scoring runs and
        // the context (prefix "= ", suffix ";") should pick the second occurrence (offset 15) over the
        // first (offset 4), even though the first is nearer to the saved offset.
        String doc = "var x = 1; y = x;";
        int[] r = NoteAnchors.relocate(doc, 0, 1, "x", "= ", ";");
        assertEquals(15, r[0], "context (prefix '= ', suffix ';') selects the second occurrence");
    }

    @Test
    void relocateOrphansWhenTextIsGone() {
        assertNull(
                NoteAnchors.relocate("totally different content", 0, 4, "missing", "", ""),
                "no occurrence → orphan (null)");
    }

    @Test
    void relocateEmptySelectionKeepsClampedPosition() {
        int[] r = NoteAnchors.relocate("abc", 99, 99, "", "", "");
        assertArrayEquals(new int[] {3, 3}, r, "empty needle (blank line) → clamped position, not orphan");
    }

    // --- span covers the full original selection, not just the capped needle (#454) -----------------

    @Test
    void relocateSpansTheFullSelectionLengthNotJustTheCappedNeedle() {
        // A note on a long selection: the stored needle is capped, but `length` is the full selection.
        String doc = "AAAAAAAAAA" + "xxxxxx" + "BBBBBBBBBB"; // needle "AAAA…" at offset 0, real span longer
        String needle = "AAAAAAAAAA"; // 10 chars (stands in for a capped-at-MAX_TEXT text)
        int fullLength = 16; // the real selection was "AAAAAAAAAAxxxxxx" (10 + 6)
        // Level 1 (saved offset still holds the needle):
        int[] exact = NoteAnchors.relocate(doc, 0, 16, needle, "", "", fullLength);
        assertArrayEquals(new int[] {0, 16}, exact, "span extends to start + full length, not start + needle");
        // Occurrence-search path (saved offset wrong) reaches the same full span.
        int[] found = NoteAnchors.relocate(doc, 99, 115, needle, "", "", fullLength);
        assertArrayEquals(new int[] {0, 16}, found);
    }

    @Test
    void relocateClampsTheSpanToTheDocumentAndFallsBackForOldNotes() {
        String doc = "abcdef";
        // A length past the end of the document is clamped.
        assertArrayEquals(new int[] {2, 6}, NoteAnchors.relocate(doc, 2, 6, "cd", "", "", 100));
        // An old note (length 0, or shorter than the needle) falls back to the needle length.
        assertArrayEquals(new int[] {2, 4}, NoteAnchors.relocate(doc, 2, 4, "cd", "", "", 0));
    }
}
