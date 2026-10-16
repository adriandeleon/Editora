package com.editora.editops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class IndentLevelsTest {

    @Test
    void widthCountsColumnsWithTabStops() {
        assertEquals(0, IndentLevels.width("x", 4));
        assertEquals(4, IndentLevels.width("    x", 4));
        assertEquals(4, IndentLevels.width("\tx", 4));
        assertEquals(8, IndentLevels.width("  \t  \tx", 4), "2 → tab to 4, 2 → 6, tab to 8");
        assertEquals(-1, IndentLevels.width("   ", 4), "whitespace only is blank");
        assertEquals(-1, IndentLevels.width("", 4));
        assertEquals(1, IndentLevels.width("\tx", 0), "a tab size below one counts as one");
    }

    @Test
    void blankLinesTakeTheSmallerOfTheirNeighbours() {
        List<String> lines = List.of("def f():", "    if x:", "        y", "", "    z", "", "w");
        int[] widths = IndentLevels.widths(lines::get, lines.size(), 0, lines.size() - 1, 4, 200);
        assertArrayEquals(new int[] {0, 4, 8, 4, 4, 0, 0}, widths);
    }

    @Test
    void aBlankRunAtTheEdgeOfTheWindowLooksPastIt() {
        List<String> lines = List.of("    a", "", "", "    b", "", "x");
        // Only lines 1..2 are visible: both are blank; their block is found above and below.
        assertArrayEquals(new int[] {4, 4}, IndentLevels.widths(lines::get, lines.size(), 1, 2, 4, 200));
        // With no look-around, the unseen sides count as unindented.
        assertArrayEquals(new int[] {0, 0}, IndentLevels.widths(lines::get, lines.size(), 1, 2, 4, 0));
        assertArrayEquals(new int[] {0}, IndentLevels.widths(lines::get, lines.size(), 4, 4, 4, 200));
    }

    @Test
    void guidesSitAtEachIndentLevelBelowTheWidth() {
        assertEquals(0, IndentLevels.guides(0, 4));
        assertEquals(1, IndentLevels.guides(4, 4));
        assertEquals(2, IndentLevels.guides(8, 4));
        assertEquals(2, IndentLevels.guides(6, 4), "a partial level still gets its guide");
        assertEquals(0, IndentLevels.guides(8, 0));
    }
}
