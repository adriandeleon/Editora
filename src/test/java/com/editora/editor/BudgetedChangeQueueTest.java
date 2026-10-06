package com.editora.editor;

import org.fxmisc.undo.impl.ChangeQueue.QueuePosition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetedChangeQueueTest {

    /** Entries are strings; an entry costs its length. */
    private static BudgetedChangeQueue<String> queue(int capacity, long budget) {
        return new BudgetedChangeQueue<>(capacity, budget, String::length);
    }

    @Test
    void walksBackAndForthLikeAnyUndoQueue() {
        BudgetedChangeQueue<String> q = queue(10, 1000);
        q.push("a");
        q.push("b", "c");
        assertFalse(q.hasNext());
        assertEquals("c", q.peekPrev());
        assertEquals("c", q.prev());
        assertEquals("b", q.prev());
        assertEquals("b", q.peekNext());
        assertEquals("b", q.next());
        assertEquals("b", q.prev());
        assertEquals("a", q.prev());
        assertFalse(q.hasPrev());
        assertTrue(q.hasNext());
    }

    @Test
    void aPushDropsTheRedoEntriesAndTheirCost() {
        BudgetedChangeQueue<String> q = queue(10, 1000);
        q.push("aaaa");
        q.push("bbbb");
        q.prev();
        q.push("c");
        assertEquals(2, q.size());
        assertEquals(5, q.retained());
        assertFalse(q.hasNext());
        assertEquals("c", q.prev());
        assertEquals("aaaa", q.prev());
    }

    @Test
    void theCountCapEvictsTheOldest() {
        BudgetedChangeQueue<String> q = queue(3, 1000);
        for (String s : new String[] {"1", "2", "3", "4", "5"}) {
            q.push(s);
        }
        assertEquals(3, q.size());
        assertEquals("5", q.prev());
        assertEquals("4", q.prev());
        assertEquals("3", q.prev());
        assertFalse(q.hasPrev());
    }

    @Test
    void theBudgetEvictsTheOldestUntilTheRestFits() {
        BudgetedChangeQueue<String> q = queue(100, 10);
        q.push("aaaa"); // 4
        q.push("bbbb"); // 8
        q.push("cccc"); // 12 -> evict "aaaa"
        assertEquals(2, q.size());
        assertEquals(8, q.retained());
        q.push("dddddddd"); // 16 -> evict "bbbb" (12), then "cccc" (8)
        assertEquals(1, q.size());
        assertEquals(8, q.retained());
        assertEquals("dddddddd", q.prev());
        assertFalse(q.hasPrev());
    }

    @Test
    void theNewestEntryIsKeptEvenWhenItAloneExceedsTheBudget() {
        BudgetedChangeQueue<String> q = queue(100, 10);
        q.push("a");
        q.push("x".repeat(50));
        assertEquals(1, q.size());
        assertTrue(q.hasPrev(), "the last edit can always be undone");
        assertEquals(50, q.prev().length());
        assertTrue(q.hasNext(), "and redone");
    }

    @Test
    void undoAndRedoStayConsistentAcrossEviction() {
        BudgetedChangeQueue<String> q = queue(100, 6);
        q.push("aa");
        q.push("bb");
        q.push("cc");
        q.prev(); // position before "cc"
        q.prev(); // position before "bb"
        q.push("dddd"); // drops bb+cc (redo), then aa+dddd = 6: fits
        assertEquals(2, q.size());
        q.push("ee"); // 8 -> evict "aa"
        assertEquals("ee", q.prev());
        assertEquals("dddd", q.prev());
        assertFalse(q.hasPrev());
        assertEquals("dddd", q.next());
        assertEquals("ee", q.next());
        assertFalse(q.hasNext());
    }

    @Test
    void forgetHistoryReleasesWhatWasBeforeThePosition() {
        BudgetedChangeQueue<String> q = queue(10, 1000);
        q.push("loaded document text");
        q.push("edit");
        q.prev();
        q.forgetHistory();
        assertEquals(1, q.size(), "only the redo entry is still held");
        assertEquals(4, q.retained());
        assertFalse(q.hasPrev());
        assertEquals("edit", q.next());
    }

    @Test
    void aPositionIsValidWhileTheChangeBeforeItIsHeld() {
        BudgetedChangeQueue<String> q = queue(2, 1000);
        QueuePosition start = q.getCurrentPosition();
        assertTrue(start.isValid());
        q.push("a");
        QueuePosition afterA = q.getCurrentPosition();
        assertNotEquals(start, afterA);
        assertTrue(start.isValid(), "undoing 'a' returns to it");
        q.prev();
        assertEquals(start, q.getCurrentPosition());
        q.next();
        assertEquals(afterA, q.getCurrentPosition());

        q.push("b");
        q.push("c"); // evicts "a": the start position is gone, the one after "a" is now the queue start
        assertFalse(start.isValid());
        assertTrue(afterA.isValid());
        q.prev();
        q.prev();
        assertEquals(afterA, q.getCurrentPosition());

        q.push("d"); // replaces b and c
        assertTrue(afterA.isValid());
        q.forgetHistory();
        assertFalse(afterA.isValid(), "forgotten history cannot be returned to");
        assertTrue(q.getCurrentPosition().isValid());
    }
}
