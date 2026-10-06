package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoHistoryBudgetTest {

    private static String text(char c, int length) {
        return String.valueOf(c).repeat(length);
    }

    @Test
    void theLeastRecentlyEditedBufferGivesUpItsOldestCheckpointsFirst() {
        UndoHistoryBudget budget = new UndoHistoryBudget(1000);
        UndoHistory idle = new UndoHistory(budget);
        UndoHistory active = new UndoHistory(budget);
        idle.add(text('a', 300), 0, 1);
        idle.add(text('b', 300), 0, 2);
        active.add(text('c', 300), 0, 3);
        assertEquals(900, budget.retainedChars());

        active.add(text('d', 300), 0, 4); // 1200: over by 200 -> the idle buffer's oldest goes
        assertEquals(1, idle.size());
        assertEquals(text('b', 300), idle.entriesNewestFirst().getFirst().text());
        assertEquals(2, active.size(), "the buffer being edited keeps its steps");
        assertEquals(900, budget.retainedChars());

        active.add(text('e', 300), 0, 5); // over again -> the idle buffer is emptied before the active one
        assertEquals(0, idle.size());
        assertEquals(3, active.size());
    }

    @Test
    void theActiveBufferLosesOldStepsOnlyWhenNoOtherHasAny_andNeverItsNewest() {
        UndoHistoryBudget budget = new UndoHistoryBudget(1000);
        UndoHistory only = new UndoHistory(budget);
        only.add(text('a', 600), 0, 1);
        only.add(text('b', 600), 0, 2);
        assertEquals(1, only.size());
        assertEquals(text('b', 600), only.entriesNewestFirst().getFirst().text());

        only.add(text('c', 5000), 0, 3); // alone over the budget: still kept, it is the state just left
        assertEquals(1, only.size());
        assertEquals(5000, budget.retainedChars());
    }

    @Test
    void recencyFollowsEditsNotCreationOrder() {
        UndoHistoryBudget budget = new UndoHistoryBudget(1000);
        UndoHistory first = new UndoHistory(budget);
        UndoHistory second = new UndoHistory(budget);
        first.add(text('a', 400), 0, 1);
        second.add(text('b', 400), 0, 2);
        first.add(text('c', 100), 0, 3); // 'first' is now the more recently edited; total 900

        UndoHistory third = new UndoHistory(budget);
        third.add(text('d', 300), 0, 4); // 1200 -> 'second' (least recent) pays
        assertEquals(0, second.size());
        assertEquals(2, first.size());
        assertEquals(1, third.size());
    }

    @Test
    void aClearedHistoryNoLongerCounts() {
        UndoHistoryBudget budget = new UndoHistoryBudget(1000);
        UndoHistory closed = new UndoHistory(budget);
        UndoHistory open = new UndoHistory(budget);
        closed.add(text('a', 900), 0, 1);
        closed.clear(); // its tab was closed
        assertEquals(0, budget.retainedChars());
        open.add(text('b', 500), 0, 2);
        open.add(text('c', 400), 0, 3);
        assertEquals(2, open.size(), "nothing evicted for text that is no longer held");
    }

    @Test
    void theAppWideBudgetIsLooserThanOneBufferButTighterThanAFew() {
        assertTrue(UndoHistory.MAX_RETAINED_CHARS < 64_000_000L);
        assertTrue(5L * UndoHistory.MAX_RETAINED_CHARS > 64_000_000L);
    }
}
