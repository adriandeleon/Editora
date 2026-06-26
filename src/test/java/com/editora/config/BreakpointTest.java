package com.editora.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the {@link Breakpoint} record (normalization, flags, withers). */
class BreakpointTest {

    @Test
    void compactCtorNullsBecomeEmptyStrings() {
        Breakpoint b = new Breakpoint(3, null, null, true, null);
        assertEquals("", b.condition());
        assertEquals("", b.logMessage());
        assertEquals("", b.lineText());
    }

    @Test
    void lineTextIsTruncatedToTheCap() {
        String huge = "x".repeat(Breakpoint.MAX_LINE_TEXT + 50);
        Breakpoint b = new Breakpoint(0, "", "", true, huge);
        assertEquals(Breakpoint.MAX_LINE_TEXT, b.lineText().length());
    }

    @Test
    void plainIsEnabledWithNoConditionOrLog() {
        Breakpoint b = Breakpoint.plain(7, "int x = 1;");
        assertEquals(7, b.line());
        assertTrue(b.enabled());
        assertFalse(b.isLogpoint());
        assertFalse(b.isConditional());
        assertEquals("int x = 1;", b.lineText());
    }

    @Test
    void flagsReflectConditionAndLog() {
        assertTrue(Breakpoint.plain(0, "").withCondition("x > 0").isConditional());
        assertTrue(Breakpoint.plain(0, "").withLogMessage("hit").isLogpoint());
    }

    @Test
    void withersReplaceOnlyTheTargetedField() {
        Breakpoint b = Breakpoint.plain(1, "line");
        assertEquals(9, b.withLine(9).line());
        assertEquals("c", b.withCondition("c").condition());
        assertEquals("m", b.withLogMessage("m").logMessage());
        assertFalse(b.withEnabled(false).enabled());
        // Untouched fields survive a wither.
        assertEquals("line", b.withLine(9).lineText());
    }

    @Test
    void recordsAreValueEqual() {
        assertEquals(Breakpoint.plain(2, "t"), Breakpoint.plain(2, "t"));
        assertEquals(List.of(Breakpoint.plain(2, "t")), List.of(Breakpoint.plain(2, "t")));
    }
}
