package com.editora.editor;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link CodeLensShift}: lenses stay on their declarations while lines come and go around them. */
class CodeLensShiftTest {

    private static final Map<Integer, String> LENSES = Map.of(2, "a", 5, "b", 9, "c");

    @Test
    void aLineAddedAboveMovesEverythingBelowIt() {
        assertEquals(Map.of(2, "a", 6, "b", 10, "c"), CodeLensShift.shift(LENSES, 3, false, 0, false, 1));
    }

    @Test
    void enterAtTheEndOfADeclarationLeavesItsLensInPlace() {
        assertEquals(Map.of(2, "a", 5, "b", 10, "c"), CodeLensShift.shift(LENSES, 5, false, 0, false, 1));
    }

    @Test
    void enterAtTheStartOfADeclarationPushesItDown() {
        assertEquals(Map.of(2, "a", 6, "b", 10, "c"), CodeLensShift.shift(LENSES, 5, true, 0, false, 1));
    }

    @Test
    void removedLinesTakeTheirLensesWithThem() {
        // lines 4..6 joined into line 4: the lens on 5 is gone, the one on 9 moves up two
        assertEquals(Map.of(2, "a", 7, "c"), CodeLensShift.shift(LENSES, 4, false, 2, false, -2));
    }

    @Test
    void aDeclarationDeletedFromItsFirstColumnLosesItsLens() {
        assertEquals(Map.of(2, "a", 8, "c"), CodeLensShift.shift(LENSES, 5, true, 1, true, -1));
    }

    @Test
    void replacingLinesWithMoreLinesShiftsByTheDifference() {
        assertEquals(Map.of(2, "a", 11, "c"), CodeLensShift.shift(LENSES, 4, false, 2, false, 2));
    }

    @Test
    void theLineAfterWholeRemovedLinesKeepsItsLens() {
        // lines 3 and 4 deleted from column 0 through their line breaks: line 5 is whole and becomes line 3
        assertEquals(Map.of(2, "a", 3, "b", 7, "c"), CodeLensShift.shift(LENSES, 3, true, 2, true, -2));
    }
}
