package com.editora.todo;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TodoGroupingTest {

    /** Builds a match whose line text is a structured comment, parsed from the given keyword. */
    private static TodoGrouping.Entry entry(String file, int line, String keyword, String rest) {
        String lineText = keyword + " " + rest;
        TodoComment parsed = TodoComment.parse(lineText, 0, keyword.length());
        TodoMatch m = new TodoMatch(0, keyword.length(), line, 1, lineText, keyword, "#fff", parsed);
        return new TodoGrouping.Entry(Path.of(file), m);
    }

    @Test
    void byPriorityOrdersUrgentFirstNoPriorityLast() {
        List<TodoGrouping.Entry> entries = List.of(
                entry("a.java", 1, "TODO", "(low) x"),
                entry("a.java", 2, "TODO", "(critical) y"),
                entry("a.java", 3, "TODO", "no priority here"),
                entry("a.java", 4, "FIXME", "(high) z"));
        List<TodoGrouping.Group> groups = TodoGrouping.group(entries, TodoGrouping.GroupBy.PRIORITY, null);
        assertEquals(
                List.of("critical", "high", "low", ""),
                groups.stream().map(TodoGrouping.Group::label).toList());
    }

    @Test
    void byTagAlphabeticalUntaggedLast() {
        List<TodoGrouping.Entry> entries = List.of(
                entry("a.java", 1, "TODO", "[zeta] x"),
                entry("a.java", 2, "TODO", "[auth] y"),
                entry("a.java", 3, "TODO", "no tag"));
        List<TodoGrouping.Group> groups = TodoGrouping.group(entries, TodoGrouping.GroupBy.TAG, null);
        assertEquals(
                List.of("auth", "zeta", ""),
                groups.stream().map(TodoGrouping.Group::label).toList());
    }

    @Test
    void byKeywordUsesDefaultOrder() {
        List<TodoGrouping.Entry> entries = List.of(
                entry("a.java", 1, "XXX", "x"), entry("a.java", 2, "TODO", "y"), entry("a.java", 3, "FIXME", "z"));
        List<TodoGrouping.Group> groups = TodoGrouping.group(entries, TodoGrouping.GroupBy.KEYWORD, null);
        assertEquals(
                List.of("TODO", "FIXME", "XXX"),
                groups.stream().map(TodoGrouping.Group::label).toList());
    }

    @Test
    void byFilePutsActiveFileFirstAndKeepsFileOnGroup() {
        List<TodoGrouping.Entry> entries = List.of(
                entry("a.java", 1, "TODO", "x"), entry("b.java", 2, "TODO", "y"), entry("a.java", 3, "TODO", "z"));
        List<TodoGrouping.Group> groups = TodoGrouping.group(entries, TodoGrouping.GroupBy.FILE, Path.of("b.java"));
        assertEquals("b.java", groups.get(0).label(), "active file first");
        assertEquals(Path.of("b.java"), groups.get(0).file());
        assertEquals(2, groups.size());
    }

    @Test
    void emptyInputYieldsNoGroups() {
        assertEquals(List.of(), TodoGrouping.group(List.of(), TodoGrouping.GroupBy.PRIORITY, null));
    }
}
