package com.editora.editor;

import java.util.Arrays;

/**
 * The gutter's right-aligned line-number strings, built without a {@link java.util.Formatter} and kept in a
 * small direct-mapped cache so a row rebuilt by Flowless (every edited paragraph, every scroll step) reuses the
 * String it had. {@code String.format("%Ns", n)} parsed the pattern, built a Formatter and boxed the number
 * for each of the 5–8 nodes a cell rebuild allocates (K2 in the 2026-10 perf review); a hit here is two array
 * reads. The cache is per gutter (per buffer) and bounded; a width change drops it, since every row re-pads.
 */
final class LineNumberText {

    /** Rows cached per gutter: a few screens of lines, so scrolling back over them allocates nothing. */
    static final int SLOTS = 1024;

    private final String[] text = new String[SLOTS];
    private final int[] number = new int[SLOTS];
    private int width = -1;
    private String blank = "";

    /** {@code number} right-aligned in {@code width} columns (wider when it does not fit), cached. */
    String padded(int number, int width) {
        if (width != this.width) {
            reset(width);
        }
        int slot = Math.floorMod(number, SLOTS);
        String hit = text[slot];
        if (hit != null && this.number[slot] == number) {
            return hit;
        }
        String s = format(number, width);
        text[slot] = s;
        this.number[slot] = number;
        return s;
    }

    /** {@code width} spaces: the text of a row with no number of its own (a wrapped continuation). */
    String blank(int width) {
        if (width != this.width) {
            reset(width);
        }
        return blank;
    }

    private void reset(int width) {
        Arrays.fill(text, null);
        this.width = width;
        blank = " ".repeat(Math.max(0, width));
    }

    /** As {@code String.format("%" + width + "s", number)}: right-aligned, never truncated. Pure. */
    static String format(int number, int width) {
        String digits = Integer.toString(number);
        int pad = width - digits.length();
        if (pad <= 0) {
            return digits;
        }
        char[] out = new char[width];
        Arrays.fill(out, 0, pad, ' ');
        digits.getChars(0, digits.length(), out, pad);
        return new String(out);
    }
}
