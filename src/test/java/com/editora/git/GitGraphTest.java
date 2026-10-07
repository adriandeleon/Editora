package com.editora.git;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The commit graph's lane layout, from parent hashes in {@code --date-order} (children before parents). */
class GitGraphTest {

    private static GitLog.Entry commit(String hash, String... parents) {
        return new GitLog.Entry(hash, hash, hash, "a", 0L, "", List.of(parents), List.of());
    }

    private static void assertRow(GitGraph.Row row, int column, int[] through, int[] in, int[] out) {
        assertEquals(column, row.column(), "column of " + row);
        assertArrayEquals(through, row.through(), "through of " + row);
        assertArrayEquals(in, row.in(), "in of " + row);
        assertArrayEquals(out, row.out(), "out of " + row);
    }

    private static int[] lanes(int... lanes) {
        return lanes;
    }

    @Test
    void aLinearHistoryIsOneLane() {
        GitGraph graph = new GitGraph();
        List<GitGraph.Row> rows = graph.append(List.of(commit("c", "b"), commit("b", "a"), commit("a")));

        assertRow(rows.get(0), 0, lanes(), lanes(), lanes(0)); // the tip: nothing above it
        assertRow(rows.get(1), 0, lanes(), lanes(0), lanes(0));
        assertRow(rows.get(2), 0, lanes(), lanes(0), lanes()); // the root: nothing below it
        assertEquals(1, graph.width());
    }

    @Test
    void aMergeOpensALaneForItsSecondParentAndTheBranchPointClosesIt() {
        //   m        merge of f into the mainline
        //   |\
        //   | f      the side branch's commit
        //   b |      mainline commit made meanwhile
        //   |/
        //   a
        GitGraph graph = new GitGraph();
        List<GitGraph.Row> rows =
                graph.append(List.of(commit("m", "b", "f"), commit("f", "a"), commit("b", "a"), commit("a")));

        assertRow(rows.get(0), 0, lanes(), lanes(), lanes(0, 1)); // two lanes leave the merge
        assertRow(rows.get(1), 1, lanes(0), lanes(1), lanes(1)); // f in the side lane; the mainline passes by
        assertRow(rows.get(2), 0, lanes(1), lanes(0), lanes(0)); // b; f's lane (waiting for a) passes by
        assertRow(rows.get(3), 0, lanes(), lanes(0, 1), lanes()); // both lanes converge on a
        assertEquals(2, graph.width());
    }

    @Test
    void aBranchTipJoinsTheLaneAlreadyWaitingForItsParent() {
        // x and y are both children of p (--all: two branch heads). y is a tip in the first free column, and
        // its parent already has a lane to the left, which y bends into instead of opening a second one.
        GitGraph graph = new GitGraph();
        List<GitGraph.Row> rows = graph.append(List.of(commit("x", "p"), commit("y", "p"), commit("p")));

        assertRow(rows.get(0), 0, lanes(), lanes(), lanes(0));
        assertRow(rows.get(1), 1, lanes(0), lanes(), lanes(0));
        assertRow(rows.get(2), 0, lanes(), lanes(0), lanes());
        assertEquals(2, graph.width());
    }

    @Test
    void aFreedColumnIsReusedAndAnOctopusFansOut() {
        GitGraph graph = new GitGraph();
        List<GitGraph.Row> rows = graph.append(List.of(
                commit("o", "a", "b", "c"), // octopus merge
                commit("c", "r"),
                commit("b", "r"),
                commit("a", "r"),
                commit("r", "q"),
                commit("t", "q"), // a tip listed late: takes a column the octopus freed, not a fourth one
                commit("q")));

        assertRow(rows.get(0), 0, lanes(), lanes(), lanes(0, 1, 2));
        // Each arm keeps its own lane down to r, where the three converge.
        assertRow(rows.get(1), 2, lanes(0, 1), lanes(2), lanes(2));
        assertRow(rows.get(2), 1, lanes(0, 2), lanes(1), lanes(1));
        assertRow(rows.get(3), 0, lanes(1, 2), lanes(0), lanes(0));
        assertRow(rows.get(4), 0, lanes(), lanes(0, 1, 2), lanes(0));
        assertRow(rows.get(5), 1, lanes(0), lanes(), lanes(0));
        assertRow(rows.get(6), 0, lanes(), lanes(0), lanes());
        assertEquals(3, graph.width());
    }

    @Test
    void theLayoutIsTheSameHoweverTheHistoryIsPaged() {
        List<GitLog.Entry> history = syntheticHistory(600, 7);

        List<GitGraph.Row> whole = new GitGraph().append(history);
        for (int page : new int[] {1, 7, 200, 599}) {
            GitGraph paged = new GitGraph();
            List<GitGraph.Row> rows = new ArrayList<>();
            for (int from = 0; from < history.size(); from += page) {
                rows.addAll(paged.append(history.subList(from, Math.min(history.size(), from + page))));
            }
            assertEquals(whole, rows, "pages of " + page + ": lanes open at a page's end continue into the next");
        }
    }

    @Test
    void everyLaneIsContinuousFromRowToRow() {
        // What leaves a row at the bottom (pass-through lanes and the node's out-edges) is exactly what
        // enters the next row at the top (its pass-through lanes and the lanes ending in its node).
        List<GitLog.Entry> history = syntheticHistory(2_000, 11);
        List<GitGraph.Row> rows = new GitGraph().append(history);

        for (int i = 0; i + 1 < rows.size(); i++) {
            int[] bottom = union(rows.get(i).through(), rows.get(i).out());
            int[] top = union(rows.get(i + 1).through(), rows.get(i + 1).in());
            assertArrayEquals(bottom, top, "between rows " + i + " and " + (i + 1));
        }
        assertEquals(0, rows.get(0).in().length, "nothing enters the first row");
        GitGraph.Row last = rows.get(rows.size() - 1);
        assertEquals(0, last.through().length + last.out().length, "the whole history was listed: no lane left open");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(history.get(i).parents().size(), rows.get(i).out().length, "one edge down per parent");
        }
    }

    private static int[] union(int[] a, int[] b) {
        int[] all = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return Arrays.stream(all).distinct().sorted().toArray();
    }

    /**
     * A history of {@code n} commits in a valid children-first order: commit {@code i}'s parents have larger
     * indexes. Mostly linear, with side branches that fork and merge back, like a real repository.
     */
    static List<GitLog.Entry> syntheticHistory(int n, long seed) {
        java.util.Random random = new java.util.Random(seed);
        List<GitLog.Entry> commits = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            List<String> parents = new ArrayList<>();
            if (i + 1 < n) {
                // First parent: usually the next commit, sometimes a few further (a side branch's base).
                parents.add(hash(Math.min(n - 1, i + 1 + (random.nextInt(5) == 0 ? random.nextInt(6) : 0))));
                if (random.nextInt(5) == 0 && i + 2 < n) {
                    String second = hash(Math.min(n - 1, i + 2 + random.nextInt(8)));
                    if (!parents.contains(second)) {
                        parents.add(second);
                    }
                }
            }
            commits.add(new GitLog.Entry(hash(i), hash(i).substring(0, 7), "c" + i, "a", 0L, "", parents, List.of()));
        }
        return commits;
    }

    private static String hash(int i) {
        return String.format("%040x", i + 1);
    }

    @Test
    void fiveThousandCommitsAreLaidOutInWellUnderAFrame() {
        List<GitLog.Entry> history = syntheticHistory(5_000, 3);
        new GitGraph().append(history); // warm up
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long start = System.nanoTime();
            GitGraph graph = new GitGraph();
            graph.append(history);
            best = Math.min(best, System.nanoTime() - start);
            assertTrue(graph.width() > 1);
        }
        // Measured ~1 ms; the bound is loose because CI machines are shared.
        assertTrue(best < 100_000_000L, "layout of 5,000 commits took " + best / 1_000_000 + " ms");
    }
}
