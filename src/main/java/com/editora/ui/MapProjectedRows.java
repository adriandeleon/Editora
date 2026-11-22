package com.editora.ui;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * The Project Map's row boxes on screen, projected from the world layout when they are read (PERF-08, Pauta 6.2.0).
 * A pan or a zoom changes the projection, not the rows: a paint used to allocate a screen box for every listed row
 * (5,000 for a whole folder, tens of thousands at the caps) and test each against the viewport. Now a new projection
 * costs nothing, a box is made the first time a row is read in it and kept until the next one, and the paint reads
 * only the rows of the columns on screen ({@link #visibleRange}).
 */
final class MapProjectedRows extends AbstractList<MapSurface.NodeBox> implements RandomAccess {

    private List<MapSurface.NodeBox> world = List.of();
    private MapSurface.NodeBox[] boxes = new MapSurface.NodeBox[0];
    /** Per row, the projection its box was made in; a box of an older one is made again. */
    private int[] madeIn = new int[0];
    /** The current projection; 0 is never one, so a fresh array holds no box. */
    private int projection;

    private double zoom = 1;
    private double offsetX;
    private double offsetY;

    /** Boxes made since this list was created (tests: a paint makes only the rows it shows). */
    long made;

    /** Projects {@code rows} (world units) with {@code zoom} and the offsets. No row is projected here. */
    void project(List<MapSurface.NodeBox> rows, double zoom, double offsetX, double offsetY) {
        if (rows != world) {
            world = rows;
            boxes = new MapSurface.NodeBox[rows.size()];
            madeIn = new int[rows.size()];
        }
        this.zoom = zoom;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        projection = projection == Integer.MAX_VALUE ? 1 : projection + 1;
        if (projection == 1) {
            java.util.Arrays.fill(madeIn, 0); // wrapped round: no stamp may match by chance
        }
    }

    /** No rows (an empty map). */
    @Override
    public void clear() {
        project(List.of(), zoom, offsetX, offsetY);
    }

    @Override
    public int size() {
        return world.size();
    }

    @Override
    public MapSurface.NodeBox get(int index) {
        if (madeIn[index] == projection) {
            return boxes[index];
        }
        MapSurface.NodeBox row = world.get(index);
        MapSurface.NodeBox box = new MapSurface.NodeBox(
                row.entry(),
                offsetX + row.x() * zoom,
                offsetY + row.y() * zoom,
                row.width() * zoom,
                row.height() * zoom);
        boxes[index] = box;
        madeIn[index] = projection;
        made++;
        return box;
    }

    /**
     * The rows of one column that can reach the span {@code [low, high]} of the screen along the column's own axis
     * ({@code y} when its rows stack downwards, {@code x} when they stand side by side), as {@code {from, to}}
     * (exclusive). A column's rows are laid out one step apart along that axis; when the column's first and last rows
     * do not say so, every row is returned and the caller's own test decides. Rows past the returned ones cannot
     * reach the span; the ones returned still need that test.
     */
    int[] visibleRange(int first, int count, boolean alongX, double low, double high) {
        if (count <= 0) {
            return new int[] {first, first};
        }
        MapSurface.NodeBox head = world.get(first);
        double start = alongX ? head.x() : head.y();
        double size = (alongX ? head.width() : head.height()) * zoom;
        double origin = (alongX ? offsetX : offsetY) + start * zoom;
        if (count == 1) {
            return new int[] {first, first + 1};
        }
        MapSurface.NodeBox second = world.get(first + 1);
        MapSurface.NodeBox last = world.get(first + count - 1);
        double step = (alongX ? second.x() : second.y()) - start;
        double end = alongX ? last.x() : last.y();
        if (!(step > 0) || Math.abs(start + step * (count - 1) - end) > 1e-6 * Math.max(1, Math.abs(end))) {
            return new int[] {first, first + count}; // not evenly laid out: test them all
        }
        return rangeOf(origin, step * zoom, size, count, low, high, first);
    }

    /** {@link #visibleRange} once the layout is known even: row {@code i} spans {@code origin + i*step} plus size. */
    static int[] rangeOf(double origin, double step, double size, int count, double low, double high, int first) {
        // A row reaches the span when origin + i*step + size >= low and origin + i*step <= high.
        double lowIndex = Math.floor((low - size - origin) / step) - 1;
        double highIndex = Math.ceil((high - origin) / step) + 1;
        int from = (int) Math.max(0, Math.min(count, lowIndex));
        int to = (int) Math.max(from, Math.min(count, highIndex + 1));
        return new int[] {first + from, first + to};
    }
}
