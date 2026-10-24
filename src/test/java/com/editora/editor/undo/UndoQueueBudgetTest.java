package com.editora.editor.undo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UndoQueueBudgetTest {

    /** Entries are strings; an entry costs its length. The per-queue caps are loose: only the shared one binds. */
    private static BudgetedChangeQueue<String> queue(UndoQueueBudget shared) {
        return new BudgetedChangeQueue<>(300, 1_000_000, String::length, shared);
    }

    private static String text(char c, int length) {
        return String.valueOf(c).repeat(length);
    }

    @Test
    void theLeastRecentlyEditedDocumentGivesUpItsOldestEntriesFirst() {
        UndoQueueBudget budget = new UndoQueueBudget(1000);
        BudgetedChangeQueue<String> idle = queue(budget);
        BudgetedChangeQueue<String> active = queue(budget);
        idle.push(text('a', 300));
        idle.push(text('b', 300));
        idle.push(text('c', 100));
        active.push(text('d', 300));
        assertEquals(1000, budget.retainedChars());

        active.push(text('e', 300)); // 1300: over by 300 -> the idle document's oldest goes
        assertEquals(2, idle.size());
        assertEquals(text('c', 100), idle.prev());
        assertEquals(text('b', 300), idle.prev());
        assertFalse(idle.hasPrev());
        idle.next();
        idle.next();
        assertEquals(2, active.size(), "the document being edited keeps its steps");
        assertEquals(1000, budget.retainedChars());

        active.push(text('f', 300)); // over again -> the idle document is taken down to one step
        assertEquals(1, idle.size());
        assertEquals(text('c', 100), idle.peekPrev());
        assertEquals(3, active.size());
        assertEquals(1000, budget.retainedChars());
    }

    @Test
    void noQueueIsTakenBelowOneUndoStep_thenTheActiveQueueGivesUpItsOwnOldest() {
        UndoQueueBudget budget = new UndoQueueBudget(1000);
        BudgetedChangeQueue<String> idle = queue(budget);
        BudgetedChangeQueue<String> active = queue(budget);
        idle.push(text('a', 500));
        active.push(text('b', 400));
        active.push(text('c', 400)); // 1300 -> the idle queue has one step and keeps it; the active loses 'b'
        assertEquals(1, idle.size());
        assertEquals(1, active.size());
        assertEquals(text('c', 400), active.peekPrev());
        assertEquals(900, budget.retainedChars());

        active.push(text('d', 5000)); // nothing left to take: over budget, every queue at its one newest step
        assertEquals(1, idle.size());
        assertEquals(1, active.size());
        assertEquals(5500, budget.retainedChars());
    }

    @Test
    void redoEntriesAreNeverTakenFromAnotherQueue() {
        UndoQueueBudget budget = new UndoQueueBudget(1000);
        BudgetedChangeQueue<String> undone = queue(budget);
        BudgetedChangeQueue<String> active = queue(budget);
        undone.push(text('a', 300));
        undone.push(text('b', 300));
        undone.prev();
        undone.prev(); // both are redo entries now: the position is at 0
        active.push(text('c', 600)); // 1200 over budget, but 'undone' has no undo step to give up
        assertEquals(2, undone.size());
        assertFalse(undone.hasPrev());
        assertTrue(undone.hasNext());
        assertEquals(1, active.size());
    }

    @Test
    void recencyFollowsEditsNotCreationOrder_andQueuePositionsStayValid() {
        UndoQueueBudget budget = new UndoQueueBudget(1000);
        BudgetedChangeQueue<String> first = queue(budget);
        BudgetedChangeQueue<String> second = queue(budget);
        first.push(text('a', 300));
        first.push(text('b', 100));
        second.push(text('c', 300));
        second.push(text('d', 100));
        first.push(text('e', 100)); // 'first' is now the more recently edited; total 900
        var secondTop = second.getCurrentPosition();

        BudgetedChangeQueue<String> third = queue(budget);
        third.push(text('f', 300)); // 1200 -> 'second' (least recent) pays with 'c'
        assertEquals(1, second.size());
        assertEquals(3, first.size());
        assertTrue(secondTop.isValid(), "the position after the surviving entry is still the queue's top");
        assertEquals(text('d', 100), second.prev());
        assertFalse(second.hasPrev());
    }

    @Test
    void aQueueThatForgotItsHistoryCountsNothing() {
        UndoQueueBudget budget = new UndoQueueBudget(1000);
        BudgetedChangeQueue<String> q = queue(budget);
        q.push(text('a', 600));
        q.forgetHistory();
        assertEquals(0, budget.retainedChars());
        BudgetedChangeQueue<String> other = queue(budget);
        other.push(text('b', 900));
        assertEquals(900, budget.retainedChars());
    }
}
