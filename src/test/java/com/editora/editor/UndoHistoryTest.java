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
    void evictsBeyondTheCharBudgetEvenWhenUnderTheCountCap() {
        UndoHistory h = new UndoHistory();
        String big = "x".repeat(UndoHistory.MAX_RETAINED_CHARS / 4);
        for (int i = 0; i < 10; i++) {
            h.add(big + i, 0, i); // ten of these is 2.5x the budget, but only 10 of 50 checkpoints
        }
        assertTrue(h.retainedChars() <= UndoHistory.MAX_RETAINED_CHARS, "budget exceeded: " + h.retainedChars());
        List<UndoHistory.Checkpoint> e = h.entriesNewestFirst();
        assertTrue(e.size() < 10, "nothing was evicted: " + e.size());
        assertEquals(big + 9, e.get(0).text()); // the newest survives; the oldest went first
    }

    @Test
    void keepsTheNewestCheckpointEvenWhenItAloneExceedsTheBudget() {
        UndoHistory h = new UndoHistory();
        h.add("small", 0, 1);
        String huge = "y".repeat(UndoHistory.MAX_RETAINED_CHARS + 1);
        h.add(huge, 0, 2);
        List<UndoHistory.Checkpoint> e = h.entriesNewestFirst();
        assertEquals(1, e.size()); // the small one is evicted, the newest is never dropped
        assertEquals(huge, e.get(0).text());
    }

    @Test
    void smallDocumentsStillGetTheFullCountOfCheckpoints() {
        UndoHistory h = new UndoHistory();
        for (int i = 0; i < UndoHistory.MAX; i++) {
            h.add("line " + i, 0, i); // ordinary edits: the char budget must not bind here
        }
        assertEquals(UndoHistory.MAX, h.entriesNewestFirst().size());
    }

    @Test
    void retainedCharsTracksTheDequeAcrossEvictionAndClear() {
        UndoHistory h = new UndoHistory();
        h.add("abc", 0, 1);
        h.add("de", 0, 2);
        assertEquals(5, h.retainedChars());
        h.clear();
        assertEquals(0, h.retainedChars());
        assertTrue(h.isEmpty());
    }

    @Test
    void clampKeepsCaretInRange() {
        assertEquals(0, UndoHistory.clamp(-5, 10));
        assertEquals(10, UndoHistory.clamp(99, 10));
        assertEquals(3, UndoHistory.clamp(3, 10));
    }
}
