package com.editora.editops;

import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LineTransformsTest {

    // --- lineBounds ---

    @Test
    void lineBoundsExtendsToFullLines() {
        String text = "aaa\nbbb\nccc";
        assertArrayEquals(new int[] {0, 7}, LineTransforms.lineBounds(text, 1, 5));
        assertArrayEquals(new int[] {4, 7}, LineTransforms.lineBounds(text, 5, 5));
    }

    @Test
    void lineBoundsExcludesTheColumnZeroEndLine() {
        String text = "aaa\nbbb\nccc";
        // Dragging over "aaa\nbbb\n" leaves the selection end at ccc's column 0 — ccc is not included.
        assertArrayEquals(new int[] {0, 7}, LineTransforms.lineBounds(text, 0, 8));
    }

    @Test
    void lineBoundsClampsOutOfRange() {
        assertArrayEquals(new int[] {0, 3}, LineTransforms.lineBounds("abc", -2, 99));
    }

    // --- sorts ---

    @Test
    void sortAscendingIsNaturalAndCaseInsensitive() {
        assertEquals("apple\nBanana\ncherry", LineTransforms.sortAscending("cherry\nBanana\napple"));
        assertEquals("file2\nfile10", LineTransforms.sortAscending("file10\nfile2"));
    }

    @Test
    void sortIsDeterministicOnCaseTies() {
        assertEquals("ABC\nABC\nabc\nabc", LineTransforms.sortAscending("abc\nABC\nabc\nABC"));
    }

    @Test
    void sortDescendingReversesTheOrder() {
        assertEquals("file10\nfile2", LineTransforms.sortDescending("file2\nfile10"));
    }

    @Test
    void sortByLengthShortestFirst() {
        assertEquals("b\naa\nccc", LineTransforms.sortByLength("ccc\nb\naa"));
    }

    @Test
    void numericComparisonHandlesLeadingZeros() {
        assertEquals("a007\na8\na10", LineTransforms.sortAscending("a10\na007\na8"));
    }

    // --- reverse / shuffle ---

    @Test
    void reverseFlipsLineOrder() {
        assertEquals("c\nb\na", LineTransforms.reverse("a\nb\nc"));
    }

    @Test
    void shuffleKeepsAllLines() {
        String shuffled = LineTransforms.shuffle("a\nb\nc\nd\ne", new Random(42));
        assertEquals("a\nb\nc\nd\ne", LineTransforms.sortAscending(shuffled));
    }

    // --- filters ---

    @Test
    void removeDuplicatesKeepsFirstOccurrence() {
        assertEquals("b\na\nc", LineTransforms.removeDuplicates("b\na\nb\nc\na"));
    }

    @Test
    void removeEmptyDropsBlankLines() {
        assertEquals("a\nb", LineTransforms.removeEmpty("a\n\n   \nb"));
    }

    @Test
    void trimTrailingStripsLineEnds() {
        assertEquals("a\n  b\nc", LineTransforms.trimTrailing("a  \n  b\t\nc"));
    }

    // --- trailing-newline preservation ---

    @Test
    void trailingNewlinePreservedAndNotSorted() {
        assertEquals("a\nb\nc\n", LineTransforms.sortAscending("c\na\nb\n"));
        assertEquals("a\nb\n", LineTransforms.removeDuplicates("a\nb\na\n"));
    }

    @Test
    void singleLineAndEmptyInputPassThrough() {
        assertEquals("only", LineTransforms.sortAscending("only"));
        assertEquals("", LineTransforms.sortAscending(""));
    }
}
