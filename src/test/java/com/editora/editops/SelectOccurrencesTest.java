package com.editora.editops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SelectOccurrencesTest {

    // --- wordAt ---

    @Test
    void wordAtInsideAWord() {
        assertArrayEquals(new int[] {4, 7}, SelectOccurrences.wordAt("foo bar baz", 5));
    }

    @Test
    void wordAtWordBoundaryTakesTheAdjacentWord() {
        assertArrayEquals(new int[] {0, 3}, SelectOccurrences.wordAt("foo bar", 3)); // just after "foo"
        assertArrayEquals(new int[] {4, 7}, SelectOccurrences.wordAt("foo bar", 4)); // just before "bar"
    }

    @Test
    void wordAtIncludesDigitsAndUnderscore() {
        assertArrayEquals(new int[] {0, 6}, SelectOccurrences.wordAt("foo_42 x", 3));
    }

    @Test
    void wordAtOnNonWordIsNull() {
        assertNull(SelectOccurrences.wordAt("a + b", 2)); // on the '+'
        assertNull(SelectOccurrences.wordAt("   ", 1));
        assertNull(SelectOccurrences.wordAt("", 0));
        assertNull(SelectOccurrences.wordAt("ab", 9)); // out of range
    }

    // --- primaryIndex ---

    @Test
    void primaryIsTheMatchContainingTheAnchor() {
        List<int[]> m = List.of(new int[] {0, 3}, new int[] {10, 13}, new int[] {20, 23});
        assertEquals(1, SelectOccurrences.primaryIndex(m, 11)); // anchor inside the 2nd match
        assertEquals(1, SelectOccurrences.primaryIndex(m, 10)); // at its start
        assertEquals(1, SelectOccurrences.primaryIndex(m, 13)); // at its end
    }

    @Test
    void primaryFallsToTheFirstMatchAfterTheAnchor() {
        List<int[]> m = List.of(new int[] {0, 3}, new int[] {10, 13}, new int[] {20, 23});
        assertEquals(1, SelectOccurrences.primaryIndex(m, 5)); // between match 0 and 1 → next is 1
    }

    @Test
    void primaryFallsToTheLastWhenAnchorIsPastEverything() {
        List<int[]> m = List.of(new int[] {0, 3}, new int[] {10, 13});
        assertEquals(1, SelectOccurrences.primaryIndex(m, 50));
    }

    @Test
    void primaryIsZeroForAnEmptyList() {
        assertEquals(0, SelectOccurrences.primaryIndex(List.of(), 0));
    }
}
