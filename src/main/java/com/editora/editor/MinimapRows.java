package com.editora.editor;

import java.util.Arrays;
import java.util.function.IntFunction;
import java.util.function.IntUnaryOperator;

/**
 * Pure geometry for the {@link Minimap}'s content render: which document lines get a row of the column,
 * and where each line's runs of non-whitespace fall in it. The minimap only draws what a {@link RunList}
 * says, so the walk over the document is testable without a canvas, and two renders can be compared
 * before anything is drawn.
 *
 * <p>The column is {@code rowHeight} pixels per line, compressed to fit a long document. Once a line is
 * less than a pixel tall ({@link #aggregated}) several lines share each pixel row, and drawing all of them
 * was the cost that grew with the document — 20,000 sub-pixel rows for a 20,000-line file, every one a
 * fill the renderer then blends away. Instead each pixel row is drawn once, as the longest of the lines
 * that map to it: one length read per line, one text fetch and one scan per <em>row</em>, so the render is
 * O(column height) in everything but the cheapest step.
 */
final class MinimapRows {

    private MinimapRows() {}

    /** Below this many column pixels per line, lines share pixel rows and one line stands for each row. */
    static final double MIN_ROW_HEIGHT = 1.0;

    /** Whether lines are drawn one per pixel row rather than one per line. */
    static boolean aggregated(double rowHeight) {
        return rowHeight < MIN_ROW_HEIGHT;
    }

    /** How many rows a render draws: one per pixel row when aggregating, else one per line. */
    static int rowCount(int total, double rowHeight) {
        if (total <= 0 || rowHeight <= 0) {
            return 0;
        }
        return aggregated(rowHeight) ? Math.max(1, (int) Math.ceil(total * rowHeight)) : total;
    }

    /** The row line {@code line} is drawn in, given {@code rows} rows (see {@link #rowCount}). */
    static int rowOf(int line, double rowHeight, int rows) {
        return aggregated(rowHeight) ? Math.min(rows - 1, (int) (line * rowHeight)) : line;
    }

    /**
     * For each row, the line that stands for it: the line itself when every line has a row, else the
     * longest of the lines sharing the row; {@code -1} for a row whose lines are all blank. Reads one
     * length per line and no text.
     */
    static int[] representatives(int total, double rowHeight, IntUnaryOperator lengthOf) {
        int rows = rowCount(total, rowHeight);
        int[] rep = new int[rows];
        Arrays.fill(rep, -1);
        int[] best = new int[rows];
        for (int i = 0; i < total; i++) {
            int len = lengthOf.applyAsInt(i);
            if (len <= 0) {
                continue;
            }
            int r = rowOf(i, rowHeight, rows);
            if (len > best[r]) {
                best[r] = len;
                rep[r] = i;
            }
        }
        return rep;
    }

    /**
     * Fills {@code out} with every run one render draws: the representative of each row (see {@link
     * #representatives}), scanned for runs of non-whitespace up to {@code maxColumns}.
     *
     * @param lengthOf the length of a line, called once per line
     * @param textOf the text of a line, called once per drawn row
     */
    static void plan(
            int total,
            double rowHeight,
            int tabSize,
            int maxColumns,
            IntUnaryOperator lengthOf,
            IntFunction<String> textOf,
            RunList out) {
        out.clear();
        int[] rep = representatives(total, rowHeight, lengthOf);
        for (int r = 0; r < rep.length; r++) {
            if (rep[r] >= 0) {
                scanRuns(textOf.apply(rep[r]), tabSize, maxColumns, r, out);
            }
        }
    }

    /**
     * Appends the runs of non-whitespace in {@code text} to {@code out}, in columns, for row {@code row}.
     * Stops at {@code maxColumns}: past the column's right edge nothing more can be drawn, so a minified
     * line is not scanned to its end for runs that all fall outside it.
     *
     * @return how many characters of {@code text} were examined
     */
    static int scanRuns(String text, int tabSize, int maxColumns, int row, RunList out) {
        int n = text.length();
        int col = 0;
        int runStart = -1;
        for (int i = 0; i <= n; i++) {
            if (i < n && col >= maxColumns) {
                if (runStart >= 0 && runStart < maxColumns) {
                    out.add(row, runStart, maxColumns);
                }
                return i;
            }
            boolean whitespace = i == n || Character.isWhitespace(text.charAt(i));
            if (!whitespace && runStart < 0) {
                runStart = col;
            } else if (whitespace && runStart >= 0) {
                if (runStart < maxColumns) {
                    out.add(row, runStart, Math.min(col, maxColumns));
                }
                runStart = -1;
            }
            if (i < n) {
                col += text.charAt(i) == '\t' ? tabSize : 1;
            }
        }
        return n;
    }

    /**
     * The runs of one render as packed {@code (row, startColumn, endColumn)} triples, in the order they are
     * drawn. Reused across renders so a re-render allocates nothing once it has grown to the document's
     * size; {@link #sameAs} is how a render whose runs did not move skips the snapshot.
     */
    static final class RunList {

        private static final int ROW_SHIFT = 40;
        private static final int START_SHIFT = 20;
        private static final long COLUMN_MASK = (1L << START_SHIFT) - 1;

        private long[] runs = new long[256];
        private int size;

        void clear() {
            size = 0;
        }

        void add(int row, int startColumn, int endColumn) {
            if (size == runs.length) {
                runs = Arrays.copyOf(runs, size * 2);
            }
            runs[size++] = ((long) row << ROW_SHIFT)
                    | ((long) (startColumn & COLUMN_MASK) << START_SHIFT)
                    | (endColumn & COLUMN_MASK);
        }

        int size() {
            return size;
        }

        int row(int k) {
            return (int) (runs[k] >>> ROW_SHIFT);
        }

        int startColumn(int k) {
            return (int) ((runs[k] >>> START_SHIFT) & COLUMN_MASK);
        }

        int endColumn(int k) {
            return (int) (runs[k] & COLUMN_MASK);
        }

        /** Replaces these runs with {@code other}'s. */
        void copyFrom(RunList other) {
            if (runs.length < other.size) {
                runs = new long[other.runs.length];
            }
            System.arraycopy(other.runs, 0, runs, 0, other.size);
            size = other.size;
        }

        /** Whether {@code other} holds exactly these runs, in this order. */
        boolean sameAs(RunList other) {
            return size == other.size && Arrays.equals(runs, 0, size, other.runs, 0, size);
        }
    }
}
