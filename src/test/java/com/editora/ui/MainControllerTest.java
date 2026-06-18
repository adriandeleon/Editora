package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // (C-a smart-line-start column logic moved to editor/TextNavTest; path-identity/keying — canonicalPath,
    //  pathKey, historyKey, noteKey, sameNormalized, findKeyByIdentity — moved to config/PathKeysTest.)

    @Test
    void compactSourceNoiseMatchesImplicitClassComplaintsOnly() {
        // JDK 23+ JDT wording, the preview-gating message, and the JDK 21/22 preview-era name…
        assertTrue(MainController.isCompactSourceNoise("Implicitly declared class must have a candidate main method"));
        assertTrue(MainController.isCompactSourceNoise(
                "Implicitly Declared Classes and Instance Main Methods is a preview feature and"
                        + " disabled by default. Use --enable-preview to enable"));
        assertTrue(
                MainController.isCompactSourceNoise("Unnamed Classes and Instance Main Methods is a preview feature"));
        // …but real errors in the file still surface.
        assertFalse(MainController.isCompactSourceNoise("The method foo() is undefined"));
        assertFalse(MainController.isCompactSourceNoise("Syntax error on token \";\""));
        assertFalse(MainController.isCompactSourceNoise(null));
    }
}
