package com.editora.git;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A remote URL is shown in the branch popup: the credentials it may embed must not be. */
class RemoteUrlDisplayTest {

    @Test
    void anHttpsUrlLosesItsUserInfo() {
        assertEquals(
                "https://github.com/o/r.git",
                GitFormat.displayRemoteUrl("https://alice:ghp_secretTOKEN123@github.com/o/r.git"));
        assertEquals(
                "https://github.com/o/r.git",
                GitFormat.displayRemoteUrl("https://ghp_secretTOKEN123@github.com/o/r.git"),
                "a bare user in an https URL is usually the token itself");
    }

    @Test
    void anSshUrlKeepsItsUserButNotAPassword() {
        assertEquals("ssh://git@host.example/o/r.git", GitFormat.displayRemoteUrl("ssh://git@host.example/o/r.git"));
        assertEquals(
                "ssh://git@host.example/o/r.git", GitFormat.displayRemoteUrl("ssh://git:hunter2@host.example/o/r.git"));
    }

    @Test
    void urlsWithoutCredentialsAreUnchanged() {
        assertEquals("git@github.com:o/r.git", GitFormat.displayRemoteUrl("git@github.com:o/r.git"));
        assertEquals("https://github.com/o/r.git", GitFormat.displayRemoteUrl("https://github.com/o/r.git"));
        assertEquals(
                "https://host/o/r@v2.git", GitFormat.displayRemoteUrl("https://host/o/r@v2.git"), "an @ in the path");
        assertEquals("/srv/git/r.git", GitFormat.displayRemoteUrl("/srv/git/r.git"));
        assertEquals("", GitFormat.displayRemoteUrl(null));
    }
}
