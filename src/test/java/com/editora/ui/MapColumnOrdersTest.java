package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A column's order, filters aside, worked out for that column alone and once per listing (perf review 2026-11-23 S1):
 * the same order {@code columnsById} gives, and a row's place found by a lookup rather than a scan.
 */
class MapColumnOrdersTest {

    private static final Path ROOT = Path.of("/tmp/map-orders").toAbsolutePath();

    /** The order as it was found before: every column grouped and sorted, then the one asked for picked. */
    private static List<ProjectMapModel.Entry> wholeMapOrder(
            List<ProjectMapModel.Entry> entries, ProjectMapModel.ColumnId id) {
        for (ProjectMapModel.Column column : ProjectMapModel.columnsById(entries, Map.of(), Map.of(), null)) {
            if (column.id().equals(id)) {
                return column.entries();
            }
        }
        return List.of();
    }

    private static List<ProjectMapModel.Entry> listing() {
        List<ProjectMapModel.Entry> entries = new ArrayList<>();
        entries.add(new ProjectMapModel.Entry(ROOT, null, 0, true));
        Path src = ROOT.resolve("src");
        Path docs = ROOT.resolve("docs");
        entries.add(new ProjectMapModel.Entry(src, ROOT, 1, true));
        entries.add(new ProjectMapModel.Entry(docs, ROOT, 1, true));
        entries.add(new ProjectMapModel.Entry(ROOT.resolve(".hidden"), ROOT, 1, false));
        entries.add(new ProjectMapModel.Entry(ROOT.resolve("README.md"), ROOT, 1, false));
        // Natural order (f2 before f10), folders first, and names that differ in case only.
        List<ProjectMapModel.Entry> files = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            files.add(new ProjectMapModel.Entry(src.resolve("f" + i + ".txt"), src, 2, false));
        }
        files.add(new ProjectMapModel.Entry(src.resolve("Zeta.txt"), src, 2, false));
        files.add(new ProjectMapModel.Entry(src.resolve("alpha.txt"), src, 2, false));
        files.add(new ProjectMapModel.Entry(src.resolve("lib"), src, 2, true));
        Collections.shuffle(files, new Random(7));
        entries.addAll(files);
        entries.add(new ProjectMapModel.Entry(src.resolve("lib/x.txt"), src.resolve("lib"), 3, false));
        entries.add(new ProjectMapModel.Entry(docs.resolve("b.md"), docs, 2, false));
        entries.add(new ProjectMapModel.Entry(docs.resolve("a.md"), docs, 2, false));
        // A column whose folder is not listed is not on the map.
        Path gone = ROOT.resolve("gone");
        entries.add(new ProjectMapModel.Entry(gone.resolve("orphan.txt"), gone, 2, false));
        return List.copyOf(entries);
    }

    @Test
    void aColumnAloneIsInTheOrderTheWholeMapGivesIt() {
        List<ProjectMapModel.Entry> entries = listing();
        List<ProjectMapModel.ColumnId> ids = List.of(
                new ProjectMapModel.ColumnId(0, null),
                new ProjectMapModel.ColumnId(1, ROOT),
                new ProjectMapModel.ColumnId(2, ROOT.resolve("src")),
                new ProjectMapModel.ColumnId(3, ROOT.resolve("src/lib")),
                new ProjectMapModel.ColumnId(2, ROOT.resolve("docs")),
                new ProjectMapModel.ColumnId(2, ROOT.resolve("gone")),
                new ProjectMapModel.ColumnId(4, ROOT.resolve("nowhere")));
        for (ProjectMapModel.ColumnId id : ids) {
            assertEquals(wholeMapOrder(entries, id), ProjectMapModel.columnInOrder(entries, id), id.toString());
        }
        assertTrue(ProjectMapModel.columnInOrder(entries, ids.get(5)).isEmpty(), "the orphan column is not shown");
        List<ProjectMapModel.Entry> src = ProjectMapModel.columnInOrder(entries, ids.get(2));
        assertEquals("lib", src.getFirst().name(), "folders first");
        assertTrue(
                src.indexOf(entries.stream()
                                .filter(e -> e.name().equals("f2.txt"))
                                .findFirst()
                                .orElseThrow())
                        < src.indexOf(entries.stream()
                                .filter(e -> e.name().equals("f10.txt"))
                                .findFirst()
                                .orElseThrow()),
                "natural order");
    }

    @Test
    void aColumnIsSortedOncePerListingAndItsPlacesAreLookedUp() {
        List<ProjectMapModel.Entry> entries = listing();
        ProjectMapModel.ColumnId src = new ProjectMapModel.ColumnId(2, ROOT.resolve("src"));
        MapColumnOrders orders = new MapColumnOrders();
        long passes = ProjectMapModel.columnPassesForTest.get();
        MapColumnOrders.Order order = orders.of(entries, src);
        for (int i = 0; i < 10; i++) {
            assertSame(order, orders.of(entries, src), "kept for the listing");
        }
        assertEquals(1, orders.sorted);
        assertEquals(passes, ProjectMapModel.columnPassesForTest.get(), "no whole-map column pass");
        List<ProjectMapModel.Entry> rows = order.rows();
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(i, order.indexOf(rows.get(i)));
        }
        assertEquals(-1, order.indexOf(entries.getFirst()), "the root is not in src's column");

        // A new listing (an expand, a re-list) is sorted again.
        List<ProjectMapModel.Entry> relisted = new ArrayList<>(entries);
        relisted.add(new ProjectMapModel.Entry(ROOT.resolve("src/new.txt"), ROOT.resolve("src"), 2, false));
        assertTrue(orders.of(List.copyOf(relisted), src).rows().size() == rows.size() + 1);
        assertEquals(2, orders.sorted);
    }

    @Test
    void aFilteredRowStandsWhereTheOldScanPutIt() {
        List<ProjectMapModel.Entry> entries = listing();
        MapColumnOrders.Order order =
                new MapColumnOrders().of(entries, new ProjectMapModel.ColumnId(2, ROOT.resolve("src")));
        List<ProjectMapModel.Entry> all = order.rows();
        // What a column filter "1" leaves drawn.
        List<ProjectMapModel.Entry> drawn =
                all.stream().filter(e -> e.name().contains("1")).toList();
        for (ProjectMapModel.Entry hidden : all) {
            if (drawn.contains(hidden)) {
                continue;
            }
            int at = all.indexOf(hidden);
            int expected = 1
                    + (int) drawn.stream().filter(row -> all.indexOf(row) < at).count();
            assertEquals(expected, order.positionAmong(drawn, hidden), hidden.name());
        }
        assertEquals(1, order.positionAmong(List.of(), all.getLast()));
    }
}
