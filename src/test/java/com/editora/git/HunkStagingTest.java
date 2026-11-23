package com.editora.git;

import java.util.List;

import com.editora.git.DiffParser.Hunk;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Staging one change from the editor: picking its unstaged hunks and applying only those to the index. */
class HunkStagingTest {

    private static Hunk hunk(int oldStart, int oldCount, int newStart, int newCount) {
        return new Hunk(
                oldStart,
                oldCount,
                newStart,
                newCount,
                java.util.Collections.nCopies(oldCount, "-"),
                java.util.Collections.nCopies(newCount, "+"),
                false,
                false);
    }

    /** A hunk with the lines it really removes from {@code index} and adds from {@code working}. */
    private static Hunk real(String index, String working, int oldStart, int oldCount, int newStart, int newCount) {
        List<String> old = index.isEmpty() ? List.of() : List.of(index.split("\n", -1));
        List<String> now = working.isEmpty() ? List.of() : List.of(working.split("\n", -1));
        int at = oldCount == 0 ? oldStart : oldStart - 1;
        int from = newCount == 0 ? newStart : newStart - 1;
        return new Hunk(
                oldStart,
                oldCount,
                newStart,
                newCount,
                old.subList(at, at + oldCount),
                now.subList(from, from + newCount),
                false,
                false);
    }

    @Test
    void onlyTheChosenHunkReachesTheIndex() {
        String index = "a\nb\nc\nd\ne\n";
        String working = "a\nB\nc\nd\nE\nf\n";
        Hunk first = real(index, working, 2, 1, 2, 1);
        Hunk second = real(index, working, 5, 1, 5, 2);
        assertEquals("a\nB\nc\nd\ne\n", HunkStaging.apply(index, working, List.of(first)));
        assertEquals("a\nb\nc\nd\nE\nf\n", HunkStaging.apply(index, working, List.of(second)));
        assertEquals(working, HunkStaging.apply(index, working, List.of(first, second)));
    }

    @Test
    void additionsAndDeletionsUseGitsLineBeforeTheGap() {
        assertEquals(
                "a\nnew\nb\n",
                HunkStaging.apply("a\nb\n", "a\nnew\nb\n", List.of(real("a\nb\n", "a\nnew\nb\n", 1, 0, 2, 1))));
        assertEquals("top\na\n", HunkStaging.apply("a\n", "top\na\n", List.of(real("a\n", "top\na\n", 0, 0, 1, 1))));
        assertEquals(
                "a\nc\n", HunkStaging.apply("a\nb\nc\n", "a\nc\n", List.of(real("a\nb\nc\n", "a\nc\n", 2, 1, 1, 0))));
        assertEquals("", HunkStaging.apply("a\n", "", List.of(real("a\n", "", 1, 1, 0, 0))));
        assertEquals("a\n", HunkStaging.apply("", "a\n", List.of(real("", "a\n", 0, 0, 1, 1))));
    }

    @Test
    void theFinalNewlineFollowsTheLastLineThatWasStaged() {
        // The file's last line is staged without its newline…
        assertEquals("a\nb\nc", HunkStaging.apply("a\nb\n", "a\nb\nc", List.of(real("a\nb\n", "a\nb\nc", 2, 0, 3, 1))));
        // …but a hunk further up leaves the index's own ending alone.
        assertEquals("A\nb\n", HunkStaging.apply("a\nb\n", "A\nb", List.of(real("a\nb\n", "A\nb", 1, 1, 1, 1))));
        assertEquals("a\nb\n", HunkStaging.apply("a\nb", "a\nb\n", List.of(real("a\nb", "a\nb\n", 2, 1, 2, 1))));
        // Deleting the index's unterminated last line leaves a terminated one.
        assertEquals("a\n", HunkStaging.apply("a\nb", "a\n", List.of(real("a\nb", "a\n", 2, 1, 1, 0))));
    }

    @Test
    void aHunkThatNoLongerFitsIsRefused() {
        assertNull(HunkStaging.apply("a\n", "a\nb\n", List.of(hunk(5, 1, 2, 1))));
        assertNull(HunkStaging.apply("a\nb\n", "a\n", List.of(hunk(2, 1, 2, 3))));
    }

    @Test
    void aHunkWhoseLinesAreNoLongerThereIsRefused() {
        Hunk diffed = real("a\nb\nc\n", "a\nB\nc\n", 2, 1, 2, 1);
        assertEquals("a\nB\nc\n", HunkStaging.apply("a\nb\nc\n", "a\nB\nc\n", List.of(diffed)));
        // The index moved on (someone staged "x" there): same counts, other text.
        assertNull(HunkStaging.apply("a\nx\nc\n", "a\nB\nc\n", List.of(diffed)));
        // The working file moved on.
        assertNull(HunkStaging.apply("a\nb\nc\n", "a\nY\nc\n", List.of(diffed)));
    }

    @Test
    void aCrlfOrOtherCharsetDiffStillMatchesTheEditorsText() {
        Hunk crlf = new Hunk(2, 1, 2, 1, List.of("b\r"), List.of("B\r"), false, false);
        assertEquals("a\nB\nc\n", HunkStaging.apply("a\nb\nc\n", "a\nB\nc\n", List.of(crlf)));
        Hunk bom = new Hunk(1, 1, 1, 1, List.of("\uFEFFa"), List.of("\uFEFFA"), false, false);
        assertEquals("A\nb\n", HunkStaging.apply("a\nb\n", "A\nb\n", List.of(bom)));
        Hunk latin1 = new Hunk(1, 1, 1, 1, List.of("caf\uFFFD"), List.of("CAF\uFFFD"), false, false);
        assertEquals("CAFÉ\n", HunkStaging.apply("café\n", "CAFÉ\n", List.of(latin1)));
    }

    @Test
    void aChangeIsMadeOfTheUnstagedHunksThatTouchItsLines() {
        Hunk above = hunk(1, 1, 1, 1); // working line 0
        Hunk inside = hunk(5, 2, 5, 2); // working lines 4-5
        Hunk gap = hunk(9, 1, 8, 0); // a deletion above working line 8
        List<Hunk> unstaged = List.of(above, inside, gap);
        assertEquals(List.of(inside), HunkStaging.touching(unstaged, 3, 3), "lines 3-5");
        assertEquals(List.of(), HunkStaging.touching(unstaged, 1, 3), "lines 1-3: staged in full");
        assertEquals(List.of(gap), HunkStaging.touching(unstaged, 8, 0), "the deletion itself");
        assertEquals(List.of(gap), HunkStaging.touching(unstaged, 6, 2), "a change ending where the gap is");
        assertEquals(List.of(above), HunkStaging.touching(unstaged, 0, 1));
        assertEquals(List.of(), HunkStaging.touching(unstaged, 1, 1), "the line after a changed one");
    }
}
