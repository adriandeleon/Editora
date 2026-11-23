package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Map's rows are projected when read, and a paint reads only those that can be on screen (PERF-08). */
class MapProjectedRowsTest {

    private static final Path ROOT = Path.of("/tmp/map-rows").toAbsolutePath();

    /** {@code count} rows stacked from {@code (x, y)}, {@code step} apart along y (or x). */
    private static List<MapSurface.NodeBox> column(int count, double x, double y, double step, boolean alongX) {
        List<MapSurface.NodeBox> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ProjectMapModel.Entry entry = new ProjectMapModel.Entry(ROOT.resolve("f" + i), ROOT, 1, false);
            rows.add(new MapSurface.NodeBox(entry, alongX ? x + i * step : x, alongX ? y : y + i * step, 180, 24));
        }
        return rows;
    }

    @Test
    void aProjectionMakesNoBoxUntilARowIsRead() {
        List<MapSurface.NodeBox> world = column(5_000, 10, 20, 28, false);
        MapProjectedRows rows = new MapProjectedRows();
        rows.project(world, 2, 100, -50);
        assertEquals(5_000, rows.size());
        assertEquals(0, rows.made);
        MapSurface.NodeBox box = rows.get(3);
        assertEquals(100 + 10 * 2, box.x(), 1e-9);
        assertEquals(-50 + (20 + 3 * 28) * 2, box.y(), 1e-9);
        assertEquals(360, box.width(), 1e-9);
        assertEquals(48, box.height(), 1e-9);
        assertSame(box, rows.get(3), "kept for the projection");
        assertEquals(1, rows.made);
        rows.project(world, 2, 90, -50); // a pan
        assertEquals(1, rows.made);
        assertEquals(90 + 10 * 2, rows.get(3).x(), 1e-9);
        assertEquals(2, rows.made);
        rows.clear();
        assertTrue(rows.isEmpty());
    }

    @Test
    void theVisibleRangeHoldsEveryRowThatReachesTheSpan() {
        for (boolean alongX : new boolean[] {false, true}) {
            List<MapSurface.NodeBox> world = new ArrayList<>(column(3, 0, 0, 30, alongX)); // another column first
            world.addAll(column(5_000, 40, 300, 28, alongX));
            MapProjectedRows rows = new MapProjectedRows();
            for (double zoom : new double[] {0.25, 1, 3.5}) {
                for (double offset : new double[] {-120_000, -9_000, -500, 0, 700}) {
                    rows.project(world, zoom, offset, offset);
                    int[] range = rows.visibleRange(3, 5_000, alongX, -20, 600);
                    assertTrue(range[1] - range[0] < 200, "only about a screen of rows: " + (range[1] - range[0]));
                    for (int i = 3; i < 3 + 5_000; i++) {
                        MapSurface.NodeBox box = rows.get(i);
                        double start = alongX ? box.x() : box.y();
                        double size = alongX ? box.width() : box.height();
                        boolean reaches = start + size >= -20 && start <= 600;
                        if (reaches) {
                            assertTrue(
                                    i >= range[0] && i < range[1],
                                    "row " + i + " reaches the span at zoom " + zoom + ", offset " + offset);
                        }
                    }
                }
            }
        }
    }

    @Test
    void unevenRowsAreAllHandedBack() {
        List<MapSurface.NodeBox> world = new ArrayList<>(column(3, 0, 0, 30, false));
        world.set(2, new MapSurface.NodeBox(world.get(2).entry(), 0, 500, 180, 24));
        MapProjectedRows rows = new MapProjectedRows();
        rows.project(world, 1, 0, 0);
        int[] range = rows.visibleRange(0, 3, false, 0, 10);
        assertEquals(0, range[0]);
        assertEquals(3, range[1]);
        assertEquals(
                List.of(0, 1),
                List.of(rows.visibleRange(0, 1, false, 0, 10)[0], rows.visibleRange(0, 1, false, 0, 10)[1]));
        assertEquals(2, rows.visibleRange(2, 0, false, 0, 10)[0]);
    }

    /** Perf review 2026-11-23 S6: a paint of an unchanged layout clears and projects again without new arrays. */
    @Test
    void projectingTheSameRowsAfterAClearReusesTheArrays() {
        List<MapSurface.NodeBox> world = column(5_000, 10, 20, 28, false);
        MapProjectedRows rows = new MapProjectedRows();
        rows.project(world, 1, 0, 0);
        assertEquals(1, rows.arraysMade);
        MapSurface.NodeBox first = rows.get(7);
        for (int frame = 0; frame < 10; frame++) {
            rows.clear(); // what every projectLayout starts with
            assertTrue(rows.isEmpty());
            rows.project(world, 1, -frame, 0);
            assertEquals(5_000, rows.size());
        }
        assertEquals(1, rows.arraysMade, "the arrays stay while the world rows do");
        assertEquals(-9 + 10, rows.get(7).x(), 1e-9, "each row is projected afresh");
        assertTrue(first != rows.get(7), "not the box of an older projection");
        rows.project(column(10, 0, 0, 28, false), 1, 0, 0); // a new layout
        assertEquals(2, rows.arraysMade);
        assertEquals(10, rows.size());
    }
}
