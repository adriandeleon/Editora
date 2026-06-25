package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for pure helpers in {@link MainController} (no toolkit needed). */
class MainControllerTest {

    @Test
    void repoNameFromHttpsUrl() {
        assertEquals("repo", GitCoordinator.repoNameFromUrl("https://github.com/user/repo.git"));
        assertEquals("repo", GitCoordinator.repoNameFromUrl("https://github.com/user/repo"));
    }

    @Test
    void repoNameFromScpStyleUrl() {
        assertEquals("repo", GitCoordinator.repoNameFromUrl("git@github.com:user/repo.git"));
        assertEquals("Editora", GitCoordinator.repoNameFromUrl("git@github.com:adriandeleon/Editora.git"));
    }

    @Test
    void repoNameStripsTrailingSlashes() {
        assertEquals("repo", GitCoordinator.repoNameFromUrl("https://github.com/user/repo/"));
        assertEquals("repo", GitCoordinator.repoNameFromUrl("https://github.com/user/repo.git/"));
    }

    @Test
    void repoNameFromLocalPath() {
        assertEquals("myrepo", GitCoordinator.repoNameFromUrl("/home/me/src/myrepo.git"));
        assertEquals("myrepo", GitCoordinator.repoNameFromUrl("/home/me/src/myrepo"));
    }

    @Test
    void blankOrNullUrl() {
        assertEquals("", GitCoordinator.repoNameFromUrl(""));
        assertEquals("", GitCoordinator.repoNameFromUrl(null));
        assertEquals("", GitCoordinator.repoNameFromUrl("   "));
    }

    // (C-a smart-line-start column logic moved to editor/TextNavTest; path-identity/keying — canonicalPath,
    //  pathKey, historyKey, noteKey, sameNormalized, findKeyByIdentity — moved to config/PathKeysTest.)

    @Test
    void compactSourceNoiseMatchesImplicitClassComplaintsOnly() {
        // JDK 23+ JDT wording, the preview-gating message, and the JDK 21/22 preview-era name…
        assertTrue(LspCoordinator.isCompactSourceNoise("Implicitly declared class must have a candidate main method"));
        assertTrue(LspCoordinator.isCompactSourceNoise(
                "Implicitly Declared Classes and Instance Main Methods is a preview feature and"
                        + " disabled by default. Use --enable-preview to enable"));
        assertTrue(
                LspCoordinator.isCompactSourceNoise("Unnamed Classes and Instance Main Methods is a preview feature"));
        // …but real errors in the file still surface.
        assertFalse(LspCoordinator.isCompactSourceNoise("The method foo() is undefined"));
        assertFalse(LspCoordinator.isCompactSourceNoise("Syntax error on token \";\""));
        assertFalse(LspCoordinator.isCompactSourceNoise(null));
    }
}
