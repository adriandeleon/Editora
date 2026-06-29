package com.editora.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void tabMovesToNextCellAndReflows() {
        String block = "| a | b |\n|---|---|\n| 1 | 22 |";
        MarkdownTable.Nav nav = MarkdownTable.tab(block, 2, true); // caret in header cell "a"
        assertEquals("| a   | b   |\n| --- | --- |\n| 1   | 22  |", nav.block());
        assertEquals('b', nav.block().charAt(nav.caret()));
    }

    @Test
    void shiftTabMovesToPreviousCell() {
        String block = "| a   | b   |\n| --- | --- |\n| 1   | 22  |";
        MarkdownTable.Nav nav = MarkdownTable.tab(block, 8, false); // caret in header cell "b"
        assertEquals('a', nav.block().charAt(nav.caret()));
    }

    @Test
    void tabWrapsToNextRowSkippingDelimiter() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownTable.Nav nav = MarkdownTable.tab(block, 6, true); // last header cell → first data cell
        assertEquals('1', nav.block().charAt(nav.caret()));
    }

    @Test
    void tabPastLastCellAppendsRow() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownTable.Nav nav = MarkdownTable.tab(block, block.length() - 2, true);
        assertEquals(4, nav.block().split("\n", -1).length);
    }

    @Test
    void enterOnLastRowAddsRow() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownTable.Nav nav = MarkdownTable.enter(block, block.length() - 1);
        assertEquals(4, nav.block().split("\n", -1).length);
    }

    @Test
    void enterMidTableReturnsNull() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        assertNull(MarkdownTable.enter(block, 2)); // header row, not the last row
    }

    @Test
    void tabReturnsNullOutsideTable() {
        assertNull(MarkdownTable.tab("not a table", 3, true));
    }

    private static int columns(String headerLine) {
        return (int) headerLine.chars().filter(c -> c == '|').count() - 1;
    }

    @Test
    void generateBuildsAlignedSkeleton() {
        MarkdownTable.Nav g = MarkdownTable.generate(3, 2); // 3 rows incl. header, 2 cols
        String[] lines = g.block().split("\n", -1);
        assertEquals(4, lines.length); // header + delimiter + 2 body rows
        assertTrue(lines[0].contains("Column 1") && lines[0].contains("Column 2"));
        assertEquals('C', g.block().charAt(g.caret())); // caret in the first header cell
    }

    @Test
    void addRowInsertsAndDeleteRemoves() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownTable.Nav add = MarkdownTable.addRow(block, block.length() - 2); // caret in the data row
        assertEquals(4, add.block().split("\n", -1).length);
        MarkdownTable.Nav del = MarkdownTable.deleteRow(add.block(), add.caret());
        assertEquals(3, del.block().split("\n", -1).length);
    }

    @Test
    void deleteRowRefusesHeaderAndDelimiter() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        assertNull(MarkdownTable.deleteRow(block, 2)); // header row
        assertNull(MarkdownTable.deleteRow(block, 12)); // delimiter row
    }

    @Test
    void addColumnAndDeleteColumn() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownTable.Nav add = MarkdownTable.addColumn(block, 2); // caret in col 0
        assertEquals(3, columns(add.block().split("\n", -1)[0]));
        MarkdownTable.Nav del = MarkdownTable.deleteColumn(add.block(), 2);
        assertEquals(2, columns(del.block().split("\n", -1)[0]));
    }

    @Test
    void deleteColumnRefusesLastColumn() {
        assertNull(MarkdownTable.deleteColumn("| a |\n|---|\n| 1 |", 2));
    }

    @Test
    void setAlignmentRewritesDelimiterCell() {
        String block = "| a | b |\n|---|---|\n| 1 | 2 |";
        MarkdownTable.Nav nav = MarkdownTable.setAlignment(block, 2, MarkdownTable.Align.CENTER); // col 0
        assertTrue(nav.block().split("\n", -1)[1].startsWith("| :-")); // col 0 now centered
    }
}
