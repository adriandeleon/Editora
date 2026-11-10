package com.editora.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.editora.git.GitRecentChanges;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Code Metrics card's measures (Map code browser gap 15): lines, comments, complexity, hotspots. */
class ProjectMapMetricsTest {

    private static ProjectMapMetrics.Measure measure(String rel, String text) {
        return Objects.requireNonNull(ProjectMapMetrics.measure(rel, text));
    }

    @Test
    void aJavaFilesLinesCommentsAndDecisionPoints() {
        ProjectMapMetrics.Measure java = measure("A.java", """
                /*
                 * A header.
                 */
                package a;

                // a comment
                class A {
                    /** One line. */
                    int f(int x) {
                        if (x > 0 && x < 9 || x == 42) { // if in a comment does not count
                            return x > 1 ? 1 : 0;
                        }
                        String s = "if while for";
                        for (int i = 0; i < x; i++) {}
                        switch (x) {
                            case 1 -> {}
                            default -> {}
                        }
                        try {} catch (Exception e) {}
                        return 0; /* trailing
                        block */
                    }
                }
                """);
        assertEquals("Java", java.language());
        assertEquals(23, java.lines());
        assertEquals(6, java.comments(), "the header's three, the line comment, the Javadoc, the block's last line");
        assertEquals(16, java.code());
        assertEquals(7, java.complexity(), "if, &&, ||, ?:, for, case, catch; not the words in the string or comment");
    }

    @Test
    void otherLanguagesCommentsAndDecisions() {
        ProjectMapMetrics.Measure python = measure(
                "a.py",
                "\"\"\"Module doc.\n\nMore.\n\"\"\"\n# note\ndef f(x):\n    '''One line.'''\n"
                        + "    if x and not x or x:\n        return [y for y in x if y]\n    elif x:\n        pass\n");
        assertEquals("Python", python.language());
        assertEquals(6, python.comments());
        assertEquals(5, python.code());
        assertEquals(6, python.complexity(), "if, and, or, the comprehension's for and if, elif");

        ProjectMapMetrics.Measure lua = measure("a.lua", "-- c\nif a and b then end\n");
        assertEquals(1, lua.comments());
        assertEquals(2, lua.complexity());
        ProjectMapMetrics.Measure clojure = measure("a.clj", "; c\n(when x (if y 1 2))\n");
        assertEquals(1, clojure.comments());
        assertEquals(2, clojure.complexity());
        ProjectMapMetrics.Measure rust = measure("a.rs", "#[derive(Debug)]\nstruct A;\n");
        assertEquals(2, rust.code(), "an attribute is code, not a comment");
        ProjectMapMetrics.Measure ruby = measure("a.rb", "x = 1 unless y # if\n#{not a comment}\n");
        assertEquals(1, ruby.complexity());
        ProjectMapMetrics.Measure open = measure("b.c", "int x; /* opens\nstill */ int y;\n");
        assertEquals(2, open.code(), "code after a block comment's end is code");
        assertEquals(1, measure("c.c", "/* one */\n").comments());
        assertEquals(1, measure("d.c", "/* one */ int z;\n").code());
        assertEquals(0, measure("e.c", "").lines());
        assertEquals(2, measure("f.c", "a;\n\n").lines(), "a last newline starts no line");

        assertNull(ProjectMapMetrics.measure("notes.md", "# Title"));
        assertNull(ProjectMapMetrics.languageOf("Makefile"));
        assertNull(ProjectMapMetrics.languageOf("dir.c/file"));
        assertEquals(
                "C++",
                Objects.requireNonNull(ProjectMapMetrics.languageOf("x.HPP")).name());
        assertTrue(ProjectMapMetrics.languageNames().contains("Kotlin"));
    }

    private static ProjectMapMetrics sample() {
        Map<String, ProjectMapMetrics.Measure> files = new LinkedHashMap<>();
        files.put("core/Big.java", new ProjectMapMetrics.Measure("Java", 300, 250, 20, 40));
        files.put("core/Small.java", new ProjectMapMetrics.Measure("Java", 40, 30, 5, 2));
        files.put("ui/View.kt", new ProjectMapMetrics.Measure("Kotlin", 120, 100, 10, 20));
        files.put("build.py", new ProjectMapMetrics.Measure("Python", 30, 20, 2, 4));
        return new ProjectMapMetrics(files, true);
    }

    private static GitRecentChanges.Result history(Map<String, Integer> files, Map<String, Integer> folders) {
        Map<String, GitRecentChanges.Stat> f = new LinkedHashMap<>();
        files.forEach((rel, n) -> f.put(rel, new GitRecentChanges.Stat(n, 0, "Ada")));
        Map<String, GitRecentChanges.Stat> d = new LinkedHashMap<>();
        folders.forEach((rel, n) -> d.put(rel, new GitRecentChanges.Stat(n, 0, "Ada")));
        return new GitRecentChanges.Result("abc", false, 10, false, f, d, Map.of(), 0, "");
    }

    @Test
    void totalsRowsAndHotspots() {
        ProjectMapMetrics metrics = sample();
        assertTrue(metrics.truncated());
        assertEquals(4, metrics.fileCount());
        assertEquals(
                30, Objects.requireNonNull(metrics.measure("core/Small.java")).code());

        ProjectMapMetrics.Totals all = metrics.totals("");
        assertEquals(4, all.files());
        assertEquals(400, all.code());
        assertEquals(37, all.comments());
        assertEquals(66, all.complexity());
        assertEquals(
                List.of("Java", "Kotlin", "Python"), List.copyOf(all.languages().keySet()));
        assertEquals(280, all.languages().get("Java"));
        assertEquals(2, metrics.totals("core").files());

        // No history: the most complex first, no changes.
        List<ProjectMapMetrics.Row> plain = metrics.rows("", false, GitRecentChanges.Result.NONE);
        assertEquals(
                List.of("core/Big.java", "ui/View.kt", "build.py", "core/Small.java"),
                plain.stream().map(ProjectMapMetrics.Row::rel).toList());
        assertEquals(-1, plain.get(0).changes());
        assertEquals(0, plain.get(0).hotspot());

        // History: Small changes most, Big a little; the hottest is complex and changed.
        GitRecentChanges.Result changes = history(
                Map.of("core/Big.java", 5, "core/Small.java", 10, "ui/View.kt", 10), Map.of("core", 12, "ui", 10));
        List<ProjectMapMetrics.Row> hot = metrics.rows("", false, changes);
        assertEquals(
                List.of("core/Big.java", "ui/View.kt", "core/Small.java", "build.py"),
                hot.stream().map(ProjectMapMetrics.Row::rel).toList(),
                "Big (40/40 × 5/10) ties View (20/40 × 10/10) at 50: the more complex first");
        assertEquals(
                List.of(50, 50, 5, 0),
                hot.stream().map(ProjectMapMetrics.Row::hotspot).toList());

        // Parts: core/ and ui/ summed, build.py on its own; folders' commits are their own.
        List<ProjectMapMetrics.Row> parts = metrics.rows("", true, changes);
        ProjectMapMetrics.Row core = parts.stream()
                .filter(row -> row.rel().equals("core"))
                .findFirst()
                .orElseThrow();
        assertTrue(core.folder());
        assertEquals(2, core.files());
        assertEquals(280, core.code());
        assertEquals(42, core.complexity());
        assertEquals(12, core.changes());
        assertEquals(100, core.hotspot());
        assertEquals(
                0,
                parts.stream()
                        .filter(row -> row.rel().equals("build.py"))
                        .findFirst()
                        .orElseThrow()
                        .changes());

        assertEquals(
                List.of("core/Big.java", "core/Small.java"),
                metrics.rows("core", true, changes).stream()
                        .map(ProjectMapMetrics.Row::rel)
                        .toList());
        GitRecentChanges.Result failed =
                new GitRecentChanges.Result("abc", false, 0, false, Map.of(), Map.of(), Map.of(), 0, "boom");
        assertEquals(-1, metrics.rows("", false, failed).get(0).changes(), "a failed read is no history");
        assertEquals(0, ProjectMapMetrics.EMPTY.fileCount());
    }

    @Test
    void hotspotAndPercentArithmetic() {
        assertEquals(100, ProjectMapMetrics.hotspot(10, 4, 10, 4));
        assertEquals(25, ProjectMapMetrics.hotspot(5, 2, 10, 4));
        assertEquals(0, ProjectMapMetrics.hotspot(5, 0, 10, 4));
        assertEquals(0, ProjectMapMetrics.hotspot(0, 3, 10, 4));
        assertEquals(0, ProjectMapMetrics.hotspot(5, 3, 0, 0));
        assertEquals(33, ProjectMapMetrics.percent(1, 3));
        assertEquals(0, ProjectMapMetrics.percent(1, 0));
    }
}
