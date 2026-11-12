package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link LogpointMessage}: a logpoint's message split into text and {@code {expression}} parts, and filled in. */
class LogpointMessageTest {

    private static LogpointMessage.Part text(String s) {
        return new LogpointMessage.Part(s, false);
    }

    private static LogpointMessage.Part expr(String s) {
        return new LogpointMessage.Part(s, true);
    }

    @Test
    void textAndExpressionsAreSplitInOrder() {
        assertEquals(
                List.of(expr("item.Name"), text(": total = "), expr("total")),
                LogpointMessage.parse("{item.Name}: total = {total}"));
        assertEquals(List.of(text("no values here")), LogpointMessage.parse("no values here"));
    }

    @Test
    void bracesNestInsideAnExpression() {
        assertEquals(
                List.of(text("made "), expr("new { a = 1 }.a"), text("!")),
                LogpointMessage.parse("made {new { a = 1 }.a}!"));
    }

    @Test
    void anUnclosedOrEmptyPairIsText() {
        assertEquals(List.of(text("a {b")), LogpointMessage.parse("a {b"));
        assertEquals(List.of(text("set {} and { }")), LogpointMessage.parse("set {} and { }"));
        assertEquals(List.of(text("} then "), expr("x")), LogpointMessage.parse("} then {x}"));
    }

    @Test
    void fillPutsEachValueInItsPlace() {
        List<LogpointMessage.Part> parts = LogpointMessage.parse("{a} + {b} = {a + b}");
        assertEquals("1 + 2 = 3", LogpointMessage.fill(parts, List.of("1", "2", "3")));
        assertEquals("1 +  = ", LogpointMessage.fill(parts, List.of("1")), "a missing value is left empty");
    }
}
