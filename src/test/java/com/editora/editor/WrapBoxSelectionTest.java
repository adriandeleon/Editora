package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WrapBoxSelectionTest {

    @Test
    void anUnwrappedParagraphIsOneRowWhereverTheRectangleEnds() {
        assertEquals(List.of(110.0), WrapBoxSelection.rowCentres(100, 20, 1, 0, 500));
        assertEquals(List.of(110.0), WrapBoxSelection.rowCentres(100, 20, 1, 119, 500));
    }

    @Test
    void aWrappedParagraphInsideTheRectangleGivesEveryRow() {
        assertEquals(List.of(110.0, 130.0, 150.0), WrapBoxSelection.rowCentres(100, 60, 3, 50, 400));
    }

    @Test
    void aWrappedParagraphTheRectangleStartsOrEndsInGivesOnlyTheRowsItCrosses() {
        assertEquals(List.of(130.0, 150.0), WrapBoxSelection.rowCentres(100, 60, 3, 125, 400)); // starts in row 2
        assertEquals(List.of(110.0, 130.0), WrapBoxSelection.rowCentres(100, 60, 3, 50, 135)); // ends in row 2
        assertEquals(List.of(130.0), WrapBoxSelection.rowCentres(100, 60, 3, 125, 135)); // within row 2
    }

    @Test
    void aRowTheRectangleOnlyTouchesIsNotCrossed() {
        assertEquals(List.of(110.0), WrapBoxSelection.rowCentres(100, 60, 3, 50, 120));
    }
}
