package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ManualFoldsShiftLinesTest {

    @Test
    void aSingleLineEditMovesNothing() {
        List<Integer> lines = List.of(2, 9);
        assertSame(lines, ManualFolds.shiftLines(lines, 4, 0, 0));
    }

    @Test
    void insertedLinesPushLaterHeadersDown() {
        assertEquals(List.of(2, 4, 12), ManualFolds.shiftLines(List.of(2, 4, 9), 4, 0, 3));
    }

    @Test
    void removedLinesPullLaterHeadersUpAndDropTheOnesTheyHeld() {
        // Lines 5..7 are joined into line 4: headers on them are gone, the one on line 8 becomes line 5.
        assertEquals(List.of(4, 5), ManualFolds.shiftLines(List.of(4, 5, 7, 8), 4, 3, 0));
    }

    @Test
    void aReplacementAppliesBoth() {
        assertEquals(List.of(1, 11), ManualFolds.shiftLines(List.of(1, 3, 10), 2, 2, 3));
    }

    @Test
    void nothingToShift() {
        assertEquals(List.of(), ManualFolds.shiftLines(null, 0, 1, 1));
        assertEquals(List.of(), ManualFolds.shiftLines(List.of(), 0, 1, 1));
    }
}
