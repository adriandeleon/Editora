package com.editora.markdown;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiTargetsTest {

    private static final Path ROOT = Path.of("/p").toAbsolutePath();

    private static Path p(String rel) {
        return ROOT.resolve(rel);
    }

    private static final WikiTargets TARGETS = new WikiTargets(
            ROOT,
            List.of(
                    p("README.md"),
                    p("docs/Setup.md"),
                    p("docs/guide.md"),
                    p("notes/guide.md"),
                    p("notes/todo.markdown"),
                    p("notes/todo.txt"),
                    p("img/diagram.png"),
                    p("a/twin.md"),
                    p("a/twin.markdown")));

    private static Path file(String doc, String target) {
        WikiTargets.Resolved r = TARGETS.resolve(p(doc), target);
        return r == null ? null : r.file();
    }

    @Test
    void aBareNameIsTheUniqueFileOfThatNameExtensionOptionalCaseInsensitive() {
        assertEquals(p("docs/Setup.md"), file("README.md", "setup"));
        assertEquals(p("docs/Setup.md"), file("README.md", "SETUP.md"));
        assertEquals(p("img/diagram.png"), file("README.md", "diagram.png"));
        assertNull(file("README.md", "diagram"), "only a Markdown file may drop its extension");
        assertEquals(p("notes/todo.markdown"), file("README.md", "todo"));
        assertEquals(p("notes/todo.txt"), file("README.md", "todo.txt"));
        assertNull(file("README.md", "missing"));
    }

    @Test
    void theDocumentsOwnFolderWinsAndAmbiguityResolvesToNothing() {
        assertEquals(p("docs/guide.md"), file("docs/Setup.md", "guide"));
        assertEquals(p("notes/guide.md"), file("notes/todo.markdown", "guide"));
        assertNull(file("README.md", "guide"), "two files of that name elsewhere: ambiguous");
        assertNull(file("a/other.md", "twin"), "two in the same folder: ambiguous");
        assertNull(file("README.md", "twin"));
    }

    @Test
    void aTargetWithAFolderIsAPathFromTheDocumentThenFromTheRoot() {
        WikiTargets.Resolved fromDoc = TARGETS.resolve(p("docs/Setup.md"), "../notes/guide");
        assertEquals(p("notes/guide.md"), fromDoc.file());
        assertEquals(p("docs"), fromDoc.base());
        WikiTargets.Resolved fromRoot = TARGETS.resolve(p("docs/Setup.md"), "notes/guide.md");
        assertEquals(p("notes/guide.md"), fromRoot.file());
        assertEquals(ROOT, fromRoot.base());
        assertNull(TARGETS.resolve(p("docs/Setup.md"), "nowhere/guide"));
    }

    @Test
    void anEmptyTargetIsTheDocumentItself() {
        assertEquals(p("README.md"), file("README.md", ""));
        assertEquals(p("unsaved.md"), file("unsaved.md", "  "));
        assertNull(TARGETS.resolve(null, "x"));
        assertNull(WikiTargets.EMPTY.resolve(p("a.md"), "x"));
    }

    @Test
    void namesAndExtensions() {
        assertTrue(WikiTargets.hasExtension("note.md"));
        assertTrue(WikiTargets.hasExtension("a/b.png"));
        assertFalse(WikiTargets.hasExtension("note"));
        assertFalse(WikiTargets.hasExtension("v1. final draft"));
        assertEquals("Setup", WikiTargets.name(p("docs/Setup.md"), false));
        assertEquals("Setup.md", WikiTargets.name(p("docs/Setup.md"), true));
        assertEquals("diagram.png", WikiTargets.name(p("img/diagram.png"), false));
        assertEquals(9, TARGETS.size());
    }
}
