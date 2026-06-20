package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoHistoryTest {

    @Test
    void addsChangedStatesNewestFirstAndSkipsDuplicates() {
        UndoHistory h = new UndoHistory();
        assertTrue(h.isEmpty());
        assertTrue(h.add("a", 1, 100));
        assertFalse(h.add("a", 1, 101)); // unchanged text → no checkpoint
        assertTrue(h.add("ab", 2, 102));
        List<UndoHistory.Checkpoint> e = h.entriesNewestFirst();
        assertEquals(2, e.size());
        assertEquals("ab", e.get(0).text()); // newest first
        assertEquals("a", e.get(1).text());
    }

    @Test
    void evictsOldestBeyondCap() {
        UndoHistory h = new UndoHistory();
        for (int i = 0; i <= UndoHistory.MAX; i++) {
            h.add("v" + i, 0, i);
        }
        List<UndoHistory.Checkpoint> e = h.entriesNewestFirst();
        assertEquals(UndoHistory.MAX, e.size());
        assertEquals("v" + UndoHistory.MAX, e.get(0).text()); // newest kept
        assertEquals("v1", e.get(e.size() - 1).text()); // v0 evicted
    }

    @Test
    void lineAtReturnsTheCaretLineStrippedAndCapped() {
        assertEquals("two", UndoHistory.lineAt("one\ntwo\nthree", 5)); // caret in the middle line
        assertEquals("one", UndoHistory.lineAt("one\ntwo", 0));
        assertEquals("", UndoHistory.lineAt("a\n\nb", 2)); // blank line
        assertTrue(UndoHistory.lineAt("x".repeat(200), 0).endsWith("…")); // capped
    }

    @Test
    void clampKeepsCaretInRange() {
        assertEquals(0, UndoHistory.clamp(-5, 10));
        assertEquals(10, UndoHistory.clamp(99, 10));
        assertEquals(3, UndoHistory.clamp(3, 10));
    }
}
