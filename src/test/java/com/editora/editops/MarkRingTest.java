package com.editora.editops;

import java.util.List;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure mark ring (no toolkit). */
class MarkRingTest {

    private static MarkRing ring(int... marks) {
        MarkRing r = new MarkRing();
        for (int m : marks) {
            r.push(m);
        }
        return r;
    }

    // --- push ------------------------------------------------------------------------------------

    @Test
    void pushesNewestFirst() {
        assertEquals(List.of(30, 20, 10), ring(10, 20, 30).positions());
    }

    @Test
    void collapsesAConsecutiveDuplicate() {
        MarkRing r = ring(10, 10, 20, 20, 10);
        assertEquals(List.of(10, 20, 10), r.positions(), "only *consecutive* duplicates fold");
    }

    @Test
    void ignoresANegativePosition() {
        MarkRing r = new MarkRing();
        r.push(-1);
        assertTrue(r.isEmpty());
    }

    @Test
    void isBoundedAndDropsTheOldest() {
        MarkRing r = new MarkRing(3);
        r.push(1);
        r.push(2);
        r.push(3);
        r.push(4);
        assertEquals(List.of(4, 3, 2), r.positions());
    }

    @Test
    void maxMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new MarkRing(0));
    }

    // --- pop / cycle -----------------------------------------------------------------------------

    @Test
    void popOnAnEmptyRingReturnsEmpty() {
        assertTrue(new MarkRing().pop(5).isEmpty());
    }

    @Test
    void popReturnsTheMostRecentMark() {
        MarkRing r = ring(10, 20, 30);
        assertEquals(OptionalInt.of(30), r.pop(99));
    }

    @Test
    void repeatedPopsCycleThroughEveryMarkAndReturnToTheStart() {
        MarkRing r = ring(10, 20, 30); // ring = [30, 20, 10], start point = 99
        int point = 99;
        int[] visited = new int[4];
        for (int i = 0; i < 4; i++) {
            OptionalInt t = r.pop(point);
            point = t.getAsInt();
            visited[i] = point;
        }
        assertEquals("[30, 20, 10, 99]", java.util.Arrays.toString(visited), "walks all marks, then back to origin");
    }

    @Test
    void popDoesNotReAddThePointWhenItEqualsTheTarget() {
        MarkRing r = ring(42);
        assertEquals(OptionalInt.of(42), r.pop(42), "popping to where you already are is a no-op move");
        assertEquals(List.of(), r.positions(), "and does not stack a duplicate entry");
    }

    // --- shift through edits ---------------------------------------------------------------------

    @Test
    void aMarkAfterAnInsertionMovesByTheInsertedLength() {
        MarkRing r = ring(20);
        r.shift(10, 0, 5); // inserted 5 chars at offset 10
        assertEquals(List.of(25), r.positions());
    }

    @Test
    void aMarkBeforeAnEditIsUntouched() {
        MarkRing r = ring(5);
        r.shift(10, 0, 5);
        assertEquals(List.of(5), r.positions());
    }

    @Test
    void anInsertionExactlyAtTheMarkKeepsItBeforeTheNewText() {
        MarkRing r = ring(10);
        r.shift(10, 0, 5);
        assertEquals(List.of(10), r.positions(), "insertion at the mark does not carry it forward");
    }

    @Test
    void aMarkAfterADeletionMovesBack() {
        MarkRing r = ring(20);
        r.shift(5, 4, 0); // removed 4 chars at offset 5
        assertEquals(List.of(16), r.positions());
    }

    @Test
    void aMarkInsideTheDeletedSpanCollapsesToTheEditSite() {
        MarkRing r = ring(8);
        r.shift(5, 6, 0); // removes [5, 11), which contains the mark at 8
        assertEquals(List.of(5), r.positions());
    }

    @Test
    void shiftMovesEveryEntryAndPreservesOrder() {
        MarkRing r = ring(10, 30, 50); // [50, 30, 10]
        r.shift(20, 0, 100); // big insert at 20 shifts 30 and 50, leaves 10
        assertEquals(List.of(150, 130, 10), r.positions());
    }

    @Test
    void aNoOpEditDoesNotDisturbTheRing() {
        MarkRing r = ring(10, 20);
        r.shift(5, 0, 0);
        assertEquals(List.of(20, 10), r.positions());
    }

    // --- shiftOne boundaries ---------------------------------------------------------------------

    @Test
    void shiftOneReplacementInteriorMarkCollapsesToTheEditStart() {
        // Replace [5, 10) (5 removed) with 3 chars: a mark at 7 lands at 5, before the inserted text
        // (Emacs marker insertion-type nil), not carried past it.
        assertEquals(5, MarkRing.shiftOne(7, 5, 5, 3));
    }

    @Test
    void shiftOneMarkJustAfterTheDeletedSpanLandsAfterTheInsertedText() {
        // The boundary case: a mark at pos+removed (10) ends up right after the replacement (5 + 3 = 8).
        assertEquals(8, MarkRing.shiftOne(10, 5, 5, 3));
    }

    @Test
    void clearEmptiesTheRing() {
        MarkRing r = ring(1, 2, 3);
        r.clear();
        assertTrue(r.isEmpty());
        assertFalse(r.pop(0).isPresent());
    }
}
