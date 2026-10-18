package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Columns to slots and back.
 *
 * <p>A selection is slots, a mouse position and a highlight are columns, and on a line with a Nerd
 * Font icon before every name they are different numbers. Getting it wrong copies text a column
 * off from the text that was highlighted — the two halves disagreeing is what makes it confusing
 * rather than merely wrong.
 */
class ColumnMapTest {

    /** A plane-15 Nerd Font icon: two slots, one column. */
    private static final String ICON = new String(Character.toChars(0xF024D));

    private static final String LINE = ICON + " Downloads";
    private static final String CJK = "你" + CharUtils.DWC + "好" + CharUtils.DWC + "!";

    @Test
    void plainTextMapsOneToOne() {
        assertEquals(0, ColumnMap.slotAt("Desktop", 0));
        assertEquals(4, ColumnMap.slotAt("Desktop", 4));
        assertEquals(4, ColumnMap.columnAt("Desktop", 4));
    }

    @Test
    void anAstralCharacterIsTwoSlotsAndOneColumn() {
        // Column 1 is the space after the icon, which lives at slot 2.
        assertEquals(0, ColumnMap.slotAt(LINE, 0));
        assertEquals(2, ColumnMap.slotAt(LINE, 1));
        assertEquals(3, ColumnMap.slotAt(LINE, 2));

        assertEquals(0, ColumnMap.columnAt(LINE, 0));
        assertEquals(1, ColumnMap.columnAt(LINE, 2));
        assertEquals(2, ColumnMap.columnAt(LINE, 3));
    }

    @Test
    void theTwoDirectionsAgree() {
        for (int column = 0; column <= 12; column++) {
            assertEquals(column, ColumnMap.columnAt(LINE, ColumnMap.slotAt(LINE, column)), "column " + column);
        }
    }

    @Test
    void aWideGlyphTakesTwoColumnsAndTwoSlots() {
        // The opposite direction: one slot for the glyph, one for its placeholder, two columns.
        assertEquals(2, ColumnMap.slotAt(CJK, 2));
        assertEquals(4, ColumnMap.slotAt(CJK, 4));
        assertEquals(4, ColumnMap.columnAt(CJK, 4));
    }

    @Test
    void pastTheEndBothDirectionsCarryOnOneToOne() {
        // A click in the padding right of the last character, and a selection clamped to the grid.
        assertEquals(LINE.length() + 3, ColumnMap.slotAt(LINE, 14));
        assertEquals(14, ColumnMap.columnAt(LINE, LINE.length() + 3));
    }

    @Test
    void anEmptyOrNegativeInputIsHandled() {
        assertEquals(5, ColumnMap.slotAt("", 5));
        assertEquals(5, ColumnMap.columnAt("", 5));
        assertEquals(0, ColumnMap.slotAt(LINE, -1));
        assertEquals(0, ColumnMap.columnAt(LINE, -1));
        assertEquals(3, ColumnMap.slotAt(null, 3));
        assertEquals(3, ColumnMap.columnAt(null, 3));
    }
}
