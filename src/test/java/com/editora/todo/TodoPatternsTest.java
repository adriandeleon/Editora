package com.editora.todo;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPatternsTest {

    @Test
    void defaultsAreTheStandardKeywords() {
        List<TodoPattern> d = TodoPatterns.defaults();
        List<String> names = d.stream().map(TodoPattern::getName).toList();
        assertEquals(List.of("TODO", "FIXME", "HACK", "NOTE", "XXX", "DONE"), names);
        assertTrue(d.stream().allMatch(TodoPattern::isCaseSensitive), "defaults are case-sensitive");
        assertEquals(d.size(), TodoPatterns.compile(d).size(), "all defaults compile");
        assertEquals("DONE", TodoPatterns.DONE_KEYWORD);
    }

    @Test
    void skipsDisabledBlankAndInvalid() {
        List<TodoPattern> patterns = List.of(
                new TodoPattern("ok", "\\bTODO\\b", "#fff", false, true),
                new TodoPattern("disabled", "\\bX\\b", "#fff", false, false),
                new TodoPattern("blank", "  ", "#fff", false, true),
                new TodoPattern("bad", "(unclosed", "#fff", false, true));
        List<TodoPatterns.Compiled> c = TodoPatterns.compile(patterns);
        assertEquals(1, c.size());
        assertEquals("ok", c.get(0).name());
    }

    @Test
    void fallsBackToDefaultColorWhenBlank() {
        var c = TodoPatterns.compile(List.of(new TodoPattern("n", "x", "", false, true)));
        assertEquals(TodoPatterns.DEFAULT_COLOR, c.get(0).color());
    }

    @Test
    void nullListIsSafe() {
        assertTrue(TodoPatterns.compile(null).isEmpty());
    }
}
