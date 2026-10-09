package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The minimap's row plan. The property that matters is that a render stays O(column height) however long
 * the document is: a 20,000-line file in a 900 px column used to draw 20,000 sub-pixel rows.
 */
class MinimapRowsTest {

    private static final int COLUMNS = Minimap.maxColumns(Minimap.WIDTH);

    private static String[] lines(int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = "line " + i + " = compute(" + i + ");";
        }
        return out;
    }

    private static MinimapRows.RunList plan(String[] doc, double rowHeight, int tabSize) {
        MinimapRows.RunList out = new MinimapRows.RunList();
        MinimapRows.plan(doc.length, rowHeight, tabSize, COLUMNS, i -> doc[i].length(), i -> doc[i], out);
        return out;
    }

    private static List<int[]> triples(MinimapRows.RunList runs) {
        List<int[]> out = new ArrayList<>();
        for (int k = 0; k < runs.size(); k++) {
            out.add(new int[] {runs.row(k), runs.startColumn(k), runs.endColumn(k)});
        }
        return out;
    }

    @Test
    void aShortDocumentDrawsOneRowPerLine() {
        assertFalse(MinimapRows.aggregated(3.0));
        assertFalse(MinimapRows.aggregated(1.0), "a whole pixel per line still draws every line");
        assertEquals(600, MinimapRows.rowCount(600, 1.5));
        assertEquals(42, MinimapRows.rowOf(42, 1.5, 600));
        int[] rep = MinimapRows.representatives(5, 2.0, i -> i == 2 ? 0 : 10);
        assertArrayEquals(new int[] {0, 1, -1, 3, 4}, rep, "a blank line has no representative");
    }

    @Test
    void aLongDocumentDrawsOnePixelRowForEveryLineThatSharesIt() {
        int total = 20_000;
        double rowHeight = 900.0 / total; // a 900 px column
        assertTrue(MinimapRows.aggregated(rowHeight));
        assertEquals(900, MinimapRows.rowCount(total, rowHeight));
        assertEquals(0, MinimapRows.rowOf(0, rowHeight, 900));
        assertEquals(0, MinimapRows.rowOf(21, rowHeight, 900));
        assertEquals(1, MinimapRows.rowOf(23, rowHeight, 900));
        assertEquals(899, MinimapRows.rowOf(total - 1, rowHeight, 900), "the last line lands on the last row");

        int[] fetched = {0};
        MinimapRows.RunList runs = new MinimapRows.RunList();
        String[] doc = lines(total);
        MinimapRows.plan(
                total,
                rowHeight,
                4,
                COLUMNS,
                i -> doc[i].length(),
                i -> {
                    fetched[0]++;
                    return doc[i];
                },
                runs);
        assertEquals(900, fetched[0], "one text fetch per pixel row, not per line");
        int maxRow = 0;
        for (int k = 0; k < runs.size(); k++) {
            maxRow = Math.max(maxRow, runs.row(k));
        }
        assertEquals(899, maxRow);
    }

    @Test
    void theLongestLineStandsForItsRow() {
        // Five lines per row; the longest of each group is the one drawn.
        int[] lengths = {3, 9, 0, 4, 1, 7, 2, 2, 2, 8};
        int[] rep = MinimapRows.representatives(10, 0.2, i -> lengths[i]);
        assertArrayEquals(new int[] {1, 9}, rep);
        int[] blank = MinimapRows.representatives(10, 0.2, i -> i < 5 ? 0 : 1);
        assertArrayEquals(new int[] {-1, 5}, blank, "a row of blank lines draws nothing");
    }

    @Test
    void runsAreTheNonWhitespaceSpansInColumns() {
        MinimapRows.RunList runs = new MinimapRows.RunList();
        assertEquals(10, MinimapRows.scanRuns("int x = 1;", 4, COLUMNS, 7, runs), "a line that fits is scanned whole");
        List<int[]> t = triples(runs);
        assertEquals(4, t.size());
        assertArrayEquals(new int[] {7, 0, 3}, t.get(0));
        assertArrayEquals(new int[] {7, 4, 5}, t.get(1));
        assertArrayEquals(new int[] {7, 6, 7}, t.get(2));
        assertArrayEquals(new int[] {7, 8, 10}, t.get(3));

        runs.clear();
        MinimapRows.scanRuns("\tif (a)", 4, COLUMNS, 0, runs);
        assertArrayEquals(new int[] {0, 4, 6}, triples(runs).get(0), "a tab advances by the tab size");
        assertArrayEquals(new int[] {0, 7, 10}, triples(runs).get(1));

        runs.clear();
        assertEquals(0, MinimapRows.scanRuns("", 4, COLUMNS, 0, runs));
        assertEquals(0, runs.size());
        MinimapRows.scanRuns("   ", 4, COLUMNS, 0, runs);
        assertEquals(0, runs.size(), "whitespace alone draws nothing");
    }

    @Test
    void aRowStopsScanningAtTheRightEdge() {
        MinimapRows.RunList runs = new MinimapRows.RunList();
        String minified = "x=1;".repeat(12_500); // 50,000 characters, one run
        int scanned = MinimapRows.scanRuns(minified, 4, COLUMNS, 0, runs);
        assertTrue(scanned < 1000, "a 110-column row examined " + scanned + " of 50,000 characters");
        assertEquals(1, runs.size());
        assertArrayEquals(new int[] {0, 0, COLUMNS}, triples(runs).get(0), "the run is clipped to the edge");

        runs.clear();
        MinimapRows.scanRuns(" ".repeat(COLUMNS + 5) + "late", 4, COLUMNS, 0, runs);
        assertEquals(0, runs.size(), "a run that starts past the edge is not drawn");
    }

    @Test
    void twoPlansOfTheSameShapeCompareEqualAndADifferentOneDoesNot() {
        String[] doc = lines(300);
        MinimapRows.RunList a = plan(doc, 3.0, 4);
        MinimapRows.RunList b = plan(doc, 3.0, 4);
        assertTrue(a.sameAs(b));
        assertTrue(a.size() > 0);

        String[] retyped = doc.clone();
        retyped[10] = retyped[10].replace("compute", "COMPUTE"); // same runs, other letters
        assertTrue(a.sameAs(plan(retyped, 3.0, 4)), "a change inside a run draws the same picture");

        String[] moved = doc.clone();
        moved[10] = moved[10] + " more";
        assertFalse(a.sameAs(plan(moved, 3.0, 4)), "a new run is a new picture");

        MinimapRows.RunList copy = new MinimapRows.RunList();
        copy.copyFrom(a);
        assertTrue(copy.sameAs(a));
        copy.clear();
        assertFalse(copy.sameAs(a));
    }

    @Test
    void aRunListGrowsPastItsInitialCapacity() {
        MinimapRows.RunList runs = new MinimapRows.RunList();
        for (int i = 0; i < 5000; i++) {
            runs.add(i, i % 100, i % 100 + 1);
        }
        assertEquals(5000, runs.size());
        assertEquals(4999, runs.row(4999));
        assertEquals(99, runs.startColumn(4999));
        assertEquals(100, runs.endColumn(4999));
    }
}
