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

    @Test
    void lambdasAreKeptSortedOnceAndAnOnlyWithNoneStopsOnTheLine() {
        Breakpoint b = Breakpoint.plain(2, "f(x -> x);");
        assertTrue(b.lambdas().isEmpty());
        assertTrue(b.stopsOnLine());
        assertFalse(b.hasLambdas());
        Breakpoint some = b.withLambdas(java.util.Arrays.asList(3, null, 1, 3, 0, -2, Breakpoint.MAX_LAMBDA + 1), true);
        assertEquals(List.of(1, 3), some.lambdas());
        assertTrue(some.hasLambdas());
        assertFalse(some.stopsOnLine());
        assertTrue(some.lambdasOnly());
        assertFalse(b.withLambdas(List.of(), true).lambdasOnly(), "no lambda: it stops on the line");
        assertFalse(b.withLambdas(null, true).hasLambdas());
        // Every other change keeps them.
        assertEquals(
                some.lambdas(),
                some.withLine(5).withCondition("x > 1").withEnabled(false).lambdas());
        assertTrue(some.withTemporary(true)
                .withHitCondition("2")
                .withLogMessage("m")
                .withLineText("t")
                .lambdasOnly());
    }

    @Test
    void lambdasSurviveTheFileAndAnOlderFileHasNone() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Breakpoint some = Breakpoint.plain(2, "f(x -> x);").withLambdas(List.of(2), true);
        assertEquals(some, mapper.readValue(mapper.writeValueAsString(some), Breakpoint.class));
        Breakpoint old = mapper.readValue("{\"line\":4,\"enabled\":true,\"lineText\":\"a\"}", Breakpoint.class);
        assertEquals(List.of(), old.lambdas());
        assertTrue(old.stopsOnLine());
    }

    @Test
    void aRecordingBreakpointStaysRecordingThroughTheWithersAndJson() throws Exception {
        Breakpoint b = Breakpoint.plain(4, "total += i").withRecording(true);
        assertTrue(b.recording());
        assertTrue(b.records());
        Breakpoint changed = b.withLine(9)
                .withLineText("y")
                .withCondition("i > 0")
                .withEnabled(false)
                .withHitCondition("2")
                .withTemporary(true)
                .withLambdas(List.of(1), false);
        assertTrue(changed.recording(), "every wither keeps it");
        assertFalse(changed.withRecording(false).recording());
        assertFalse(b.withLogMessage("at {i}").records(), "a logpoint never stops, so has nothing to record");
        assertFalse(Breakpoint.plain(1, "x").recording(), "a breakpoint stops by default");
        assertFalse(new Breakpoint(1, "", "", true, "x", "", false, List.of(2), true).recording());

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(b, json.readValue(json.writeValueAsString(b), Breakpoint.class));
        // breakpoints.json written before recording breakpoints existed (schema 6)
        Breakpoint old = json.readValue(
                "{\"line\":2,\"condition\":\"\",\"logMessage\":\"\",\"enabled\":true,\"lineText\":\"x\","
                        + "\"lambdas\":[1],\"lambdasOnly\":true}",
                Breakpoint.class);
        assertFalse(old.recording());
        assertEquals(List.of(1), old.lambdas());
        assertEquals(
                List.of(com.editora.i18n.Messages.tr("debug.breakpoint.recording")),
                com.editora.editor.debug.BreakpointDescription.lines(b));
    }

    @Test
    void anIdAndTheBreakpointItWaitsForAreKeptByEveryWither() throws Exception {
        Breakpoint b = Breakpoint.plain(4, "total += i").withId(" a1b2c3d4 ").withTriggeredBy("e5f6");
        assertEquals("a1b2c3d4", b.id(), "trimmed");
        assertTrue(b.isTriggered());
        Breakpoint changed = b.withLine(9)
                .withLineText("y")
                .withCondition("i > 0")
                .withLogMessage("")
                .withEnabled(false)
                .withHitCondition("2")
                .withTemporary(true)
                .withLambdas(List.of(1), false)
                .withRecording(true);
        assertEquals("a1b2c3d4", changed.id(), "every wither keeps it");
        assertEquals("e5f6", changed.triggeredBy());
        assertFalse(changed.withTriggeredBy("").isTriggered());
        assertFalse(b.withTriggeredBy("a1b2c3d4").isTriggered(), "a breakpoint never waits for itself");
        assertEquals("", Breakpoint.plain(1, "x").id(), "none until another waits for it");
        assertEquals(8, Breakpoint.newId().length());
        assertTrue(!Breakpoint.newId().equals(Breakpoint.newId()));

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(b, json.readValue(json.writeValueAsString(b), Breakpoint.class));
        // breakpoints.json written before triggered breakpoints existed (schema 7)
        Breakpoint old = json.readValue(
                "{\"line\":2,\"condition\":\"\",\"logMessage\":\"\",\"enabled\":true,\"lineText\":\"x\","
                        + "\"recording\":true}",
                Breakpoint.class);
        assertEquals("", old.id());
        assertFalse(old.isTriggered());
        assertTrue(old.recording());
        assertEquals(
                List.of(com.editora.i18n.Messages.tr("debug.breakpoint.triggeredBy", "Main.java:3")),
                com.editora.editor.debug.BreakpointDescription.lines(
                        b, id -> "e5f6".equals(id) ? "Main.java:3" : null));
        assertEquals(
                List.of(),
                com.editora.editor.debug.BreakpointDescription.lines(b, id -> null),
                "one whose trigger is gone waits for none");
    }

    @Test
    void aGroupIsKeptByEveryWitherAndItsNameTidied() throws Exception {
        Breakpoint b = Breakpoint.plain(4, "total += i")
                .withGroup("  Checkout\n  flow  ")
                .withId("a1");
        assertEquals("Checkout flow", b.group(), "one line, spaces folded");
        Breakpoint changed = b.withLine(9)
                .withLineText("y")
                .withCondition("i > 0")
                .withLogMessage("")
                .withEnabled(false)
                .withHitCondition("2")
                .withTemporary(true)
                .withLambdas(List.of(1), false)
                .withRecording(true)
                .withId("b2")
                .withTriggeredBy("c3");
        assertEquals("Checkout flow", changed.group(), "every wither keeps it");
        assertEquals("", b.withGroup(null).group());
        assertEquals(Breakpoint.MAX_GROUP, Breakpoint.groupName("x".repeat(100)).length());

        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertEquals(b, json.readValue(json.writeValueAsString(b), Breakpoint.class));
        // breakpoints.json written before groups existed (schema 8)
        Breakpoint old = json.readValue(
                "{\"line\":2,\"condition\":\"\",\"logMessage\":\"\",\"enabled\":true,\"lineText\":\"x\","
                        + "\"id\":\"q\",\"triggeredBy\":\"r\"}",
                Breakpoint.class);
        assertEquals("", old.group());
        assertEquals("r", old.triggeredBy());
        assertEquals(
                List.of(com.editora.i18n.Messages.tr("debug.breakpoint.group", "Checkout flow")),
                com.editora.editor.debug.BreakpointDescription.lines(
                        Breakpoint.plain(1, "x").withGroup("Checkout flow")));
    }
}
