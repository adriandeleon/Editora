package com.editora.todo;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoPatternsTest {

    @Test
    void defaultsAreTodoAndFixme() {
        List<TodoPattern> d = TodoPatterns.defaults();
        assertEquals(2, d.size());
        assertEquals("TODO", d.get(0).getName());
        assertEquals("FIXME", d.get(1).getName());
        assertEquals(2, TodoPatterns.compile(d).size());
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
