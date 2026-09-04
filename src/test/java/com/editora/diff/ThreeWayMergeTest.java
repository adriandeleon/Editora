package com.editora.diff;

import java.util.Collections;
import java.util.List;

import com.editora.diff.ConflictParser.Choice;
import com.editora.diff.ConflictParser.Conflict;
import com.editora.diff.ConflictParser.ConflictSegment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThreeWayMergeTest {

    @Test
    void automaticallyCombinesIndependentChanges() {
        var result = ThreeWayMerge.merge(
                "alpha\nbeta\ngamma\ndelta\n", "alpha\nBETA\ngamma\ndelta\n", "alpha\nbeta\ngamma\nDELTA\n");

        assertFalse(result.file().hasConflicts());
        assertEquals(2, result.automaticallyMergedChanges());
        assertEquals(
                List.of("alpha", "BETA", "gamma", "DELTA"),
                ConflictParser.resolve(result.file(), Collections.emptyList()));
    }

    @Test
    void automaticallyCombinesTheSameOverlappingEditOnce() {
        var result = ThreeWayMerge.merge("before\nold\nafter", "before\nnew\nafter", "before\nnew\nafter");

        assertFalse(result.file().hasConflicts());
        assertEquals(List.of("before", "new", "after"), ConflictParser.resolve(result.file(), List.of()));
    }

    @Test
    void exposesActualAncestorForDivergentEdit() {
        var result = ThreeWayMerge.merge("before\nold\nafter", "before\nours\nafter", "before\ntheirs\nafter");

        assertEquals(1, result.file().conflictCount());
        Conflict conflict = ((ConflictSegment) result.file().segments().get(1)).conflict();
        assertTrue(conflict.hasBase());
        assertEquals(List.of("old"), conflict.base());
        assertEquals(List.of("ours"), conflict.ours());
        assertEquals(List.of("theirs"), conflict.theirs());
        assertEquals(List.of("before", "old", "after"), ConflictParser.resolve(result.file(), List.of(Choice.BASE)));
    }

    @Test
    void representsCompetingInsertionsWithAnEmptyButPresentBase() {
        var result = ThreeWayMerge.merge("before\nafter", "before\nours\nafter", "before\ntheirs\nafter");

        Conflict conflict = ((ConflictSegment) result.file().segments().get(1)).conflict();
        assertTrue(conflict.hasBase());
        assertTrue(conflict.base().isEmpty());
        assertEquals(List.of("ours"), conflict.ours());
        assertEquals(List.of("theirs"), conflict.theirs());
    }

    @Test
    void deletionConflictsWithAnEditInsideDeletedRegion() {
        var result = ThreeWayMerge.merge("before\none\ntwo\nafter", "before\nafter", "before\none\nTWO\nafter");

        assertEquals(1, result.file().conflictCount());
        Conflict conflict = ((ConflictSegment) result.file().segments().get(1)).conflict();
        assertEquals(List.of("one", "two"), conflict.base());
        assertTrue(conflict.ours().isEmpty());
        assertEquals(List.of("one", "TWO"), conflict.theirs());
    }

    @Test
    void adjacentEditsRemainIndependent() {
        var result = ThreeWayMerge.merge("one\ntwo\nthree", "ONE\ntwo\nthree", "one\nTWO\nthree");

        assertFalse(result.file().hasConflicts());
        assertEquals(List.of("ONE", "TWO", "three"), ConflictParser.resolve(result.file(), List.of()));
    }
}
