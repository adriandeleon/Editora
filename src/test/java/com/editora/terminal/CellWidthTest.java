package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Buffer text measured in columns rather than in array slots. */
class CellWidthTest {

    private static final String DWC = String.valueOf(CharUtils.DWC);

    @Test
    void asciiIsOneCellPerCharacter() {
        assertEquals(5, CellWidth.cells("hello"));
        assertEquals(0, CellWidth.cells(""));
    }

    @Test
    void aWideCharacterIsTwoCellsAcrossTwoSlots() {
        // JediTerm stores the glyph, then a placeholder for the column it spills into.
        assertEquals(2, CellWidth.cells("日" + DWC));
        assertEquals(4, CellWidth.cells("日" + DWC + "本" + DWC));
    }

    @Test
    void anAstralCharacterIsOneCellAcrossTwoSlots() {
        // The case that broke alignment: a Nerd Font icon from the supplementary private-use area
        // is a surrogate pair, so String.length() says 2 while the terminal gave it one column.
        String icon = new String(Character.toChars(0xF0219));
        assertEquals(2, icon.length());
        assertEquals(1, CellWidth.cells(icon));
        assertEquals(3, CellWidth.cells(icon + ".."));
    }

    @Test
    void aWideAstralCharacterIsTwoCells() {
        // An emoji: a surrogate pair *and* a placeholder.
        String emoji = new String(Character.toChars(0x1F600));
        assertEquals(2, CellWidth.cells(emoji + DWC));
    }
}
