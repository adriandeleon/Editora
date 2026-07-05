package com.editora.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TodoPartColorsTest {

    @Test
    void defaultsMatchTodoColors() {
        TodoPartColors d = TodoPartColors.defaults();
        assertEquals(TodoColors.TAG_COLOR, d.tag());
        assertEquals(TodoColors.priorityColor("critical"), d.critical());
        assertEquals(TodoColors.priorityColor("low"), d.low());
    }

    @Test
    void ofUsesGivenColors() {
        TodoPartColors c = TodoPartColors.of("#111111", "#222222", "#333333", "#444444", "#555555");
        assertEquals("#111111", c.tag());
        assertEquals("#222222", c.priorityColor("critical"));
        assertEquals("#333333", c.priorityColor("high"));
        assertEquals("#444444", c.priorityColor("medium"));
        assertEquals("#555555", c.priorityColor("low"));
    }

    @Test
    void ofFallsBackToDefaultForBlankOrNull() {
        TodoPartColors c = TodoPartColors.of(null, "  ", "", null, "#00FF00");
        TodoPartColors d = TodoPartColors.defaults();
        assertEquals(d.tag(), c.tag());
        assertEquals(d.critical(), c.priorityColor("critical"));
        assertEquals(d.medium(), c.priorityColor("medium"));
        assertEquals("#00FF00", c.priorityColor("low"));
    }

    @Test
    void unknownPriorityHasNoColor() {
        assertNull(TodoPartColors.defaults().priorityColor("someday"));
        assertNull(TodoPartColors.defaults().priorityColor(null));
    }
}
