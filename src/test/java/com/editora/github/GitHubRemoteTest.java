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

    /** G14: the scp form needs no user. */
    @Test
    void acceptsTheScpFormWithoutAUser() {
        assertEquals("github.com", GitHubRemote.hostOf("github.com:org/repo.git"));
        assertTrue(GitHubRemote.isGitHub("github.com:org/repo.git"));
        assertEquals("ghe.corp.example", GitHubRemote.hostOf("ghe.corp.example:team/app"));
    }

    @Test
    void aLocalPathIsNotAHost() {
        assertEquals("", GitHubRemote.hostOf("C:\\repos\\app"));
        assertEquals("", GitHubRemote.hostOf("C:/repos/app"));
        assertEquals("", GitHubRemote.hostOf("./github.com:x"));
        assertEquals("", GitHubRemote.hostOf("/srv/git/github.com:x"));
        assertEquals("", GitHubRemote.hostOf("dir/sub:x"));
    }

    /** G14: when gh's hosts are known they decide — an Enterprise host needs no "github" in its name. */
    @Test
    void ghsOwnHostsDecideWhenKnown() {
        java.util.List<String> hosts = java.util.List.of("git.corp.example", "github.com");

        assertTrue(GitHubRemote.isGitHub("https://git.corp.example/team/app.git", hosts));
        assertTrue(GitHubRemote.isGitHub("git@GIT.corp.example:team/app.git", hosts));
        assertTrue(GitHubRemote.isGitHub("https://github.com/o/r", hosts));
        assertFalse(GitHubRemote.isGitHub("https://github.other.example/o/r", hosts), "gh has no account there");
        assertFalse(GitHubRemote.isGitHub("https://gitlab.com/o/r", hosts));
    }

    @Test
    void theNameHeuristicDecidesWhenGhsHostsAreUnknown() {
        assertTrue(GitHubRemote.isGitHub("https://github.mycorp.com/team/app.git", java.util.List.of()));
        assertTrue(GitHubRemote.isGitHub("https://github.com/o/r", null));
        assertFalse(GitHubRemote.isGitHub("https://git.corp.example/team/app.git", java.util.List.of()));
    }

    /** G14: any remote counts, not only origin — a fork whose GitHub remote is "upstream". */
    @Test
    void anyRemoteOnGitHubMakesTheRepositoryAGitHubOne() {
        java.util.List<String> hosts = java.util.List.of("github.com");

        assertTrue(GitHubRemote.anyGitHub(
                java.util.List.of("https://gitlab.com/me/fork.git", "github.com:org/repo.git"), hosts));
        assertFalse(GitHubRemote.anyGitHub(java.util.List.of("https://gitlab.com/me/fork.git", ""), hosts));
        assertFalse(GitHubRemote.anyGitHub(java.util.List.of(), hosts));
        assertFalse(GitHubRemote.anyGitHub(null, hosts));
    }
}
