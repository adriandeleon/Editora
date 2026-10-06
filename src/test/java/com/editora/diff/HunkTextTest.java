package com.editora.diff;

import java.util.List;

import com.editora.diff.DiffModels.Row;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What an index side holds after a hunk is staged into it: its end of file follows its last line's origin. */
class HunkTextTest {

    private static String stage(String index, String working, List<Row> rows, int start, int end) {
        return HunkText.apply(rows, start, end, false, DiffText.parse(index), DiffText.parse(working));
    }

    @Test
    void aStagedLastLineBringsItsOwnEndOfFile() {
        List<Row> rows = List.of(Row.equal("a", 1, 1), Row.equal("b", 2, 2), Row.added("c", 3));
        assertEquals("a\nb\nc", stage("a\nb\n", "a\nb\nc", rows, 2, 3), "the working file's last line is unterminated");
        assertEquals("a\nb\nc\n", stage("a\nb", "a\nb\nc\n", rows, 2, 3), "and here it is terminated");
    }

    @Test
    void aHunkAwayFromTheEndLeavesTheIndexEndOfFileAlone() {
        List<Row> rows = List.of(Row.modified("a", 1, "A", 1, null, null), Row.equal("b", 2, 2));
        assertEquals("A\nb\n", stage("a\nb\n", "A\nb", rows, 0, 1));
        assertEquals("A\nb", stage("a\nb", "A\nb\n", rows, 0, 1));
    }

    @Test
    void stagingIntoAnEmptyIndexSideUsesTheWorkingFilesEndOfFile() {
        List<Row> rows = List.of(Row.added("a", 1), Row.added("b", 2));
        assertEquals("a\nb\n", stage("", "a\nb\n", rows, 0, 2));
        assertEquals("a\n", stage("", "a\nb\n", rows, 0, 1), "one line of a new file");
    }

    @Test
    void removingEveryLineLeavesAnEmptyFileNotALoneNewline() {
        List<Row> rows = List.of(Row.removed("x", 1), Row.removed("y", 2));
        assertEquals("", stage("x\ny\n", "", rows, 0, 2));
        assertEquals("y\n", stage("x\ny\n", "", rows, 0, 1), "what stays keeps the index's own terminator");
    }

    @Test
    void unstagingRewritesTheRightSide() {
        List<Row> rows = List.of(Row.equal("a", 1, 1), Row.added("b", 2));
        // HEAD "a" (unterminated) on the left, index "a\nb\n" on the right; the hunk is taken out of the index.
        assertEquals(
                "a\n",
                HunkText.apply(rows, 1, 2, true, DiffText.parse("a\nb\n"), DiffText.parse("a")),
                "the remaining last line is the index's own, so it keeps the index's terminator");
    }

    @Test
    void aRangeCoversEveryChangeOnlyWhenNothingElseDiffers() {
        List<Row> rows = List.of(Row.modified("a", 1, "A", 1, null, null), Row.equal("b", 2, 2), Row.added("c", 3));
        assertFalse(HunkText.coversEveryChange(rows, 0, 1));
        assertTrue(HunkText.coversEveryChange(rows, 0, 3));
        assertTrue(HunkText.coversEveryChange(List.of(Row.added("a", 1)), 0, 1));
        assertFalse(
                HunkText.coversEveryChange(List.of(Row.equal("a ", 1, "a", 1), Row.added("b", 2)), 1, 2),
                "a row shown as equal but spelled differently is a difference outside the range");
    }
}
