package com.editora.editor;

import java.util.NavigableMap;
import java.util.TreeMap;

import com.editora.config.Bookmark;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure line-shift arithmetic (no JavaFX toolkit needed). */
class BookmarkManagerTest {

    private static NavigableMap<Integer, Bookmark> map(int... lines) {
        NavigableMap<Integer, Bookmark> m = new TreeMap<>();
        for (int l : lines) {
            m.put(l, new Bookmark(l, "", "text" + l));
        }
        return m;
    }

    @Test
    void insertingLinesAboveShiftsBookmarksBelow() {
        // Insert 2 newlines in the middle of line 1; bookmarks strictly below shift down by 2.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(0, 1, 5), 1, false, 0, 2, 100);
        assertTrue(out.containsKey(0));
        assertTrue(out.containsKey(1)); // edit within line 1 (not at its start): bookmark stays
        assertTrue(out.containsKey(7)); // 5 + 2
        assertFalse(out.containsKey(5));
        assertEquals(7, out.get(7).line());
    }

    @Test
    void insertingAtLineStartMovesThatLinesBookmark() {
        // Insert 4 newlines at the START of line 12 (the user's case): the line's content — and its
        // bookmark — must move down to line 16. Forward gravity: bookmark follows the content.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(12), 12, true, 0, 4, 100);
        assertFalse(out.containsKey(12));
        assertTrue(out.containsKey(16));
        assertEquals("text12", out.get(16).lineText()); // same bookmark, moved with its content
    }

    @Test
    void deletingLinesDropsInsideRangeAndShiftsBelow() {
        // Delete lines 2..4 mid-line (startLine=2, removedNL=2): bookmark at 3 is inside -> dropped; 5 -> 3.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(1, 2, 3, 5), 2, false, 2, 0, 100);
        assertEquals(3, out.size());
        assertTrue(out.containsKey(1));
        assertTrue(out.containsKey(2)); // at the edit line, edit not at its start: kept
        assertTrue(out.containsKey(3)); // the former line-5 bookmark shifted here
        assertFalse(out.containsKey(5));
        assertEquals("text5", out.get(3).lineText()); // proves original line-3 was dropped
    }

    @Test
    void deletingWholeLinesFromLineStartDropsAndShifts() {
        // Select from start of line 12 through start of line 16 and delete (removedNL=4, atLineStart):
        // lines 12..15 are removed (bookmark at 12 dropped), and a bookmark at 16 moves up to 12.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(12, 16), 12, true, 4, 0, 100);
        assertEquals(1, out.size()); // the line-12 bookmark was deleted
        assertTrue(out.containsKey(12)); // the line-16 bookmark shifted up by 4
        assertEquals("text16", out.get(12).lineText());
    }

    @Test
    void replacingLinesUsesNetDelta() {
        // Replace 1 line with 3 lines at line 0 (removed 1 nl, inserted 3 nl): net +2 below.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(0, 4), 0, false, 1, 3, 100);
        assertTrue(out.containsKey(0)); // edit within line 0, not at start: kept
        assertTrue(out.containsKey(6)); // 4 + 2
    }

    @Test
    void shiftIsClampedToParagraphCount() {
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(5), 0, false, 0, 100, 10);
        assertTrue(out.containsKey(9)); // 5 + 100 clamped to maxLine = paragraphCount-1 = 9
    }

    @Test
    void intraLineEditUnchanged() {
        NavigableMap<Integer, Bookmark> in = map(2, 7);
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(in, 3, false, 0, 0, 100);
        assertEquals(in, out);
    }

    // --- reanchor(...): re-find a bookmark by its saved lineText after an external edit -------------

    /** A document as an array of raw line texts, exposed as the IntFunction reanchor expects. */
    private static java.util.function.IntFunction<String> doc(String... lines) {
        return i -> (i >= 0 && i < lines.length) ? lines[i] : "";
    }

    private static java.util.List<Bookmark> saved(Bookmark... bms) {
        return java.util.List.of(bms);
    }

    @Test
    void exactMatchKeepsStoredLine() {
        var lines = doc("alpha", "beta", "gamma");
        var out = BookmarkManager.reanchor(saved(new Bookmark(1, "n", "beta")), 3, lines, 2000);
        assertTrue(out.containsKey(1));
        assertEquals("n", out.get(1).note());
    }

    @Test
    void reanchorsWhenContentDriftedDown() {
        // The user's case: a line inserted above pushed "KeyDispatcher" from stored 7 down to 8.
        var lines = doc("a", "b", "c", "d", "e", "f", "g", "x", "KeyDispatcher");
        var out = BookmarkManager.reanchor(saved(new Bookmark(7, "", "KeyDispatcher")), 9, lines, 2000);
        assertFalse(out.containsKey(7));
        assertTrue(out.containsKey(8));
        assertEquals(8, out.get(8).line()); // bm.line() updated to the resolved line
    }

    @Test
    void reanchorsWhenContentDriftedUp() {
        // The real defect: stored 8 but the content sits on line 7 (a line was removed above).
        var lines = doc("a", "b", "c", "d", "e", "f", "g", "KeyDispatcher", "ConfigManager");
        var out = BookmarkManager.reanchor(saved(new Bookmark(8, "", "KeyDispatcher")), 9, lines, 2000);
        assertFalse(out.containsKey(8));
        assertTrue(out.containsKey(7));
    }

    @Test
    void picksNearestOccurrenceAmongDuplicates() {
        // stored=3, "import" at 0, 2, 4. Lines 2 (up) and 4 (down) are both distance 1; at each radius
        // the scan checks downward first, so the tie resolves to line 4.
        var lines = doc("import", "x", "import", "y", "import");
        var out = BookmarkManager.reanchor(saved(new Bookmark(3, "", "import")), 5, lines, 2000);
        assertTrue(out.containsKey(4));
        assertFalse(out.containsKey(3));
    }

    @Test
    void picksTrulyNearestOccurrence() {
        // stored=4, "import" at 0 and 3: line 3 (distance 1) wins over line 0 (distance 4).
        var lines = doc("import", "a", "b", "import", "c");
        var out = BookmarkManager.reanchor(saved(new Bookmark(4, "", "import")), 5, lines, 2000);
        assertTrue(out.containsKey(3));
    }

    @Test
    void emptyLineTextKeptAtClampedStoredLine() {
        var lines = doc("a", "", "c");
        var out = BookmarkManager.reanchor(saved(new Bookmark(1, "", "")), 3, lines, 2000);
        assertTrue(out.containsKey(1)); // can't match a blank/empty lineText — stay put
    }

    @Test
    void noMatchKeptAtClampedStoredLine() {
        var lines = doc("a", "b", "c");
        var out = BookmarkManager.reanchor(saved(new Bookmark(1, "", "vanished")), 3, lines, 2000);
        assertTrue(out.containsKey(1)); // content gone — keep at stored, don't drop
    }

    @Test
    void outOfRangeStoredLineClamped() {
        var lines = doc("a", "b", "c");
        var out = BookmarkManager.reanchor(saved(new Bookmark(99, "", "c")), 3, lines, 2000);
        assertTrue(out.containsKey(2)); // clamped to maxLine=2, which happens to match "c"
    }

    @Test
    void scanCapIsRespected() {
        var lines = doc("target", "a", "b", "c", "d"); // stored=4, "target" is 4 lines away
        var out = BookmarkManager.reanchor(saved(new Bookmark(4, "", "target")), 5, lines, 2);
        assertTrue(out.containsKey(4)); // within only 2 lines no match → kept at stored
        var out2 = BookmarkManager.reanchor(saved(new Bookmark(4, "", "target")), 5, lines, 10);
        assertTrue(out2.containsKey(0)); // wider scan finds it
    }

    @Test
    void strippingMatchesStoredStrippedText() {
        var lines = doc("a", "   beta  ", "c"); // raw line has surrounding whitespace
        var out = BookmarkManager.reanchor(saved(new Bookmark(1, "", "beta")), 3, lines, 2000);
        assertTrue(out.containsKey(1));
    }

    @Test
    void joiningALineUpwardKeepsTheBookmarkOnTheJoinLine() {
        // A bookmark on line 5; Backspace at column 0 of line 5 (mid-line delete of the newline: startLine=4,
        // atLineStart=false, removedNL=1) joins it onto line 4. Its content survives, so it must follow to 4.
        NavigableMap<Integer, Bookmark> out = BookmarkManager.shift(map(5), 4, false, 1, 0, 100);
        assertTrue(out.containsKey(4), "the bookmark follows its joined content to line 4 (not dropped)");
        assertFalse(out.containsKey(5), "line 5 no longer exists");
    }
}
