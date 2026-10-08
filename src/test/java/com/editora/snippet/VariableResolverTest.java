package com.editora.snippet;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests snippet variable resolution (fixed clock so date/time are deterministic). */
class VariableResolverTest {

    private VariableResolver resolver() {
        return new VariableResolver(
                "App.java",
                "/home/me/src",
                "/home/me/src/App.java",
                "selected",
                "clip",
                4,
                "  return x;",
                LocalDateTime.of(2026, 6, 1, 9, 8, 7));
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

    // --- N11: the VS Code variables that used to come out empty ---

    @Test
    void moreDateVariables() {
        VariableResolver r = resolver().withZone(java.time.ZoneOffset.ofHoursMinutes(-5, -30));
        assertEquals(
                String.valueOf(LocalDateTime.of(2026, 6, 1, 9, 8, 7)
                        .toEpochSecond(java.time.ZoneOffset.ofHoursMinutes(-5, -30))),
                r.resolve("CURRENT_SECONDS_UNIX"));
        assertEquals("-05:30", r.resolve("CURRENT_TIMEZONE_OFFSET"));
        assertEquals("+00:00", resolver().withZone(java.time.ZoneOffset.UTC).resolve("CURRENT_TIMEZONE_OFFSET"));
        assertEquals(
                LocalDateTime.of(2026, 6, 1, 9, 8, 7).format(java.time.format.DateTimeFormatter.ofPattern("EEEE")),
                r.resolve("CURRENT_DAY_NAME"));
        assertTrue(!r.resolve("CURRENT_DAY_NAME_SHORT").isEmpty());
        assertTrue(!r.resolve("CURRENT_MONTH_NAME_SHORT").isEmpty());
    }

    @Test
    void randomValuesHaveTheirVsCodeShapes() {
        VariableResolver r = resolver();
        assertTrue(r.resolve("RANDOM").matches("\\d{6}"));
        assertTrue(r.resolve("RANDOM_HEX").matches("[0-9a-f]{6}"));
        assertTrue(r.resolve("UUID").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        // One expansion sees one value, however often the variable is named.
        String twice = SnippetParser.parse("$UUID $UUID", r).text();
        assertEquals(twice.substring(0, 36), twice.substring(37));
    }

    @Test
    void workspaceWordAndCommentVariables() {
        VariableResolver r = resolver()
                .withWorkspace(java.nio.file.Path.of("/home/me"))
                .withCurrentWord("word")
                .withComments("//", "/*", "*/");
        assertEquals("me", r.resolve("WORKSPACE_NAME"));
        assertEquals(java.nio.file.Path.of("/home/me").toAbsolutePath().toString(), r.resolve("WORKSPACE_FOLDER"));
        assertEquals(java.nio.file.Path.of("src", "App.java").toString(), r.resolve("RELATIVE_FILEPATH"));
        assertEquals("word", r.resolve("TM_CURRENT_WORD"));
        assertEquals("//", r.resolve("LINE_COMMENT"));
        assertEquals("/*", r.resolve("BLOCK_COMMENT_START"));
        assertEquals("*/", r.resolve("BLOCK_COMMENT_END"));
        assertEquals("0", r.resolve("CURSOR_INDEX"));
        assertEquals("1", r.resolve("CURSOR_NUMBER"));
        // No workspace: the names are still variables (empty), and the relative path is the full one.
        assertEquals("", resolver().resolve("WORKSPACE_NAME"));
        assertEquals("/home/me/src/App.java", resolver().resolve("RELATIVE_FILEPATH"));
    }

    @Test
    void everyAdvertisedNameResolvesAndTheWordHelperFindsTheWordAtAColumn() {
        VariableResolver r = resolver();
        for (String name : VariableResolver.NAMES) {
            assertTrue(r.resolve(name) != null, name + " is listed as a variable but resolves to null");
        }
        assertEquals("return", VariableResolver.wordAt("  return x;", 5));
        assertEquals("x", VariableResolver.wordAt("  return x;", 10));
        assertEquals("", VariableResolver.wordAt("  return x;", 1));
    }
}
