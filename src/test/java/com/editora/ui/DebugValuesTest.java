package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DebugValuesTest {

    @Test
    void stringsByQuoteStyle() {
        assertEquals(DebugValues.ValueKind.STRING, DebugValues.kind("\"hello\"")); // Java / JS
        assertEquals(DebugValues.ValueKind.STRING, DebugValues.kind("'hello'")); // Python
    }

    @Test
    void numbersAcrossNotations() {
        assertEquals(DebugValues.ValueKind.NUMBER, DebugValues.kind("42"));
        assertEquals(DebugValues.ValueKind.NUMBER, DebugValues.kind("-3.14"));
        assertEquals(DebugValues.ValueKind.NUMBER, DebugValues.kind("1.5e10"));
        assertEquals(DebugValues.ValueKind.NUMBER, DebugValues.kind("0xFF"));
        assertEquals(DebugValues.ValueKind.NUMBER, DebugValues.kind("100L"));
    }

    @Test
    void booleansAcrossLanguages() {
        assertEquals(DebugValues.ValueKind.BOOLEAN, DebugValues.kind("true"));
        assertEquals(DebugValues.ValueKind.BOOLEAN, DebugValues.kind("False")); // Python
    }

    @Test
    void nullLikesAcrossLanguages() {
        assertEquals(DebugValues.ValueKind.NULL, DebugValues.kind("null"));
        assertEquals(DebugValues.ValueKind.NULL, DebugValues.kind("None"));
        assertEquals(DebugValues.ValueKind.NULL, DebugValues.kind("undefined"));
    }

    @Test
    void structuredValuesAreOther() {
        assertEquals(DebugValues.ValueKind.OTHER, DebugValues.kind("DebugDemo@1f2a"));
        assertEquals(DebugValues.ValueKind.OTHER, DebugValues.kind("[1, 2, 3]"));
        assertEquals(DebugValues.ValueKind.OTHER, DebugValues.kind(""));
        assertEquals(DebugValues.ValueKind.OTHER, DebugValues.kind(null));
        assertEquals(DebugValues.ValueKind.OTHER, DebugValues.kind("size = 3")); // not a bare number
    }
}
