package com.editora.editor.markdown;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The longest fenced code line, which sizes the hover card and the completion documentation (C106). */
class CodeFencesTest {

    @Test
    void onlyLinesInsideFencesCount() {
        assertEquals(0, CodeFences.longestLine(null));
        assertEquals(0, CodeFences.longestLine("a long line of prose outside any fence at all"));
        assertEquals(6, CodeFences.longestLine("prose prose prose\n```py\nx = 1\nabcdef\n```\nmore prose here"));
        assertEquals(3, CodeFences.longestLine("~~~\nabc\n~~~"));
    }
}
