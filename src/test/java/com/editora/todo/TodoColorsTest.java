package com.editora.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoColorsTest {

    @Test
    void everyKnownPriorityHasAColor() {
        for (String p : TodoComment.PRIORITY_ORDER) {
            String c = TodoColors.priorityColor(p);
            assertNotNull(c, "priority " + p + " has a color");
            assertTrue(c.startsWith("#"), "web hex");
        }
    }

    @Test
    void nullOrUnknownPriorityHasNoColor() {
        assertNull(TodoColors.priorityColor(null));
        assertNull(TodoColors.priorityColor("someday"));
    }

    @Test
    void tagColorIsAWebHex() {
        assertTrue(TodoColors.TAG_COLOR.startsWith("#"));
    }
}
