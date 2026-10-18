package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a style run is drawn.
 *
 * <p>The failure this guards is not an off-by-one but a drift: every astral character earlier in
 * the line pushes the rest of it a further column right, so a directory listing's columns diverge
 * the further right you look while each line reads correctly on its own.
 */
class RunColumnsTest {

    /** A Nerd Font icon from plane 15 — two char slots, one column. */
    private static final String ICON = new String(Character.toChars(0xF024D));

    @Test
    void plainTextIsUntouched() {
        RunColumns columns = new RunColumns();
        assertEquals(0, columns.columnOf(0, 0, "Desktop"));
        assertEquals(7, columns.columnOf(0, 7, "  "));
        assertEquals(9, columns.columnOf(0, 9, "Downloads"));
    }

    @Test
    void anAstralCharacterCostsOneColumnNotTwo() {
        RunColumns columns = new RunColumns();
        // The icon is its own run because eza colours it separately from the name.
        assertEquals(0, columns.columnOf(0, 0, ICON));
        // JediTerm says slot 2; the icon occupied one column, so the name belongs at column 1.
        assertEquals(1, columns.columnOf(0, 2, " Desktop"));
    }

    @Test
    void theDriftAccumulatesAcrossARow() {
        // The bug as seen: a row with two icons ended two columns right of where it belonged.
        RunColumns columns = new RunColumns();
        columns.columnOf(0, 0, ICON);
        columns.columnOf(0, 2, " Downloads  ");
        columns.columnOf(0, 14, ICON);
        assertEquals(14, columns.columnOf(0, 16, " Pictures"));
    }

    @Test
    void aWideGlyphStillTakesItsSecondColumn() {
        // The opposite direction, and the reason counting Java chars is not the fix: a CJK
        // character is one slot for the glyph plus a placeholder for the column it spills into.
        RunColumns columns = new RunColumns();
        assertEquals(0, columns.columnOf(0, 0, "你" + CharUtils.DWC + "好" + CharUtils.DWC));
        assertEquals(4, columns.columnOf(0, 4, "!"));
    }

    @Test
    void eachRowStartsOver() {
        RunColumns columns = new RunColumns();
        columns.columnOf(0, 0, ICON);
        columns.columnOf(0, 2, " Desktop");
        assertEquals(0, columns.columnOf(1, 0, "Documents"));
    }

    @Test
    void aGapBetweenRunsIsCarriedAcross() {
        // Padding past the last written column is ordinary single-slot cells.
        RunColumns columns = new RunColumns();
        columns.columnOf(0, 0, ICON);
        assertEquals(5, columns.columnOf(0, 6, "x"));
    }

    @Test
    void aRunThatGoesBackwardsFallsBackToTheIndex() {
        // Never observed, but the arithmetic assumes runs arrive left to right; if they ever do
        // not, the old behaviour is the safe answer rather than a negative column.
        RunColumns columns = new RunColumns();
        columns.columnOf(0, 10, "abcdefghij");
        assertEquals(0, columns.columnOf(0, 0, "x"));
    }
}
