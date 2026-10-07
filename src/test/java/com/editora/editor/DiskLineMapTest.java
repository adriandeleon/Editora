package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Git reports change bars and blame by line of the file on disk; {@link DiskLineMap} is what keeps them on
 * the right buffer line while the buffer has unsaved edits.
 */
class DiskLineMapTest {

    private static int[] diskLines(DiskLineMap map, int count) {
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = map.diskLine(i);
        }
        return out;
    }

    @Test
    void startsAsTheIdentityAndAnEditInsideALineChangesNothing() {
        DiskLineMap map = new DiskLineMap();
        assertFalse(map.edit(3, false, "", "typed"), "no line break: nothing moved");
        assertFalse(map.edit(3, false, "old", "new"));
        assertTrue(map.identity());
        assertEquals(7, map.diskLine(7));
    }

    /** The reported defect: a line typed above shifts every annotation below instead of leaving it behind. */
    @Test
    void aNewLineShiftsEverythingBelowItAndHasNoDiskLineItself() {
        DiskLineMap map = new DiskLineMap();
        assertTrue(map.edit(1, false, "", "\n"), "Enter at the end of line 1");
        // Buffer: 0, 1, <new>, 2, 3 …
        assertEquals(1, map.diskLine(1), "the line Enter was pressed on keeps its annotation");
        assertEquals(DiskLineMap.UNKNOWN, map.diskLine(2), "the typed line belongs to no commit");
        assertEquals(2, map.diskLine(3));
        assertEquals(99, map.diskLine(100), "far below the stored part, still shifted by one");
    }

    @Test
    void enterAtTheStartOfALinePushesThatLineDownIntact() {
        DiskLineMap map = new DiskLineMap();
        map.edit(4, true, "", "\n");
        assertEquals(DiskLineMap.UNKNOWN, map.diskLine(4), "the blank line that appeared");
        assertEquals(4, map.diskLine(5), "the text that was on line 4, now one lower");
        assertEquals(5, map.diskLine(6));
    }

    @Test
    void deletingLinesPullsTheRestUp() {
        DiskLineMap map = new DiskLineMap();
        map.edit(2, true, "two\nthree\n", ""); // lines 2 and 3 removed whole
        assertEquals(1, map.diskLine(1));
        assertEquals(4, map.diskLine(2), "disk line 4 is now buffer line 2");
        assertEquals(5, map.diskLine(3));
    }

    @Test
    void joiningTwoLinesKeepsTheFirstOnesAnnotation() {
        DiskLineMap map = new DiskLineMap();
        map.edit(2, false, "\n", ""); // Delete at the end of line 2
        assertEquals(2, map.diskLine(2));
        assertEquals(4, map.diskLine(3));
    }

    @Test
    void undoingAnInsertedLineRestoresTheIdentityMapping() {
        DiskLineMap map = new DiskLineMap();
        map.edit(1, false, "", "\n");
        map.edit(1, false, "\n", "");
        assertEquals("[0, 1, 2, 3, 4]", java.util.Arrays.toString(diskLines(map, 5)));

        map.edit(3, true, "", "\n");
        map.edit(3, true, "\n", "");
        assertEquals("[0, 1, 2, 3, 4]", java.util.Arrays.toString(diskLines(map, 5)));
    }

    /**
     * Move Line Down is one replacement of two whole lines by the same lines swapped. Neither is the line it
     * was, and saying so is the point: an annotation that stayed would name the wrong commit.
     */
    @Test
    void rewrittenLinesAreUnknownRatherThanSomeOtherLinesAnnotation() {
        DiskLineMap map = new DiskLineMap();
        map.edit(1, true, "one\ntwo", "two\none");
        assertEquals(0, map.diskLine(0));
        assertEquals(DiskLineMap.UNKNOWN, map.diskLine(1));
        assertEquals(DiskLineMap.UNKNOWN, map.diskLine(2));
        assertEquals(3, map.diskLine(3), "lines below a same-size rewrite do not move");
    }

    @Test
    void pastingWholeLinesAtALineStartKeepsThatLineBelowThePaste() {
        DiskLineMap map = new DiskLineMap();
        map.edit(1, true, "", "a\nb\n");
        assertEquals("[0, -1, -1, 1, 2]", java.util.Arrays.toString(diskLines(map, 5)));
    }

    @Test
    void editsCompose() {
        DiskLineMap map = new DiskLineMap();
        map.edit(5, false, "", "\n"); // 0..5, new, 6..
        map.edit(0, true, "zero\n", ""); // drop line 0
        // Buffer: 1,2,3,4,5,new,6,7
        assertEquals("[1, 2, 3, 4, 5, -1, 6, 7]", java.util.Arrays.toString(diskLines(map, 8)));
        map.reset();
        assertTrue(map.identity());
        assertEquals(6, map.diskLine(6));
    }

    @Test
    void aWholeDocumentRewriteGivesUpUntilReset() {
        DiskLineMap map = new DiskLineMap();
        String huge = "x\n".repeat(DiskLineMap.MAX_INSERTED_LINES + 1);
        assertTrue(map.edit(0, true, "a\nb\n", huge));
        assertTrue(map.lost());
        assertEquals(DiskLineMap.UNKNOWN, map.diskLine(0));
        assertEquals(DiskLineMap.UNKNOWN, map.diskLine(50_000));
        assertFalse(map.edit(3, false, "", "\n"), "nothing is followed while lost");

        map.reset();
        assertEquals(12, map.diskLine(12));
    }
}
