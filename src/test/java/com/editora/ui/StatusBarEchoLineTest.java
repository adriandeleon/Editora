package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** The status-bar echo shows a single line only — multi-line messages must not grow the status bar. */
class StatusBarEchoLineTest {

    @Test
    void plainSingleLinePassesThrough() {
        assertEquals("Saved Test.java", StatusBar.echoLine("Saved Test.java"));
    }

    @Test
    void nullAndEmptyBecomeEmpty() {
        assertEquals("", StatusBar.echoLine(null));
        assertEquals("", StatusBar.echoLine(""));
    }

    @Test
    void multiLineKeepsFirstLineWithEllipsis() {
        assertEquals(
                "Debug error: Compilation failed: …",
                StatusBar.echoLine("Debug error: Compilation failed: \n/x/DebugDemo.java:4: error\nmore"));
    }

    @Test
    void carriageReturnAlsoCuts() {
        assertEquals("first …", StatusBar.echoLine("first\r\nsecond"));
    }

    @Test
    void overlongSingleLineIsCapped() {
        String line = "x".repeat(StatusBar.MAX_ECHO_CHARS + 50);
        String shown = StatusBar.echoLine(line);
        assertEquals(StatusBar.MAX_ECHO_CHARS + 2, shown.length()); // cap + " …"
        assertEquals("x …", shown.substring(StatusBar.MAX_ECHO_CHARS - 1));
    }

    @Test
    void trailingWhitespaceBeforeNewlineIsStripped() {
        assertEquals("done …", StatusBar.echoLine("done   \nrest"));
    }
}
