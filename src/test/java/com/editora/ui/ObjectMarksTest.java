package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What tells an object apart in each adapter's values (as measured), and the marks kept by it. */
class ObjectMarksTest {

    @Test
    void anObjectIsToldByItsJavaIdElseItsAddress() {
        assertEquals("id:9", ObjectMarks.identity("Object@9"));
        assertEquals("id:10", ObjectMarks.identity("ArrayList@10 size=2"));
        assertEquals("id:17", ObjectMarks.identity("int[3]@17"));
        assertEquals("id:4", ObjectMarks.identity("HashMap$Node@4 \"a\" -> 1"));
        assertEquals("0x7fadd88b4ad0", ObjectMarks.identity("<__main__.Box object at 0x7FADD88B4AD0>"));
        assertEquals("0x55555555a2a0", ObjectMarks.identity("0x55555555a2a0"), "a native pointer");
        assertNull(ObjectMarks.identity("Box"), "js-debug shows no identity");
        assertNull(ObjectMarks.identity("42"));
        assertNull(ObjectMarks.identity("0x1f"), "a small hex number is a value");
        assertNull(ObjectMarks.identity("\"Object@9\""), "a string's text is not an identity");
        assertNull(ObjectMarks.identity("'at 0x7fadd88b4ad0'"));
        assertNull(ObjectMarks.identity(null));
    }

    @Test
    void aMarkFollowsTheObjectNotTheRow() {
        ObjectMarks marks = new ObjectMarks();
        assertTrue(marks.isEmpty());
        assertTrue(marks.mark("ArrayList@10 size=2", "  the\n list  "));
        assertEquals("the list", marks.labelOf("ArrayList@10 size=3"), "the same object, grown");
        assertNull(marks.labelOf("ArrayList@11 size=2"), "another object");
        assertFalse(marks.mark("Box", "x"), "nothing to tell it by");
        assertTrue(marks.mark("ArrayList@10 size=2", "  "));
        assertTrue(marks.isEmpty(), "blank: unmarked");
        marks.mark("Object@1", "a".repeat(80));
        assertEquals(ObjectMarks.MAX_LABEL, marks.labelOf("Object@1").length());
        marks.clear();
        assertNull(marks.labelOf("Object@1"));
    }
}
