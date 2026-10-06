package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    /** D2-1: a container's children are shown — and, when the adapter reports the count, fetched — by page. */
    @Test
    void childrenAreShownAPageAtATime() {
        assertEquals(false, DebugValues.fetchedByPage(0), "no count reported: ask for everything");
        assertEquals(false, DebugValues.fetchedByPage(DebugValues.PAGE_SIZE));
        assertEquals(true, DebugValues.fetchedByPage(65_536));
        assertEquals(DebugValues.PAGE_SIZE, DebugValues.nextPage(0, 65_536));
        assertEquals(36, DebugValues.nextPage(65_500, 65_536));
        assertEquals(0, DebugValues.nextPage(65_536, 65_536));
    }

    /** D2-2: a frame source that is not a file still gets a readable name. */
    @Test
    void sourceNameOfAUriIsItsClassFile() {
        assertEquals("util.py", DebugValues.sourceName(java.nio.file.Path.of("/proj/util.py")));
        assertEquals(
                "Thread.java",
                DebugValues.sourceName(
                        java.nio.file.Path.of(
                                "jdt:/contents/java.base/java.lang/Thread.java?=myapp/%5C/usr%5C/lib%5C/jvm%3Cjava.lang(Thread.class")));
        assertEquals("", DebugValues.sourceName(null));
    }

    /** D2-16: colour codes in program output are not text. */
    @Test
    void ansiSequencesAreStripped() {
        assertEquals("red text plain", DebugValues.stripAnsi("\u001B[31mred text\u001B[0m plain"));
        assertEquals("title", DebugValues.stripAnsi("\u001B]0;window\u0007title"));
        assertEquals("a [b] c", DebugValues.stripAnsi("a [b] c"));
    }
}
