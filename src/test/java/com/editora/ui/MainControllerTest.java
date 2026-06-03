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
}
