package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.BreakpointStore;
import com.editora.config.ExceptionBreakpoint;
import com.editora.dap.DapModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExceptionBreakpointsTest {

    private static ExceptionBreakpoint bp(String language, String type) {
        return new ExceptionBreakpoint(language, type, "", true);
    }

    @Test
    void aTypeIsMatchedWholeOrByItsLastPart() {
        assertEquals(
                List.of("java.lang.NullPointerException", "NullPointerException"),
                ExceptionBreakpoints.typeNames("java.lang.NullPointerException"));
        assertEquals(List.of("KeyError"), ExceptionBreakpoints.typeNames("KeyError"));
        assertEquals(
                List.of("std::runtime_error", "runtime_error"), ExceptionBreakpoints.typeNames("std::runtime_error"));
        assertEquals(List.of("a/b/Thing", "Thing"), ExceptionBreakpoints.typeNames("a/b/Thing"));
        assertEquals(List.of(""), ExceptionBreakpoints.typeNames(null));

        List<ExceptionBreakpoint> all = List.of(
                bp("java", "NullPointerException"),
                bp("java", "java.io.*, !java.io.EOFException"),
                bp("python", "KeyError"),
                new ExceptionBreakpoint("java", "IllegalStateException", "", false));
        assertEquals(
                1,
                ExceptionBreakpoints.matching(all, "java", "java.lang.NullPointerException")
                        .size());
        assertEquals(
                1,
                ExceptionBreakpoints.matching(all, "java", "java.io.FileNotFoundException")
                        .size());
        assertTrue(
                ExceptionBreakpoints.matching(all, "java", "java.io.EOFException")
                        .isEmpty(),
                "excluded");
        assertTrue(
                ExceptionBreakpoints.matching(all, "java", "java.lang.IllegalStateException")
                        .isEmpty(),
                "off");
        assertTrue(ExceptionBreakpoints.matching(all, "java", "KeyError").isEmpty(), "another language's");
        assertEquals(
                1,
                ExceptionBreakpoints.matching(List.of(bp("python", "")), "python", "ValueError")
                        .size(),
                "any");
        assertEquals(2, ExceptionBreakpoints.enabled(all, "java").size(), "the one turned off is not");
    }

    @Test
    void aConditionHoldsUnlessItIsFalseZeroOrNothing() {
        for (String yes : List.of("true", "True", "1", "'x'", "[1]", "{a: 1}")) {
            assertTrue(ExceptionBreakpoints.holds(yes), yes);
        }
        for (String no : List.of("false", "False", "0", "None", "null", "nil", "undefined", "", " ")) {
            assertFalse(ExceptionBreakpoints.holds(no), no);
        }
        assertFalse(ExceptionBreakpoints.holds(null));
    }

    @Test
    void anUncaughtStopAndAnExceptionPassingUp() {
        assertTrue(ExceptionBreakpoints.uncaught("unhandled"));
        assertTrue(ExceptionBreakpoints.uncaught("userUnhandled"));
        assertFalse(ExceptionBreakpoints.uncaught("always"));
        assertFalse(ExceptionBreakpoints.uncaught(""));

        DapModels.StackFrameInfo risky = frame(1, "risky");
        DapModels.StackFrameInfo caller = frame(2, "<module>");
        assertTrue(ExceptionBreakpoints.propagating(List.of(risky, caller), List.of(frame(9, "<module>"))));
        assertFalse(
                ExceptionBreakpoints.propagating(List.of(risky, caller), List.of(risky, caller)), "not further out");
        assertFalse(ExceptionBreakpoints.propagating(List.of(risky, caller), List.of(frame(3, "other"))));
        assertFalse(ExceptionBreakpoints.propagating(List.of(), List.of(caller)));
    }

    @Test
    void anExceptionPassingUpIsToldFromTheTopFramesOfDeepStacks() {
        // Stops fetch the top of the stack: 3 of 100 frames, then 3 of 99.
        List<DapModels.StackFrameInfo> before = List.of(frame(1, "inner"), frame(2, "middle"), frame(3, "outer"));
        List<DapModels.StackFrameInfo> now = List.of(frame(7, "middle"), frame(8, "outer"), frame(9, "main"));
        assertTrue(ExceptionBreakpoints.propagating(before, 100, now, 99));
        assertFalse(ExceptionBreakpoints.propagating(before, 100, now, 100), "not further out");
        assertFalse(ExceptionBreakpoints.propagating(before, 100, List.of(frame(7, "other"), frame(8, "outer")), 99));
        assertFalse(ExceptionBreakpoints.propagating(before, 100, now, 90), "no frame in common: cannot be told");
    }

    private static DapModels.StackFrameInfo frame(int id, String name) {
        return new DapModels.StackFrameInfo(id, name, Path.of("/app/m.py"), 1, 0, null, "", null, null);
    }

    @Test
    void eachLanguagesBreakpointsNeedItsThrownExceptionsFilter() {
        assertEquals(
                Map.of("java", List.of("caught"), "python", List.of("raised"), "javascript", List.of("all")),
                ExceptionBreakpoints.extras(List.of(
                        bp("java", "X"),
                        bp("python", "Y"),
                        bp("javascript", "Z"),
                        bp("ruby", "W"), // its adapter has no such filter: it stops where it stops
                        new ExceptionBreakpoint("go", "panic", "", false))));
        assertTrue(ExceptionBreakpoints.extras(List.of(new ExceptionBreakpoint("java", "X", "", false)))
                .isEmpty());
    }

    @Test
    void theyAreStoredPerProjectAndAnOldFileHasNone() throws Exception {
        ObjectMapper json = new ObjectMapper();
        BreakpointStore old = json.readValue("{\"schemaVersion\":10,\"byProject\":{}}", BreakpointStore.class);
        assertTrue(old.getExceptionBreakpoints().isEmpty());
        BreakpointStore store = new BreakpointStore();
        store.exceptions("p").add(new ExceptionBreakpoint(" java ", " NullPointerException ", " x > 1 ", true));
        BreakpointStore back = json.readValue(json.writeValueAsString(store), BreakpointStore.class);
        assertEquals(
                List.of(new ExceptionBreakpoint("java", "NullPointerException", "x > 1", true)), back.exceptions("p"));
        assertEquals(
                "NullPointerException  if x > 1",
                ExceptionBreakpoints.label(back.exceptions("p").getFirst(), "any"));
        assertEquals("any", ExceptionBreakpoints.label(bp("java", ""), "any"));
    }
}
