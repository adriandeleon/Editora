package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;

/**
 * How many terminal cells a stretch of buffer text occupies.
 *
 * <p>Not its length. The buffer is a {@code char[]}, so a character outside the Basic Multilingual
 * Plane takes two array slots. One of the Nerd Font icons a modern {@code ls} puts before every
 * filename occupies one column; an emoji occupies two, as the shell's {@code wcwidth} says. JediTerm
 * puts no placeholder after an astral character (it only asks a BMP one whether it is wide), so its
 * width is looked up here: two slots and two columns for a wide one, which is also where JediTerm's
 * cursor goes after writing it. A wide BMP character is the other case: one slot for the glyph,
 * then a {@link CharUtils#DWC} placeholder for the column it spills into.
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
            int codePoint = text.codePointAt(i);
            cells += of(codePoint);
            i += Character.charCount(codePoint);
        }
        return cells;
    }

    /**
     * Columns one code point takes, ignoring {@link CharUtils#DWC}: 2 for a wide astral character (an emoji),
     * else 1 — a wide BMP character's second column is its placeholder's.
     */
    static int of(int codePoint) {
        return Character.isSupplementaryCodePoint(codePoint) && CharUtils.isDoubleWidthCharacter(codePoint, false)
                ? 2
                : 1;
    }
}
