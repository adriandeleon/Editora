package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;

/**
 * Translates between a line's display columns and its {@code char[]} slots.
 *
 * <p>The same disagreement {@link CellWidth} counts, needed in both directions. A selection is
 * expressed in slots — JediTerm's {@code SelectionUtil} indexes the line's array — while a mouse
 * position and a highlight rectangle are columns. On a line with a Nerd Font icon before every
 * name the two are not the same number, so clicking selects text a column off from the one under
 * the pointer, and the wash is drawn somewhere other than the characters it is meant to cover.
 *
 * <p>Past the end of the line both directions extrapolate one-to-one, so a click in the padding to
 * the right of the last character, or a selection clamped to the grid width, still lands somewhere
 * sensible rather than at the line's end.
 */
final class ColumnMap {

    private ColumnMap() {}

    /** The char index a display column falls in. */
    static int slotAt(String line, int column) {
        if (line == null || line.isEmpty() || column <= 0) return Math.max(0, column);
        int cells = 0;
        int i = 0;
        while (i < line.length()) {
            if (cells >= column) return i;
            if (line.charAt(i) == CharUtils.DWC) {
                cells++;
                i++;
                continue;
            }
            cells++;
            i += Character.charCount(line.codePointAt(i));
        }
        return line.length() + (column - cells);
    }

    /** The display column a char index sits at. */
    static int columnAt(String line, int slot) {
        if (line == null || line.isEmpty() || slot <= 0) return Math.max(0, slot);
        int cells = 0;
        int i = 0;
        while (i < line.length()) {
            if (i >= slot) return cells;
            if (line.charAt(i) == CharUtils.DWC) {
                cells++;
                i++;
                continue;
            }
            cells++;
            i += Character.charCount(line.codePointAt(i));
        }
        return cells + (slot - line.length());
    }
}
