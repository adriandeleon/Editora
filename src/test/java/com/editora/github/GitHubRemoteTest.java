package com.editora.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubRemoteTest {

    @Test
    void recognizesGitHubHttpsAndSsh() {
        assertTrue(GitHubRemote.isGitHub("https://github.com/org/repo.git"));
        assertTrue(GitHubRemote.isGitHub("https://github.com/org/repo"));
        assertTrue(GitHubRemote.isGitHub("ssh://git@github.com/org/repo.git"));
        assertTrue(GitHubRemote.isGitHub("git@github.com:org/repo.git")); // scp-style
    }

    @Test
    void recognizesEnterpriseHosts() {
        assertTrue(GitHubRemote.isGitHub("https://github.mycorp.com/team/app.git"));
        assertTrue(GitHubRemote.isGitHub("git@github.internal.example.com:team/app.git"));
        assertTrue(GitHubRemote.isGitHub("https://acme.ghe.com/team/app.git"));
    }

    @Test
    void rejectsNonGitHubHosts() {
        assertFalse(GitHubRemote.isGitHub("https://gitlab.com/org/repo.git"));
        assertFalse(GitHubRemote.isGitHub("git@bitbucket.org:org/repo.git"));
        assertFalse(GitHubRemote.isGitHub("https://codeberg.org/org/repo.git"));
        assertFalse(GitHubRemote.isGitHub("/local/path/repo"));
        assertFalse(GitHubRemote.isGitHub(""));
        assertFalse(GitHubRemote.isGitHub(null));
    }

    @Test
    void extractsHostStrippingCredentialsAndPort() {
        assertEquals("github.com", GitHubRemote.hostOf("https://github.com/org/repo.git"));
        assertEquals("github.com", GitHubRemote.hostOf("ssh://git@github.com:22/org/repo.git"));
        assertEquals("github.com", GitHubRemote.hostOf("git@github.com:org/repo.git"));
        assertEquals("gitlab.com", GitHubRemote.hostOf("https://user:token@gitlab.com/org/repo.git"));
        assertEquals("", GitHubRemote.hostOf("relative/path"));
    }
}
