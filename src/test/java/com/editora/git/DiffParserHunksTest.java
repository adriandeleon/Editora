package com.editora.git;

import java.util.List;

import com.editora.git.DiffParser.Hunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The exact lines of each {@code -U0} hunk — what Revert Hunk puts back and the change card shows. */
class DiffParserHunksTest {

    private static final String HEADER = "diff --git a/f b/f\nindex 1..2 100644\n--- a/f\n+++ b/f\n";

    @Test
    void aReplacementKeepsBothSidesAndItsLineNumbers() {
        List<Hunk> hunks =
                DiffParser.parseHunks(HEADER + "@@ -3,2 +3,3 @@ void f() {\n-old a\n-old b\n+new a\n+new b\n+c\n");
        assertEquals(1, hunks.size());
        Hunk h = hunks.get(0);
        assertEquals(List.of("old a", "old b"), h.removed());
        assertEquals(List.of("new a", "new b", "c"), h.added());
        assertEquals(ChangeType.MODIFIED, h.type());
        assertEquals(2, h.markerLine());
        assertEquals(3, h.markerCount());
    }

    @Test
    void anAdditionAndADeletionHaveOneEmptySide() {
        List<Hunk> hunks = DiffParser.parseHunks(HEADER + "@@ -2,0 +3 @@\n+added\n@@ -9,2 +9,0 @@\n-gone 1\n-gone 2\n");
        assertEquals(ChangeType.ADDED, hunks.get(0).type());
        assertEquals(List.of(), hunks.get(0).removed());
        assertEquals(2, hunks.get(0).markerLine());
        Hunk deletion = hunks.get(1);
        assertEquals(ChangeType.DELETED, deletion.type());
        assertEquals(List.of("gone 1", "gone 2"), deletion.removed());
        assertEquals(9, deletion.markerLine(), "the line below the gap");
        assertEquals(1, deletion.markerCount());
    }

    /** Lines are counted from the header, so a removed "-- x" (shown as "--- x") is a line, not a file header. */
    @Test
    void bodyLinesThatLookLikeHeadersAreLines() {
        List<Hunk> hunks = DiffParser.parseHunks(HEADER + "@@ -1,2 +1,2 @@\n--- a comment\n-@@ -1 +1 @@\n+++i;\n+x\n");
        assertEquals(1, hunks.size());
        assertEquals(List.of("-- a comment", "@@ -1 +1 @@"), hunks.get(0).removed());
        assertEquals(List.of("++i;", "x"), hunks.get(0).added());
    }

    @Test
    void theNoNewlineMarkerIsAttributedToTheSideItFollows() {
        Hunk oldSide = DiffParser.parseHunks(HEADER + "@@ -2 +2,2 @@\n-b\n\\ No newline at end of file\n+b\n+c\n")
                .get(0);
        assertTrue(oldSide.oldUnterminated());
        assertFalse(oldSide.newUnterminated());
        Hunk newSide = DiffParser.parseHunks(HEADER + "@@ -2 +2 @@\n-b\n+c\n\\ No newline at end of file\n")
                .get(0);
        assertFalse(newSide.oldUnterminated());
        assertTrue(newSide.newUnterminated());
        Hunk deletion = DiffParser.parseHunks(HEADER + "@@ -2 +1,0 @@\n-b\n\\ No newline at end of file\n")
                .get(0);
        assertTrue(deletion.oldUnterminated());
    }

    @Test
    void aCrlfFileKeepsItsCarriageReturnsAndBlankInputIsEmpty() {
        assertEquals(
                List.of("old\r"),
                DiffParser.parseHunks(HEADER + "@@ -1 +1 @@\n-old\r\n+new\r\n")
                        .get(0)
                        .removed());
        assertTrue(DiffParser.parseHunks("").isEmpty());
        assertTrue(DiffParser.parseHunks(null).isEmpty());
    }
}
