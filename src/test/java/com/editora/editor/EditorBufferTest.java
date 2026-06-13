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
}
