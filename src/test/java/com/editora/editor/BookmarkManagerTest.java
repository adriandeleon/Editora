package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.NavigableMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.editora.config.Bookmark;

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
        assertEquals(1, out.size());          // the line-12 bookmark was deleted
        assertTrue(out.containsKey(12));       // the line-16 bookmark shifted up by 4
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
}
