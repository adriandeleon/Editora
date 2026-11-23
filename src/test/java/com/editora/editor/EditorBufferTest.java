package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorBufferTest {

    @Test
    void detectsLineEndings() {
        assertEquals("LF", EditorBuffer.detectLineEnding("a\nb\nc"));
        assertEquals("CRLF", EditorBuffer.detectLineEnding("a\r\nb"));
        assertEquals("LF", EditorBuffer.detectLineEnding(""));
        assertEquals("LF", EditorBuffer.detectLineEnding(null));
        // A mix counts as CRLF (any Windows ending present).
        assertEquals("CRLF", EditorBuffer.detectLineEnding("a\nb\r\nc"));
    }

    @Test
    void theBlameColumnMeasuresEachDistinctLabelOnce() {
        var ann = new com.editora.editor.git.BlameInfo("Ann", "2026-01-02", "", "", "a1");
        var bob = new com.editora.editor.git.BlameInfo("Bob", "2026-02-03", "", "", "b2");
        java.util.List<com.editora.editor.git.BlameInfo> lines = new java.util.ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            lines.add(i % 3 == 0 ? bob : ann);
        }
        lines.add(null);
        assertEquals(java.util.Set.of("Ann  2026-01-02", "Bob  2026-02-03"), EditorBuffer.distinctBlameLabels(lines));
    }
}
