package com.editora.git;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Lane layout of a commit graph, from parent hashes alone. Pure and incremental: commits are fed in the
 * order they are listed (children before parents — {@code git log --date-order}), a page at a time, and the
 * lanes left open by one page are continued by the next, so the drawing is the same however the history was
 * paged.
 *
 * <p>A <em>lane</em> is a column waiting for one commit: it is opened by a child naming that commit as a
 * parent and closed when the commit is reached. A lane keeps its column for as long as it is open — rows
 * never shift sideways — and a closed column is the first to be reused. For each commit:
 *
 * <ol>
 *   <li>every lane waiting for it ends in its node (several: a branch point, drawn as lines converging from
 *       above); it sits in the leftmost of them, or in the first free column when no child was listed (a
 *       branch tip);
 *   <li>its first parent continues in the commit's own column (or joins a lane further left that already
 *       waits for that parent); each further parent (a merge) joins the lane waiting for it, or gets the
 *       first free column.
 * </ol>
 */
public final class GitGraph {

    /**
     * How one commit row is drawn. Columns are lane indexes from the left.
     *
     * @param column the commit's node
     * @param through lanes passing the row from top to bottom
     * @param in lanes that end in the node from above (may include {@code column}: a straight line down)
     * @param out lanes that start at the node and leave the row at the bottom (may include {@code column});
     *     more than one is a merge
     * @param width the number of columns the row uses
     */
    public record Row(int column, int[] through, int[] in, int[] out, int width) {

        @Override
        public boolean equals(Object o) {
            return o instanceof Row r
                    && column == r.column
                    && width == r.width
                    && Arrays.equals(through, r.through)
                    && Arrays.equals(in, r.in)
                    && Arrays.equals(out, r.out);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * column + Arrays.hashCode(through)) + Arrays.hashCode(in)) + Arrays.hashCode(out);
        }

        @Override
        public String toString() {
            return "Row[" + column + " through=" + Arrays.toString(through) + " in=" + Arrays.toString(in) + " out="
                    + Arrays.toString(out) + "]";
        }
    }

    private static final int[] NONE = {};

    /** The commit each column is waiting for; null where the column is free. */
    private final List<String> lanes = new ArrayList<>();

    private int[] scratch = new int[8];
    private int width;

    /** The widest row laid out so far, in columns. */
    public int width() {
        return width;
    }

    /** Lays out the next commits of the listing, continuing the lanes the previous ones left open. */
    public List<Row> append(List<GitLog.Entry> commits) {
        List<Row> rows = new ArrayList<>(commits.size());
        for (GitLog.Entry commit : commits) {
            rows.add(next(commit.hash(), commit.parents()));
        }
        return rows;
    }

    /** Lays out one commit. */
    public Row next(String hash, List<String> parents) {
        // 1. The lanes waiting for this commit end here.
        int n = 0;
        for (int i = 0; i < lanes.size(); i++) {
            if (hash.equals(lanes.get(i))) {
                n = push(n, i);
                lanes.set(i, null);
            }
        }
        int[] in = n == 0 ? NONE : Arrays.copyOf(scratch, n);
        int column = n == 0 ? firstFree() : in[0];
        int rowWidth = column + 1;

        // 2. Everything still open passes by.
        n = 0;
        for (int i = 0; i < lanes.size(); i++) {
            if (lanes.get(i) != null) {
                n = push(n, i);
                rowWidth = Math.max(rowWidth, i + 1);
            }
        }
        int[] through = n == 0 ? NONE : Arrays.copyOf(scratch, n);
        for (int lane : in) {
            rowWidth = Math.max(rowWidth, lane + 1);
        }

        // 3. Its parents continue below.
        n = 0;
        boolean first = true;
        for (String parent : parents) {
            int lane = lanes.indexOf(parent);
            // The first parent continues in the commit's own column, so a line of history stays in its lane
            // down to the commit where it forked — unless a lane to the left already waits for that parent:
            // then the commit joins it (a branch tip bends into the mainline rather than doubling it). Any
            // further parent joins the lane waiting for it, or opens one.
            if (lane < 0 || (first && lane > column)) {
                // The commit's own column first: it is free until one of its parents takes it.
                lane = isFree(column) ? column : firstFree();
                set(lane, parent);
            }
            first = false;
            if (!contains(scratch, n, lane)) {
                n = push(n, lane);
                rowWidth = Math.max(rowWidth, lane + 1);
            }
        }
        int[] out = n == 0 ? NONE : Arrays.copyOf(scratch, n);

        while (!lanes.isEmpty() && lanes.get(lanes.size() - 1) == null) {
            lanes.remove(lanes.size() - 1);
        }
        width = Math.max(width, rowWidth);
        return new Row(column, through, in, out, rowWidth);
    }

    private boolean isFree(int column) {
        return column >= lanes.size() || lanes.get(column) == null;
    }

    private void set(int lane, String hash) {
        while (lanes.size() <= lane) {
            lanes.add(null);
        }
        lanes.set(lane, hash);
    }

    private int firstFree() {
        int free = lanes.indexOf(null);
        return free < 0 ? lanes.size() : free;
    }

    private int push(int n, int value) {
        if (n == scratch.length) {
            scratch = Arrays.copyOf(scratch, n * 2);
        }
        scratch[n] = value;
        return n + 1;
    }

    private static boolean contains(int[] values, int n, int value) {
        for (int i = 0; i < n; i++) {
            if (values[i] == value) {
                return true;
            }
        }
        return false;
    }
}
