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
    void aRangeInsideAReplacedSpanFollowsItsTextIntoTheReplacement() {
        // "beta" sat 6 chars into the replaced span; the replacement indents the line, moving it to 8.
        assertArrayEquals(new int[] {8, 12}, NoteAnchors.relocateInReplacement("  alpha beta gamma", 6, "beta", 4));
        // Two occurrences: the one nearest the old relative offset wins, in either direction.
        assertArrayEquals(new int[] {10, 14}, NoteAnchors.relocateInReplacement("beta x y, beta", 9, "beta", 4));
        assertArrayEquals(new int[] {0, 4}, NoteAnchors.relocateInReplacement("beta x y, beta", 2, "beta", 4));
        // A longer original selection keeps its full length, clamped to the replacement.
        assertArrayEquals(new int[] {2, 9}, NoteAnchors.relocateInReplacement("a beta gamma", 2, "beta", 7));
        assertArrayEquals(new int[] {2, 6}, NoteAnchors.relocateInReplacement("a beta", 2, "beta", 70));
        assertNull(NoteAnchors.relocateInReplacement("nothing here", 3, "beta", 4), "text gone → caller collapses");
        assertNull(NoteAnchors.relocateInReplacement("anything", 3, "", 0), "no text to follow");
    }

    @Test
    void offsetsAreMappedAcrossTheWideningSwap() {
        // The region [10, 20) was cut out and comes back 14 characters long.
        assertEquals(4, NoteAnchors.acrossRegion(4, 10, 20, 14), "before the region: unchanged");
        assertEquals(10, NoteAnchors.acrossRegion(10, 10, 20, 14), "at its start: unchanged");
        assertEquals(24, NoteAnchors.acrossRegion(20, 10, 20, 14), "at its end: moves with the growth");
        assertEquals(34, NoteAnchors.acrossRegion(30, 10, 20, 14), "after it: moves with the growth");
        assertEquals(13, NoteAnchors.acrossRegion(15, 10, 20, 3), "inside a region that shrank: clamped");
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

    // --- a needle too common to disambiguate orphans, not jumps up-file (#455) ----------------------

    @Test
    void aNeedleOccurringMoreThanTheCapOrphansInsteadOfJumpingUpFile() {
        // The needle occurs far more than MAX_OCCURRENCES, and the note lives past occurrence #MAX_OCCURRENCES.
        String doc = "X".repeat(NoteAnchors.MAX_OCCURRENCES + 1000);
        int savedStart = doc.length(); // past every collected occurrence; level-1 fails (out of range)
        assertNull(
                NoteAnchors.relocate(doc, savedStart, savedStart + 1, "X", "", "", 1),
                "too many occurrences to disambiguate → orphan, not a wrong up-file jump");
    }

    @Test
    void aManageableNumberOfOccurrencesStillRelocates() {
        // Exactly at/under the cap → still scored + relocated (no regression).
        String doc = "..word..word..word..";
        int[] r = NoteAnchors.relocate(doc, 14, 18, "word", "..", "..", 4);
        assertArrayEquals(new int[] {14, 18}, r, "the nearest matching occurrence is chosen");
    }

    // --- graded context + level-1 context gate for duplicated lines (#453) --------------------------

    @Test
    void gradedContextPrefersAPartialMatchOverPureProximity() {
        // Note captured prefix "P", suffix "Q"; the second "1" (offset 9) is nearer the saved offset but has
        // no matching context, while the first "1" (offset 1) matches the prefix. A graded score picks the
        // prefix match; the old boolean (which needed BOTH sides) scored both 0 and fell back to proximity.
        String doc = "P1RxxxxxZ1w"; // "1" at offsets 1 and 9
        int[] r = NoteAnchors.relocate(doc, 9, 10, "1", "P", "Q", 1);
        assertEquals(1, r[0], "a prefix-only context match beats the nearer no-context occurrence");
    }

    @Test
    void level1ContextGateRejectsAShiftedInIdenticalLine() {
        // savedStart (offset 1) holds the needle "}" but with the WRONG context (a/b); the real "}" — with the
        // captured context x/y — is at offset 7. Level 1 must not blindly keep the old offset.
        String doc = "a}b...x}y"; // "}" at offsets 1 and 7
        int[] r = NoteAnchors.relocate(doc, 1, 2, "}", "x", "y", 1);
        assertEquals(7, r[0], "level-1 must not keep a same-text occurrence whose stored context differs");
    }

    @Test
    void oldNotesWithNoContextStillKeepTheSavedOffset() {
        // A note with empty context (old data / no captured context) keeps today's level-1 behaviour.
        assertArrayEquals(new int[] {6, 10}, NoteAnchors.relocate("alpha beta gamma", 6, 10, "beta", "", "", 4));
    }
}
