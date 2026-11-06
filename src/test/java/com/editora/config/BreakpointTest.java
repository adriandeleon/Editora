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

    @Test
    void aHitConditionMakesItConditionalAndSurvivesTheWithersAndJson() throws Exception {
        Breakpoint b = Breakpoint.plain(4, "total += i").withHitCondition(">= 3");
        assertTrue(b.isConditional(), "drawn as conditional: it does not stop on every hit");
        assertEquals(
                ">= 3", b.withLine(9).withCondition("i > 0").withEnabled(false).hitCondition());

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(b, json.readValue(json.writeValueAsString(b), Breakpoint.class));
        // breakpoints.json written before hit counts existed
        Breakpoint old = json.readValue(
                "{\"line\":2,\"condition\":\"\",\"logMessage\":\"\",\"enabled\":true,\"lineText\":\"x\"}",
                Breakpoint.class);
        assertEquals("", old.hitCondition());
    }

    @Test
    void aTemporaryBreakpointStaysTemporaryThroughTheWithersAndJson() throws Exception {
        Breakpoint b = Breakpoint.plain(4, "total += i").withTemporary(true);
        assertTrue(b.temporary());
        Breakpoint changed = b.withLine(9)
                .withLineText("y")
                .withCondition("i > 0")
                .withLogMessage("")
                .withEnabled(false)
                .withHitCondition("2");
        assertTrue(changed.temporary(), "every wither keeps it");
        assertFalse(changed.withTemporary(false).temporary());
        assertFalse(Breakpoint.plain(1, "x").temporary(), "a breakpoint stays after a hit by default");

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(b, json.readValue(json.writeValueAsString(b), Breakpoint.class));
        // breakpoints.json written before temporary breakpoints existed
        Breakpoint old = json.readValue(
                "{\"line\":2,\"condition\":\"\",\"logMessage\":\"\",\"enabled\":true,\"lineText\":\"x\","
                        + "\"hitCondition\":\"3\"}",
                Breakpoint.class);
        assertFalse(old.temporary());
        assertEquals("3", old.hitCondition());
    }

    @Test
    void aTemporaryBreakpointSaysSoInItsDescriptionUnlessItOnlyLogs() {
        Breakpoint b = Breakpoint.plain(4, "x").withTemporary(true);
        assertEquals(
                List.of(com.editora.i18n.Messages.tr("debug.breakpoint.temporary")),
                com.editora.editor.debug.BreakpointDescription.lines(b));
        assertEquals(
                List.of(com.editora.i18n.Messages.tr("debug.breakpoint.logMessage", "at {i}")),
                com.editora.editor.debug.BreakpointDescription.lines(b.withLogMessage("at {i}")),
                "a logpoint never stops, so it is never removed");
    }
}
