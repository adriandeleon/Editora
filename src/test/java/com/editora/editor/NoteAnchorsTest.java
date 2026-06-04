package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

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
        assertNull(NoteAnchors.relocate("totally different content", 0, 4, "missing", "", ""),
                "no occurrence → orphan (null)");
    }

    @Test
    void relocateEmptySelectionKeepsClampedPosition() {
        int[] r = NoteAnchors.relocate("abc", 99, 99, "", "", "");
        assertArrayEquals(new int[] {3, 3}, r, "empty needle (blank line) → clamped position, not orphan");
    }
}
