package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

}
