package com.editora.ui;

import java.nio.file.Path;

import com.editora.snippet.SnippetManager.Problem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** N9: what the user is told about a snippet file that could not be loaded — the file, the reason, the line. */
class SnippetProblemMessageTest {

    private static final Path FILE = Path.of("snippets", "python.json");

    @Test
    void aSyntaxErrorNamesTheFileTheReasonAndTheLine() {
        String said = SnippetCoordinator.describe(
                new Problem(FILE, Problem.Kind.SYNTAX, "", "Unexpected character ('}' (code 125))", 12345));
        assertTrue(said.startsWith("python.json"), said);
        assertTrue(said.contains("Unexpected character") && said.contains("12345"), said);
        assertFalse(said.contains("12,345"), "a line number is not a quantity: " + said);
    }

    @Test
    void aBadEntryNamesTheEntryAndASkippedFileItsShape() {
        String entry = SnippetCoordinator.describe(new Problem(FILE, Problem.Kind.BAD_ENTRY, "Broken one", "", 7));
        assertTrue(entry.contains("python.json") && entry.contains("Broken one") && entry.contains("7"), entry);
        String shape = SnippetCoordinator.describe(new Problem(FILE, Problem.Kind.NOT_AN_OBJECT, "", "", 1));
        assertTrue(shape.contains("python.json") && shape.contains("JSON"), shape);
        String io = SnippetCoordinator.describe(new Problem(FILE, Problem.Kind.UNREADABLE, "", "Permission denied", 0));
        assertTrue(io.contains("python.json") && io.contains("Permission denied"), io);
        String noLine = SnippetCoordinator.describe(new Problem(FILE, Problem.Kind.SYNTAX, "", "bad", 0));
        assertTrue(noLine.contains("bad") && !noLine.contains("0)"), noLine);
    }
}
