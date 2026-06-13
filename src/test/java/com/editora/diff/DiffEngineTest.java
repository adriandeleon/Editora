package com.editora.diff;

import java.util.List;

import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.DiffModels.Row;
import com.editora.diff.DiffModels.RowType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffEngineTest {

    @Test
    void linesNormalizesEolAndDropsSingleTrailingNewline() {
        assertEquals(List.of("a", "b"), DiffEngine.lines("a\nb\n"));
        assertEquals(List.of("a", "b"), DiffEngine.lines("a\r\nb"));
        assertEquals(List.of("a", "", "b"), DiffEngine.lines("a\n\nb")); // interior blank kept
        assertEquals(List.of(), DiffEngine.lines(""));
        assertEquals(List.of(), DiffEngine.lines(null));
    }

    @Test
    void identicalContentIsAllEqual() {
        DiffModel m = DiffEngine.compute(List.of("a", "b", "c"), List.of("a", "b", "c"));
        assertEquals(3, m.rows().size());
        assertTrue(m.rows().stream().allMatch(r -> r.type() == RowType.EQUAL));
        assertEquals(0, m.added());
        assertEquals(0, m.removed());
        assertTrue(m.isEmpty());
        assertTrue(m.changeBlockStarts().isEmpty());
    }

    @Test
    void pureInsertionAndDeletionAlignWithFiller() {
        // Insert "x" between a and b.
        DiffModel ins = DiffEngine.compute(List.of("a", "b"), List.of("a", "x", "b"));
        assertEquals(3, ins.rows().size());
        assertEquals(RowType.ADDED, ins.rows().get(1).type());
        assertEquals("x", ins.rows().get(1).right());
        assertEquals(-1, ins.rows().get(1).leftLine()); // filler on the left
        assertEquals(1, ins.added());
        assertEquals(0, ins.removed());

        DiffModel del = DiffEngine.compute(List.of("a", "x", "b"), List.of("a", "b"));
        assertEquals(RowType.REMOVED, del.rows().get(1).type());
        assertEquals(-1, del.rows().get(1).rightLine());
        assertEquals(1, del.removed());
    }

    @Test
    void changedLinePairsIntoModifiedWithWordRanges() {
        DiffModel m = DiffEngine.compute(List.of("the quick fox"), List.of("the slow fox"));
        assertEquals(1, m.rows().size());
        Row r = m.rows().get(0);
        assertEquals(RowType.MODIFIED, r.type());
        assertEquals(1, m.added());
        assertEquals(1, m.removed());
        // "quick" → "slow" is the only changed word; one range per side.
        assertEquals(1, r.leftWordRanges().length);
        assertEquals(1, r.rightWordRanges().length);
        assertEquals("quick", "the quick fox".substring(r.leftWordRanges()[0][0], r.leftWordRanges()[0][1]));
        assertEquals("slow", "the slow fox".substring(r.rightWordRanges()[0][0], r.rightWordRanges()[0][1]));
    }

    @Test
    void changeBlockStartsMarkEachContiguousRun() {
        // equal, change, equal, add → two change blocks at rows 1 and 3.
        DiffModel m = DiffEngine.compute(List.of("a", "B", "c"), List.of("a", "b", "c", "d"));
        assertEquals(List.of(1, 3), m.changeBlockStarts());
    }

    @Test
    void unifiedExpandsModifiedToRemoveThenAdd() {
        DiffModel m = DiffEngine.compute(List.of("a", "B"), List.of("a", "b"));
        // context "a", then remove "B", then add "b".
        assertEquals(DiffModels.UnifiedType.CONTEXT, m.unified().get(0).type());
        assertEquals(DiffModels.UnifiedType.REMOVE, m.unified().get(1).type());
        assertEquals(DiffModels.UnifiedType.ADD, m.unified().get(2).type());
        assertEquals("B", m.unified().get(1).text());
        assertEquals("b", m.unified().get(2).text());
    }
}
