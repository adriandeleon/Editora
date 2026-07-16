package com.editora.todo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoCommentTest {

    /** Parses {@code text}, locating the keyword by substring (its first occurrence). */
    private static TodoComment parse(String text, String keyword) {
        int s = text.indexOf(keyword);
        return TodoComment.parse(text, s, s + keyword.length());
    }

    @Test
    void keywordOnly() {
        TodoComment c = parse("// TODO clean this up", "TODO");
        assertEquals("TODO", c.keyword());
        assertNull(c.tag());
        assertNull(c.priority());
        assertEquals("clean this up", c.description());
        assertFalse(c.hasTag());
        assertFalse(c.hasPriority());
    }

    @Test
    void fullFormat() {
        String text = "// TODO [auth] (high) token refresh races on logout";
        TodoComment c = parse(text, "TODO");
        assertEquals("auth", c.tag());
        assertEquals("high", c.priority());
        assertEquals("token refresh races on logout", c.description());
        // spans include the brackets/parens
        assertEquals("[auth]", text.substring(c.tagStart(), c.tagEnd()));
        assertEquals("(high)", text.substring(c.priorityStart(), c.priorityEnd()));
        assertEquals("token refresh races on logout", text.substring(c.descriptionStart(), c.descriptionEnd()));
    }

    @Test
    void tagOnly() {
        TodoComment c = parse("// FIXME [billing] refund webhook double-fires", "FIXME");
        assertEquals("billing", c.tag());
        assertNull(c.priority());
        assertEquals("refund webhook double-fires", c.description());
    }

    @Test
    void priorityOnly() {
        TodoComment c = parse("# HACK (medium) re-hash on every call", "HACK");
        assertNull(c.tag());
        assertEquals("medium", c.priority());
        assertEquals("re-hash on every call", c.description());
    }

    @Test
    void colonAfterKeywordIsTolerated() {
        TodoComment c = parse("// TODO: [api] (low) add retry", "TODO");
        assertEquals("api", c.tag());
        assertEquals("low", c.priority());
        assertEquals("add retry", c.description());
    }

    @Test
    void priorityIsCaseInsensitiveAndNormalizedToLowercase() {
        assertEquals("critical", parse("TODO (CRITICAL) boom", "TODO").priority());
        assertEquals("high", parse("TODO (High) x", "TODO").priority());
    }

    @Test
    void unknownParenIsDescriptionNotPriority() {
        TodoComment c = parse("// TODO (later) revisit this (maybe)", "TODO");
        assertNull(c.priority());
        assertEquals("(later) revisit this (maybe)", c.description());
    }

    @Test
    void emptyBracketsAreNotATag() {
        TodoComment c = parse("// TODO [] nothing here", "TODO");
        assertNull(c.tag());
        assertEquals("[] nothing here", c.description());
    }

    @Test
    void noDescription() {
        TodoComment c = parse("// TODO [ui] (high)", "TODO");
        assertEquals("ui", c.tag());
        assertEquals("high", c.priority());
        assertEquals("", c.description());
        assertEquals(-1, c.descriptionStart());
    }

    @Test
    void priorityRankOrdersUrgentFirst() {
        assertTrue(parse("TODO (critical) x", "TODO").priorityRank()
                < parse("TODO (low) x", "TODO").priorityRank());
        assertEquals(
                TodoComment.PRIORITY_ORDER.size(), parse("TODO plain", "TODO").priorityRank()); // no priority = last
    }

    @Test
    void closerStartFindsOnlyATrailingTerminator() {
        assertEquals(10, TodoComment.closerStart("/* TODO x */"));
        assertEquals(10, TodoComment.closerStart("/* TODO x */  "), "trailing spaces do not hide it");
        assertEquals(22, TodoComment.closerStart("<!-- TODO [ui] polish -->"));
        assertEquals(-1, TodoComment.closerStart("// TODO handle the */ token"), "mid-line is not a terminator");
        assertEquals(-1, TodoComment.closerStart("// TODO plain"));
        assertEquals(-1, TodoComment.closerStart(""));
        assertEquals(-1, TodoComment.closerStart(null));
    }
}
