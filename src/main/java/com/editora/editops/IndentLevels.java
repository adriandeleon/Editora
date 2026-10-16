package com.editora.editops;

import java.util.function.IntFunction;

/**
 * How far each line is indented, for drawing indent guides: the width of its leading whitespace in columns,
 * a tab reaching the next tab stop. A blank line takes the indentation of the block it sits in — the smaller
 * of the nearest non-blank lines above and below — so the guides run through blank lines instead of breaking
 * at every one. Pure.
 */
public final class IndentLevels {

    private IndentLevels() {}

    /** The indentation of {@code line} in columns, or -1 when the line is blank (empty or only whitespace). */
    public static int width(String line, int tabSize) {
        int tab = Math.max(1, tabSize);
        int column = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                column++;
            } else if (c == '\t') {
                column += tab - column % tab;
            } else {
                return column;
            }
        }
        return -1;
    }

    /**
     * The indentation of lines {@code first..last} (inclusive) of a document of {@code total} lines, blank lines
     * filled in from their neighbours. A blank run at either end looks at most {@code lookaround} lines past it;
     * with no non-blank line within reach on one side, that side counts as unindented.
     *
     * @param line the text of line {@code i}, without its line break
     */
    public static int[] widths(IntFunction<String> line, int total, int first, int last, int tabSize, int lookaround) {
        int n = Math.max(0, last - first + 1);
        int[] out = new int[n];
        int[] raw = new int[n];
        for (int i = 0; i < n; i++) {
            raw[i] = width(line.apply(first + i), tabSize);
        }
        int above = nearest(line, first - 1, -1, Math.max(0, first - lookaround), tabSize);
        for (int i = 0; i < n; i++) {
            if (raw[i] >= 0) {
                out[i] = raw[i];
                above = raw[i];
                continue;
            }
            int below = -1;
            for (int j = i + 1; j < n && below < 0; j++) {
                below = raw[j];
            }
            if (below < 0) {
                below = nearest(line, last + 1, 1, Math.min(total - 1, last + lookaround), tabSize);
            }
            out[i] = Math.max(0, Math.min(above, below));
        }
        return out;
    }

    /** The indentation of the first non-blank line from {@code from} stepping by {@code step} to {@code until}. */
    private static int nearest(IntFunction<String> line, int from, int step, int until, int tabSize) {
        for (int i = from; step < 0 ? i >= until : i <= until; i += step) {
            int w = width(line.apply(i), tabSize);
            if (w >= 0) {
                return w;
            }
        }
        return -1;
    }

    /** The guide columns of a line indented {@code width} columns: 0, unit, 2·unit… below {@code width}. */
    public static int guides(int width, int unit) {
        return unit <= 0 || width <= 0 ? 0 : (width + unit - 1) / unit;
    }
}
