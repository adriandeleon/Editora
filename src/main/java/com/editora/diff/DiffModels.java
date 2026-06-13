package com.editora.diff;

import java.util.List;

/**
 * Toolkit-free value types for the diff viewer (see {@link DiffEngine}). A computed diff is a list of
 * aligned {@link Row}s (one screen row per side, with filler on the absent side) plus a derived list of
 * {@link UnifiedRow}s; the UI renders side-by-side from {@code rows} and unified from {@code unified}.
 */
public final class DiffModels {

    private DiffModels() {}

    /** The kind of an aligned side-by-side row. */
    public enum RowType {
        EQUAL,
        ADDED,
        REMOVED,
        MODIFIED
    }

    /**
     * One aligned row. For {@link RowType#ADDED} the left side is filler ({@code left == null},
     * {@code leftLine == -1}); for {@link RowType#REMOVED} the right side is filler. {@link RowType#MODIFIED}
     * has text on both sides and optional intra-line word ranges ({@code [start,end)} char offsets that
     * differ), used to highlight just the changed words.
     */
    public record Row(
            RowType type,
            String left,
            int leftLine,
            String right,
            int rightLine,
            int[][] leftWordRanges,
            int[][] rightWordRanges) {

        public static Row equal(String text, int leftLine, int rightLine) {
            return new Row(RowType.EQUAL, text, leftLine, text, rightLine, null, null);
        }

        public static Row added(String text, int rightLine) {
            return new Row(RowType.ADDED, null, -1, text, rightLine, null, null);
        }

        public static Row removed(String text, int leftLine) {
            return new Row(RowType.REMOVED, text, leftLine, null, -1, null, null);
        }

        public static Row modified(
                String left,
                int leftLine,
                String right,
                int rightLine,
                int[][] leftWordRanges,
                int[][] rightWordRanges) {
            return new Row(RowType.MODIFIED, left, leftLine, right, rightLine, leftWordRanges, rightWordRanges);
        }
    }

    /** The kind of a unified-view row. */
    public enum UnifiedType {
        CONTEXT,
        ADD,
        REMOVE
    }

    /** One unified-view row; {@code leftLine}/{@code rightLine} is -1 on the side that has no line. */
    public record UnifiedRow(UnifiedType type, String text, int leftLine, int rightLine) {}

    /**
     * A computed diff: aligned side-by-side {@code rows}, the derived {@code unified} rows, the added /
     * removed line counts, and {@code changeBlockStarts} — the row indices that begin each contiguous run
     * of non-equal rows (for prev/next-change navigation).
     */
    public record DiffModel(
            List<Row> rows, List<UnifiedRow> unified, int added, int removed, List<Integer> changeBlockStarts) {

        public boolean isEmpty() {
            return added == 0 && removed == 0;
        }
    }
}
