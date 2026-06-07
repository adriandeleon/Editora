package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Unit tests for pure helpers in {@link MainController} (no toolkit needed). */
class MainControllerTest {

    @Test
    void repoNameFromHttpsUrl() {
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo.git"));
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo"));
    }

    @Test
    void repoNameFromScpStyleUrl() {
        assertEquals("repo", MainController.repoNameFromUrl("git@github.com:user/repo.git"));
        assertEquals("Editora", MainController.repoNameFromUrl("git@github.com:adriandeleon/Editora.git"));
    }

    @Test
    void repoNameStripsTrailingSlashes() {
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo/"));
        assertEquals("repo", MainController.repoNameFromUrl("https://github.com/user/repo.git/"));
    }

    @Test
    void repoNameFromLocalPath() {
        assertEquals("myrepo", MainController.repoNameFromUrl("/home/me/src/myrepo.git"));
        assertEquals("myrepo", MainController.repoNameFromUrl("/home/me/src/myrepo"));
    }

    @Test
    void blankOrNullUrl() {
        assertEquals("", MainController.repoNameFromUrl(""));
        assertEquals("", MainController.repoNameFromUrl(null));
        assertEquals("", MainController.repoNameFromUrl("   "));
    }

    // --- C-a smart line start (first non-whitespace ↔ column 0 toggle) ---

    @Test
    void smartLineStartGoesToFirstNonWhitespace() {
        // From the text (or anywhere not exactly at the indent), jump to the first non-whitespace column.
        assertEquals(4, MainController.smartLineStartColumn("    foo", 7)); // caret in text → indent
        assertEquals(4, MainController.smartLineStartColumn("    foo", 2)); // caret within indent → indent
        assertEquals(4, MainController.smartLineStartColumn("    foo", 0)); // caret at col 0 → indent
        // Caret already at the indent → toggle to the true line start (column 0).
        assertEquals(0, MainController.smartLineStartColumn("    foo", 4));
    }

    @Test
    void smartLineStartTogglesToColumnZero() {
        // Caret at the first non-whitespace char → toggle to the true line start.
        assertEquals(0, MainController.smartLineStartColumn("\tbar", 1));   // indent is 1 (one tab)
        // Caret inside the leading whitespace → still go to the text start.
        assertEquals(2, MainController.smartLineStartColumn("  baz", 1));
        assertEquals(2, MainController.smartLineStartColumn("  baz", 0));
    }

    @Test
    void smartLineStartNoIndent() {
        // No leading whitespace: indent is 0; from mid-line go to 0, and at 0 it stays 0.
        assertEquals(0, MainController.smartLineStartColumn("hello", 3));
        assertEquals(0, MainController.smartLineStartColumn("hello", 0));
        // Blank/empty line.
        assertEquals(0, MainController.smartLineStartColumn("", 0));
    }
}
