package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;

/**
 * How many terminal cells a stretch of buffer text occupies.
 *
 * <p>Not its length. The buffer is a {@code char[]}, so a character outside the Basic Multilingual
 * Plane — an emoji, or one of the Nerd Font icons a modern {@code ls} puts before every filename —
 * takes two array slots while occupying one column. A double-width character is the opposite case:
 * one slot for the glyph, then a {@link CharUtils#DWC} placeholder for the column it spills into.
 *
 * <p>Confusing the two shifts everything after the character by a column, which is what makes a
 * directory listing's columns fail to line up while each line looks individually fine.
 */
final class CellWidth {

    private CellWidth() {}

    /** Cells occupied by {@code text}, counting from the start of a style run. */
    static int cells(String text) {
        if (text == null || text.isEmpty()) return 0;
        int cells = 0;
        int i = 0;
        while (i < text.length()) {
            if (text.charAt(i) == CharUtils.DWC) {
                // The second half of a wide glyph: a column of its own, nothing drawn in it.
                cells++;
                i++;
                continue;
            }
            cells++;
            i += Character.charCount(text.codePointAt(i));
        }
        return cells;
    }
}
