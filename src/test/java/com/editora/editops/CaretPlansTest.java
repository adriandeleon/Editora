package com.editora.editops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaretPlansTest {

    private static final String TEXT = "alpha\nbeta\n\ngamma"; // lines start at 0, 6, 11, 12

    private static String ranges(List<int[]> rs) {
        StringBuilder b = new StringBuilder();
        for (int[] r : rs) {
            b.append(r[0]).append('-').append(r[1]).append(' ');
        }
        return b.toString().trim();
    }

    @Test
    void splitGivesEachLineItsSelectedPart() {
        // From the "ph" of alpha to the "gam" of gamma: alpha's tail, all of beta, the empty line, "gam".
        assertEquals("2-5 6-10 11-11 12-15", ranges(CaretPlans.splitIntoLines(TEXT, 2, 15)));
        assertEquals("2-5 6-10 11-11 12-15", ranges(CaretPlans.splitIntoLines(TEXT, 15, 2)), "backwards too");
    }

    @Test
    void aSelectionEndingAtALineStartDoesNotReachIntoThatLine() {
        assertEquals("0-5 6-10", ranges(CaretPlans.splitIntoLines(TEXT, 0, 11)));
        assertEquals("0-5", ranges(CaretPlans.splitIntoLines(TEXT, 0, 6)));
    }

    @Test
    void aSelectionInsideOneLineStaysOneSelection() {
        assertEquals("1-3", ranges(CaretPlans.splitIntoLines(TEXT, 1, 3)));
        assertEquals("5-5", ranges(CaretPlans.splitIntoLines(TEXT, 5, 6)), "just a line break: a caret at the end");
    }

    @Test
    void anEmptySelectionSplitsIntoNothing() {
        assertTrue(CaretPlans.splitIntoLines(TEXT, 4, 4).isEmpty());
        assertTrue(CaretPlans.caretsAtLineEnds(TEXT, 4, 4).isEmpty());
        assertTrue(CaretPlans.splitIntoLines("", 0, 0).isEmpty());
    }

    @Test
    void outOfRangeOffsetsAreClamped() {
        assertEquals("12-17", ranges(CaretPlans.splitIntoLines(TEXT, 12, 99)));
        assertEquals("0-5", ranges(CaretPlans.splitIntoLines(TEXT, -4, 3 + 2)));
    }

    @Test
    void caretsAtLineEndsPutABareCaretAtTheEndOfEveryLine() {
        assertEquals("5-5 10-10 11-11 17-17", ranges(CaretPlans.caretsAtLineEnds(TEXT, 2, 13)));
        assertEquals("5-5 10-10", ranges(CaretPlans.caretsAtLineEnds(TEXT, 0, 11)));
    }

    @Test
    void nextOccurrenceSearchesOnAndWrapsRound() {
        String text = "ab xx ab yy ab";
        assertArrayEquals(new int[] {6, 8}, CaretPlans.nextOccurrence(text, "ab", 2, List.of()));
        assertArrayEquals(new int[] {0, 2}, CaretPlans.nextOccurrence(text, "ab", 14, List.of()), "wraps");
    }

    @Test
    void nextOccurrenceSkipsTakenRanges() {
        String text = "ab xx ab yy ab";
        List<int[]> taken = List.of(new int[] {6, 8}, new int[] {14, 12});
        assertArrayEquals(new int[] {0, 2}, CaretPlans.nextOccurrence(text, "ab", 2, taken));
        List<int[]> all = List.of(new int[] {0, 2}, new int[] {6, 8}, new int[] {12, 14});
        assertNull(CaretPlans.nextOccurrence(text, "ab", 3, all), "every occurrence taken");
    }

    @Test
    void nextOccurrenceIsCaseSensitiveAndNullWithoutATerm() {
        assertNull(CaretPlans.nextOccurrence("AB ab", "aB", 0, List.of()));
        assertNull(CaretPlans.nextOccurrence("ab", "", 0, List.of()));
        assertNull(CaretPlans.nextOccurrence("ab", null, 0, List.of()));
        assertNull(CaretPlans.nextOccurrence("xyz", "ab", 0, List.of()));
    }
}
