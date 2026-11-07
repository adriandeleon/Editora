package com.editora.ui;

import java.util.List;

import com.editora.i18n.Messages;
import com.editora.markdown.MarkdownLint;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The JSON Lines squiggles: one error mark per bad line, in the UI language. */
class JsonLinesMarksTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void eachBadLineIsOneErrorMark() {
        List<MarkdownLint.Diagnostic> marks = JsonLinesMarks.marks("{\"a\":1}\n{\"a\":}\n{} {}\n");
        assertEquals(2, marks.size());
        MarkdownLint.Diagnostic invalid = marks.get(0);
        assertEquals(2, invalid.line());
        assertEquals(1, invalid.column());
        assertEquals(6, invalid.length());
        assertTrue(invalid.isError());
        assertTrue(invalid.message().startsWith("Not valid JSON at column 6: "), invalid.message());
        MarkdownLint.Diagnostic trailing = marks.get(1);
        assertEquals(3, trailing.line());
        assertEquals(4, trailing.column());
        assertEquals(2, trailing.length());
        assertTrue(trailing.message().startsWith("One JSON value per line"), trailing.message());
    }

    @Test
    void aCleanFileHasNoMarks() {
        assertEquals(List.of(), JsonLinesMarks.marks("{\"a\":1}\n[2]\n\n"));
    }
}
