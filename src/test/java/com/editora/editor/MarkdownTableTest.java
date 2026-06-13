package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class MarkdownTableTest {

    @Test
    void reflowPadsColumnsToEqualWidth() {
        String in = "| a | b |\n|---|---|\n| 1 | 22 |";
        String out = "| a   | b   |\n| --- | --- |\n| 1   | 22  |";
        assertEquals(out, MarkdownTable.reflow(in));
    }

    @Test
    void reflowHonorsAlignment() {
        String in = "| h |\n|:-:|\n| x |";
        String out = "|  h  |\n| :-: |\n|  x  |";
        assertEquals(out, MarkdownTable.reflow(in));
    }

    @Test
    void reflowLeftAndRightAlignment() {
        String in = "| a | b |\n| :-- | --: |\n| x | y |";
        // col0 left-aligned, col1 right-aligned (applies to every row including the header)
        assertEquals("| a   |   b |\n| :-- | --: |\n| x   |   y |", MarkdownTable.reflow(in));
    }

    @Test
    void nonTableLeftUnchanged() {
        assertEquals("just text", MarkdownTable.reflow("just text"));
        // pipes but no delimiter row → not a GFM table
        assertEquals("| a | b |", MarkdownTable.reflow("| a | b |"));
    }

    @Test
    void blockBoundsFindsContiguousRows() {
        String text = "pre\n| a | b |\n|---|---|\nafter";
        int[] b = MarkdownTable.blockBounds(text, 5);
        assertArrayEquals(new int[] {4, 23}, b);
        assertEquals("| a | b |\n|---|---|", text.substring(b[0], b[1]));
    }

    @Test
    void blockBoundsNullOffTable() {
        String text = "pre\n| a | b |\nafter";
        assertNull(MarkdownTable.blockBounds(text, 1)); // caret in "pre"
    }
}
