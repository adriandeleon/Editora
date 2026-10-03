package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.IntFunction;

import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared line arithmetic for bookmarks and breakpoints. The deletion and insertion cases are pinned per
 * manager ({@link BookmarkManagerTest}, {@link BreakpointManagerTest}); these cover the rewrite — newlines
 * removed <em>and</em> inserted in one change — which used to delete every mark inside the replaced span.
 */
class LineMarksTest {

    private static NavigableMap<Integer, Bookmark> bookmarks(int... lines) {
        NavigableMap<Integer, Bookmark> m = new TreeMap<>();
        for (int l : lines) {
            m.put(l, new Bookmark(l, "note" + l, "text" + l));
        }
        return m;
    }

    private static IntFunction<String> doc(String... lines) {
        return i -> i >= 0 && i < lines.length ? lines[i] : "";
    }

    @Test
    void aRangedReplaceWithTheSameLineCountKeepsEveryMarkOnItsLine() {
        // What in-file Replace All does: one ranged replace from the first match (mid line 1) to the last
        // (mid line 5), 4 newlines out and 4 back in. The bookmarks on lines 2 and 3 used to be deleted.
        NavigableMap<Integer, Bookmark> in = bookmarks(0, 2, 3, 5, 8);
        assertEquals(in, BookmarkManager.shift(in, 1, false, 4, 4, 10));
    }

    @Test
    void aWholeDocumentReplaceWithTheSameLineCountKeepsEveryMark() {
        // replaceText(0, length, …) on a 6-line document: position 0 is a line start, 5 newlines each way.
        NavigableMap<Integer, Bookmark> in = bookmarks(0, 2, 3, 5);
        assertEquals(in, BookmarkManager.shift(in, 0, true, 5, 5, 6));
    }

    @Test
    void breakpointsShareTheSameRule() {
        NavigableMap<Integer, Breakpoint> in = new TreeMap<>();
        in.put(2, Breakpoint.plain(2, "a();"));
        in.put(3, new Breakpoint(3, "x > 1", "", false, "b();"));
        assertEquals(in, BreakpointManager.shift(in, 1, false, 4, 4, 10), "condition and enabled state survive too");
    }

    @Test
    void aRewriteThatAddsLinesFollowsTheStoredLineText() {
        // Lines 0..3 rewritten as 6 lines (a formatter splitting two of them); the marked lines' text now
        // sits further down. Line 4 ("tail") is below the span and shifts by the net delta.
        NavigableMap<Integer, Bookmark> in = bookmarks(1, 2, 4);
        NavigableMap<Integer, Bookmark> out = LineMarks.shift(
                in, KIND, 0, true, 4, 6, 7, doc("text0", "split", "  text1", "split", "text2  ", "text3", "tail"));
        assertEquals(List.of(2, 4, 6), new ArrayList<>(out.keySet()));
        assertEquals("note1", out.get(2).note());
        assertEquals("note2", out.get(4).note());
        assertEquals("note4", out.get(6).note());
        assertEquals(2, out.get(2).line(), "the record's own line follows the key");
    }

    @Test
    void aRewriteThatLosesTheTextClampsToTheNearestSurvivingLine() {
        // Lines 0..5 replaced by two lines whose text matches nothing: the marks on 1 and 4 are kept on the
        // nearest free lines of the new span rather than deleted.
        NavigableMap<Integer, Bookmark> out =
                LineMarks.shift(bookmarks(1, 4), KIND, 0, true, 6, 2, 3, doc("x", "y", "tail"));
        assertEquals(List.of(1, 2), new ArrayList<>(out.keySet()));
        assertEquals("note1", out.get(1).note());
        assertEquals("note4", out.get(2).note());
    }

    @Test
    void aTextMatchIsNeverTakenByAClampedMark() {
        // The mark from line 1 has lost its text and would clamp onto line 1 — exactly where the mark from
        // line 3 finds its own text. Matches are placed first, so both survive on distinct lines.
        NavigableMap<Integer, Bookmark> out =
                LineMarks.shift(bookmarks(1, 3), KIND, 0, true, 5, 2, 3, doc("other", "text3", "tail"));
        assertEquals(2, out.size());
        assertEquals("note3", out.get(1).note());
        assertEquals("note1", out.get(2).note(), "the nearest line still free");
    }

    @Test
    void marksAreDroppedOnlyWhenTheNewSpanHasFewerLinesThanMarks() {
        // Four marked lines collapse into a two-line span (lines 0..1; line 2 is the survivor below).
        NavigableMap<Integer, Bookmark> out =
                LineMarks.shift(bookmarks(0, 1, 2, 3), KIND, 0, true, 5, 1, 2, doc("a", "tail"));
        assertEquals(2, out.size());
    }

    @Test
    void aPureDeletionStillDropsTheMarksOfTheDeletedLines() {
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(bookmarks(12, 13, 16), 12, true, 4, 0, 100);
        assertEquals(List.of(12), new ArrayList<>(out.keySet()));
        assertEquals("note16", out.get(12).note());
    }

    @Test
    void theJoinLineOfAMidLineRewriteFollowsItsContent() {
        // Replace from mid line 1 to mid line 3 with a text of one newline: line 3's tail lands on line 2.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(bookmarks(1, 3, 6), 1, false, 2, 1, 9);
        assertEquals(List.of(1, 2, 5), new ArrayList<>(out.keySet()));
        assertEquals("note3", out.get(2).note());
    }

    // --- narrowing ---------------------------------------------------------------------------------

    @Test
    void holdSplitsAroundTheRegionAndRebasesTheInside() {
        NavigableMap<Integer, Bookmark> inside = new TreeMap<>();
        LineMarks.Held<Bookmark> held = LineMarks.hold(bookmarks(0, 2, 3, 7).values(), KIND, 2, 4, inside);
        assertEquals(List.of(0, 1), new ArrayList<>(inside.keySet()), "lines 2 and 3, relative to the region");
        assertEquals("note2", inside.get(0).note());
        assertEquals(1, held.before().size());
        assertEquals(1, held.after().size());
        assertEquals(3, held.regionLines());
    }

    @Test
    void releaseAfterAnUneditedRegionRestoresEveryMark() {
        NavigableMap<Integer, Bookmark> all = bookmarks(0, 2, 3, 7);
        NavigableMap<Integer, Bookmark> inside = new TreeMap<>();
        LineMarks.Held<Bookmark> held = LineMarks.hold(all.values(), KIND, 2, 4, inside);
        assertEquals(all, LineMarks.release(held, inside.values(), KIND, 3));
    }

    @Test
    void releaseShiftsTheMarksBelowByTheLinesTheRegionGained() {
        NavigableMap<Integer, Bookmark> inside = new TreeMap<>();
        LineMarks.Held<Bookmark> held = LineMarks.hold(bookmarks(0, 3, 7).values(), KIND, 2, 4, inside);
        // Two lines were added above the region's mark while narrowed: it sits on region line 3 now.
        NavigableMap<Integer, Bookmark> edited = BookmarkManager.shift(inside, 0, true, 0, 2, 5);
        NavigableMap<Integer, Bookmark> out = LineMarks.release(held, edited.values(), KIND, 5);
        assertEquals(List.of(0, 5, 9), new ArrayList<>(out.keySet()));
        assertEquals("note3", out.get(5).note());
        assertEquals("note7", out.get(9).note());
        assertTrue(out.values().stream().allMatch(b -> out.get(b.line()) == b), "each record's line is its key");
    }

    private static final LineMarks.Kind<Bookmark> KIND = new LineMarks.Kind<>() {
        @Override
        public int line(Bookmark mark) {
            return mark.line();
        }

        @Override
        public String lineText(Bookmark mark) {
            return mark.lineText();
        }

        @Override
        public Bookmark withLine(Bookmark mark, int line) {
            return mark.withLine(line);
        }
    };
}
