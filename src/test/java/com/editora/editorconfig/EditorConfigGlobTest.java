package com.editora.editorconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorConfigGlobTest {

    @Test
    void starMatchesBasenameInAnyDir() {
        assertTrue(EditorConfigGlob.matches("*.py", "foo.py"));
        assertTrue(EditorConfigGlob.matches("*.py", "src/pkg/foo.py"));
        assertFalse(EditorConfigGlob.matches("*.py", "foo.pyc"));
        assertFalse(EditorConfigGlob.matches("*.py", "foo.py.bak"));
    }

    @Test
    void starDoesNotCrossSlash() {
        assertTrue(EditorConfigGlob.matches("/lib/*.js", "lib/a.js"));
        assertFalse(EditorConfigGlob.matches("/lib/*.js", "lib/sub/a.js"));
    }

    @Test
    void doubleStarCrossesSlashIncludingZero() {
        assertTrue(EditorConfigGlob.matches("/src/**/*.py", "src/a.py"));
        assertTrue(EditorConfigGlob.matches("/src/**/*.py", "src/x/y/a.py"));
        assertFalse(EditorConfigGlob.matches("/src/**/*.py", "lib/a.py"));
    }

    @Test
    void questionMark() {
        assertTrue(EditorConfigGlob.matches("a?c.txt", "abc.txt"));
        assertFalse(EditorConfigGlob.matches("a?c.txt", "ac.txt"));
        assertFalse(EditorConfigGlob.matches("a?c.txt", "a/c.txt"));
    }

    @Test
    void charClassAndNegation() {
        assertTrue(EditorConfigGlob.matches("*.[ch]", "main.c"));
        assertTrue(EditorConfigGlob.matches("*.[ch]", "main.h"));
        assertFalse(EditorConfigGlob.matches("*.[ch]", "main.o"));
        assertTrue(EditorConfigGlob.matches("*.[!ch]", "main.o"));
        assertFalse(EditorConfigGlob.matches("*.[!ch]", "main.c"));
    }

    @Test
    void braceAlternation() {
        assertTrue(EditorConfigGlob.matches("*.{js,ts,tsx}", "a.ts"));
        assertTrue(EditorConfigGlob.matches("*.{js,ts,tsx}", "a.tsx"));
        assertFalse(EditorConfigGlob.matches("*.{js,ts,tsx}", "a.py"));
        // A single-element brace is literal per the EditorConfig spec.
        assertTrue(EditorConfigGlob.matches("a{b}c", "a{b}c"));
    }

    @Test
    void numericRange() {
        assertTrue(EditorConfigGlob.matches("file{1..3}.txt", "file2.txt"));
        assertFalse(EditorConfigGlob.matches("file{1..3}.txt", "file5.txt"));
        assertTrue(EditorConfigGlob.matches("file{-1..1}.txt", "file-1.txt"));
    }

    @Test
    void anchoredVsFloating() {
        // No slash → matches in any directory.
        assertTrue(EditorConfigGlob.matches("Makefile", "sub/Makefile"));
        // Leading slash → anchored to the .editorconfig directory.
        assertTrue(EditorConfigGlob.matches("/Makefile", "Makefile"));
        assertFalse(EditorConfigGlob.matches("/Makefile", "sub/Makefile"));
    }

    @Test
    void anOversizedNumericRangeDoesNotThrow() {
        // A hostile .editorconfig section like [{1..99999999999999999999}] overflowed Long.parseLong and the
        // exception escaped matches() (its try/catch was after the pattern build), throwing all the way out of
        // EditorConfig.resolveFor — so merely OPENING any file it governed threw. It must degrade to matching
        // any integer instead.
        assertTrue(EditorConfigGlob.matches("file{1..99999999999999999999}.txt", "file7.txt"));
        assertTrue(EditorConfigGlob.matches("v{0..99999999999999999999}", "v12345"));
        assertFalse(EditorConfigGlob.matches("v{0..99999999999999999999}", "vNaN"));
    }

    @Test
    void anInRangeNumericRangeStillEnumerates() {
        assertTrue(EditorConfigGlob.matches("f{1..3}.txt", "f2.txt"));
        assertFalse(EditorConfigGlob.matches("f{1..3}.txt", "f9.txt"));
    }
}
