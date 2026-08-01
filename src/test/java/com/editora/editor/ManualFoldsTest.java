package com.editora.editor;

import java.util.List;

import com.editora.editor.FoldRegions.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the manual-fold line arithmetic (no toolkit). */
class ManualFoldsTest {

    private static Region r(int s, int e) {
        return new Region(s, e);
    }

    @Test
    void aSingleLineEditMovesNothing() {
        List<Region> in = List.of(r(2, 6));
        assertEquals(in, ManualFolds.shift(in, 3, 0, 0), "typing within a line never moves a region");
    }

    @Test
    void anInsertAboveShiftsTheWholeRegionDown() {
        assertEquals(List.of(r(4, 8)), ManualFolds.shift(List.of(r(2, 6)), 0, 0, 2));
    }

    @Test
    void aDeleteAboveShiftsTheWholeRegionUp() {
        assertEquals(List.of(r(1, 5)), ManualFolds.shift(List.of(r(2, 6)), 0, 1, 0));
    }

    @Test
    void anEditBelowLeavesTheRegionAlone() {
        assertEquals(List.of(r(2, 6)), ManualFolds.shift(List.of(r(2, 6)), 7, 0, 3));
        assertEquals(List.of(r(2, 6)), ManualFolds.shift(List.of(r(2, 6)), 7, 2, 0));
    }

    @Test
    void anInsertInsideGrowsTheEnd() {
        // Two new lines typed at line 4, inside [2..6] → the fold now spans [2..8].
        assertEquals(List.of(r(2, 8)), ManualFolds.shift(List.of(r(2, 6)), 4, 0, 2));
    }

    @Test
    void aDeleteInsideShrinksTheEnd() {
        assertEquals(List.of(r(2, 4)), ManualFolds.shift(List.of(r(2, 6)), 3, 2, 0));
    }

    @Test
    void aDeletionSwallowingTheRegionDropsIt() {
        // Deleting lines 1..8 removes every line of [2..6]; a phantom one-line fold would grow a
        // chevron on an arbitrary surviving line, so it is dropped instead.
        assertTrue(ManualFolds.shift(List.of(r(2, 6)), 1, 8, 0).isEmpty());
    }

    @Test
    void aDeletionOverlappingTheStartClampsIt() {
        // Lines 0..3 removed; [2..6]'s surviving lines land at [0..3] (end shifted by the delta).
        assertEquals(List.of(r(0, 3)), ManualFolds.shift(List.of(r(2, 6)), 0, 3, 0));
    }

    @Test
    void independentRegionsShiftIndependently() {
        List<Region> in = List.of(r(1, 3), r(10, 14));
        assertEquals(List.of(r(1, 3), r(12, 16)), ManualFolds.shift(in, 5, 0, 2));
    }

    @Test
    void lineBreaksCountsNewlinesOnly() {
        assertEquals(0, ManualFolds.lineBreaks(null));
        assertEquals(0, ManualFolds.lineBreaks("abc"));
        assertEquals(2, ManualFolds.lineBreaks("a\nb\nc"));
        assertEquals(1, ManualFolds.lineBreaks("\n"));
    }

    @Test
    void flatRoundTrip() {
        List<Region> regions = List.of(r(2, 6), r(10, 14));
        assertEquals(List.of(2, 6, 10, 14), ManualFolds.toFlat(regions));
        assertEquals(regions, ManualFolds.fromFlat(List.of(2, 6, 10, 14)));
    }

    @Test
    void fromFlatSkipsMalformedEntries() {
        // Odd length: the trailing element has no partner. Inverted / degenerate pairs are skipped —
        // a hand-edited or corrupted workspace-state must not produce an unfoldable phantom region.
        assertEquals(List.of(r(2, 6)), ManualFolds.fromFlat(List.of(2, 6, 9)));
        assertEquals(List.of(), ManualFolds.fromFlat(List.of(6, 2)));
        assertEquals(List.of(), ManualFolds.fromFlat(List.of(3, 3)));
        assertEquals(List.of(), ManualFolds.fromFlat(List.of(-1, 4)));
        assertEquals(List.of(), ManualFolds.fromFlat(null));
    }
}
