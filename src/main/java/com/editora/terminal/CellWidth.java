package com.editora.terminal;

import com.jediterm.terminal.util.CharUtils;
import org.jspecify.annotations.Nullable;

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

    /**
     * Whether every char of {@code text} is one narrow cell, so the run can be drawn in one call.
     *
     * <p>The renderer's fast-path test, and its scope is deliberate. Only two things break the one-char-one-column
     * assumption a single {@code fillText} relies on: an astral code point, two chars for one or two columns, and a
     * wide glyph's {@link CharUtils#DWC} filler, one char for a column that must not be drawn. Everything else in the
     * BMP — box drawing, block elements, braille, accented letters — is a cell apiece and stays batched. An
     * ASCII-only test sent every TUI border and progress bar down the per-glyph path for no gain. Control characters
     * are excluded too: a run holding one has nothing sensible to draw in a batch.
     */
    static boolean isOneCellPerChar(CharSequence text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f || c == CharUtils.DWC || Character.isSurrogate(c)) return false;
        }
        return true;
    }

    /**
     * Cells occupied by {@code text}, counting from the start of a style run. A {@link CharSequence} so the renderer
     * can pass JediTerm's {@code CharBuffer} as it is, rather than copying every run of every frame to count it.
     */
    static int cells(@Nullable CharSequence text) {
        if (text == null || text.isEmpty()) return 0;
        int cells = 0;
        int i = 0;
        int length = text.length();
        while (i < length) {
            if (text.charAt(i) == CharUtils.DWC) {
                // The second half of a wide glyph: a column of its own, nothing drawn in it.
                cells++;
                i++;
                continue;
            }
            int codePoint = Character.codePointAt(text, i);
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
