package com.editora.snippet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

/** Tests snippet variable resolution (fixed clock so date/time are deterministic). */
class VariableResolverTest {

    private VariableResolver resolver() {
        return new VariableResolver("App.java", "/home/me/src", "/home/me/src/App.java",
                "selected", "clip", 4, "  return x;", LocalDateTime.of(2026, 6, 1, 9, 8, 7));
    }

    @Test
    void fileAndDirectory() {
        VariableResolver r = resolver();
        assertEquals("App.java", r.resolve("TM_FILENAME"));
        assertEquals("App", r.resolve("TM_FILENAME_BASE"));
        assertEquals("/home/me/src", r.resolve("TM_DIRECTORY"));
        assertEquals("/home/me/src/App.java", r.resolve("TM_FILEPATH"));
    }

    @Test
    void selectionClipboardAndLine() {
        VariableResolver r = resolver();
        assertEquals("selected", r.resolve("TM_SELECTED_TEXT"));
        assertEquals("selected", r.resolve("SELECTION"));
        assertEquals("clip", r.resolve("CLIPBOARD"));
        assertEquals("4", r.resolve("TM_LINE_INDEX"));
        assertEquals("5", r.resolve("TM_LINE_NUMBER"));
        assertEquals("  return x;", r.resolve("TM_CURRENT_LINE"));
    }

    @Test
    void dateAndTime() {
        VariableResolver r = resolver();
        assertEquals("2026", r.resolve("CURRENT_YEAR"));
        assertEquals("06", r.resolve("CURRENT_MONTH"));
        assertEquals("01", r.resolve("CURRENT_DATE"));
        assertEquals("09", r.resolve("CURRENT_HOUR"));
        assertEquals("08", r.resolve("CURRENT_MINUTE"));
        assertEquals("07", r.resolve("CURRENT_SECOND"));
    }

    @Test
    void unknownVariableIsNull() {
        assertNull(resolver().resolve("NOPE"));
    }
}
