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

    @Test
    void parseSizeAcceptsNxNAndRejectsGarbage() {
        assertArrayEquals(new int[] {4, 4}, MarkdownTable.parseSize("4x4"));
        assertArrayEquals(new int[] {3, 2}, MarkdownTable.parseSize(" 3 X 2 "));
        assertArrayEquals(new int[] {2, 5}, MarkdownTable.parseSize("2×5"));
        assertArrayEquals(
                new int[] {MarkdownTable.MAX_SIZE, MarkdownTable.MAX_SIZE}, MarkdownTable.parseSize("999x999"));
        assertNull(MarkdownTable.parseSize("4"));
        assertNull(MarkdownTable.parseSize("4x"));
        assertNull(MarkdownTable.parseSize("0x3"));
        assertNull(MarkdownTable.parseSize("axb"));
        assertNull(MarkdownTable.parseSize(null));
    }

    @Test
    void fromCsvProducesValidGfmTableWithHeaderRow() {
        String t = MarkdownTable.fromCsv("Name,Age\nAlice,30");
        String[] lines = t.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("Name") && lines[0].contains("Age"), lines[0]);
        assertTrue(lines[1].replace(" ", "").matches("\\|-+\\|-+\\|"), lines[1]); // delimiter row
        assertTrue(lines[2].contains("Alice") && lines[2].contains("30"), lines[2]);
    }

    @Test
    void csvRoundTripsThroughTable() {
        // toCsv(fromCsv(csv)) is padding-agnostic, so it exercises both directions
        String csv = "Name,Age\nAlice,30\nBob,7";
        assertEquals(csv, MarkdownTable.toCsv(MarkdownTable.fromCsv(csv)));
    }

    @Test
    void fromCsvDetectsTabAndSemicolonDelimiters() {
        assertEquals("a,b,c\n1,2,3", MarkdownTable.toCsv(MarkdownTable.fromCsv("a\tb\tc\n1\t2\t3")));
        assertEquals("a,b\n1,2", MarkdownTable.toCsv(MarkdownTable.fromCsv("a;b\n1;2")));
    }

    @Test
    void csvHonorsQuotingAndEscapesPipes() {
        // a quoted comma, a doubled quote, and a literal pipe — round-trips exactly
        String csv = "h1,h2\n\"x, y\",\"say \"\"hi\"\"\"\na|b,c";
        assertEquals(csv, MarkdownTable.toCsv(MarkdownTable.fromCsv(csv)));
    }

    @Test
    void fromCsvPadsShortRowsAndReturnsNullWhenEmpty() {
        assertEquals("a,b,c\n1,,", MarkdownTable.toCsv(MarkdownTable.fromCsv("a,b,c\n1")));
        assertNull(MarkdownTable.fromCsv("   "));
        assertNull(MarkdownTable.fromCsv(null));
    }

    @Test
    void toCsvUnescapesPipesAndReturnsNullWhenNotATable() {
        assertEquals("a|b,c", MarkdownTable.toCsv("| a\\|b | c |\n| --- | --- |"));
        assertNull(MarkdownTable.toCsv("just some text\nno table here"));
    }

    /**
     * {@code lastIndexOf} searches backward from *and including* {@code fromIndex}, so the old
     * {@code Math.max(0, caret - 1)} clamp made a caret at offset 0 find a leading newline — yielding
     * {@code substring(1, 0)}. It threw for any document starting with a blank line, table or not, and
     * {@code EditorBuffer.applyEnter} calls this on every Enter in a Markdown buffer: Ctrl+Home then Enter
     * lost the keystroke and dumped an exception.
     */
    @Test
    void blockBoundsAtOffsetZeroOfADocStartingWithABlankLine() {
        assertNull(MarkdownTable.blockBounds("\n| a | b |\n|---|---|", 0));
        assertNull(MarkdownTable.blockBounds("\nplain text", 0));
        assertNull(MarkdownTable.blockBounds("\n", 0));
        assertArrayEquals(
                new int[] {1, 20},
                MarkdownTable.blockBounds("\n| a | b |\n|---|---|", 2),
                "the row itself still resolves");
    }

    /**
     * A cell may contain an escaped pipe ({@code \|}) — {@link MarkdownTable#fromCsv} emits them by design.
     * Splitting the row on every {@code |} cut such a cell in two and invented a column, so pressing Tab in a
     * table pasted from CSV silently corrupted it.
     */
    @Test
    void escapedPipesSurviveReflowAndNavigation() {
        String table = MarkdownTable.fromCsv("h1,h2\na|b,c");
        assertEquals("h1,h2\na|b,c", MarkdownTable.toCsv(table), "fromCsv → toCsv round-trips");
        String reflowed = MarkdownTable.reflow(table);
        assertEquals(
                2, MarkdownTable.reflow(reflowed).split("\n")[0].split("(?<!\\\\)\\|").length - 1, "still 2 columns");
        assertEquals("h1,h2\na|b,c", MarkdownTable.toCsv(reflowed), "reflow preserves the escaped pipe");
        assertEquals(reflowed, MarkdownTable.reflow(reflowed), "reflow is idempotent");
        MarkdownTable.Nav nav = MarkdownTable.tab(table, 2, true);
        assertEquals("h1,h2\na|b,c", MarkdownTable.toCsv(nav.block()), "Tab preserves it too");
    }

    /** An escaped pipe is two chars wide on emit, so the column must be padded to fit it. */
    @Test
    void escapedPipeCellFillsItsColumn() {
        String[] lines =
                MarkdownTable.reflow("| a\\|b | c |\n| --- | --- |\n| x | y |").split("\n");
        assertEquals(lines[0].length(), lines[1].length(), "header and delimiter rows line up");
        assertEquals(lines[0].length(), lines[2].length(), "body row lines up");
    }

    /** Deleting the only data row used to clamp the caret onto the delimiter row, where typing breaks it. */
    @Test
    void deleteRowNeverLeavesTheCaretOnTheDelimiterRow() {
        String block = "| a | b |\n| --- | --- |\n| 1 | 2 |";
        MarkdownTable.Nav nav = MarkdownTable.deleteRow(block, block.indexOf('1'));
        int line = 0;
        for (int i = 0; i < nav.caret() && i < nav.block().length(); i++) {
            if (nav.block().charAt(i) == '\n') {
                line++;
            }
        }
        assertEquals(0, line, "caret falls back to the header, not the '---' row");
    }
}
