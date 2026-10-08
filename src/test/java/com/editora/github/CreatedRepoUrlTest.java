package com.editora.github;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The git remote for a repository {@code gh repo create} just made, in the protocol the user's gh uses. */
class CreatedRepoUrlTest {

    @Test
    void theRemoteFollowsTheConfiguredProtocol() {
        String out = "✓ Created repository adl/editora-sync on github.com\n  https://github.com/adl/editora-sync\n";
        assertEquals("https://github.com/adl/editora-sync.git", GitHubService.remoteUrl(out, "https\n"));
        assertEquals("git@github.com:adl/editora-sync.git", GitHubService.remoteUrl(out, "ssh\n"));
        assertEquals("https://github.com/adl/editora-sync.git", GitHubService.remoteUrl(out, ""));
    }

    @Test
    void anEnterpriseHostAndATrailingDotGitAreUnderstood() {
        assertEquals(
                "git@git.example.com:team/my.sync.git",
                GitHubService.remoteUrl("https://git.example.com/team/my.sync.git", "ssh"));
    }

    @Test
    void outputWithoutAnAddressGivesNothing() {
        assertEquals("", GitHubService.remoteUrl("", "ssh"));
        assertEquals("", GitHubService.remoteUrl(null, "ssh"));
        assertEquals("", GitHubService.remoteUrl("GraphQL: Name already exists on this account", "https"));
    }
}
