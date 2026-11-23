package com.editora.github;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure argv / text decisions of {@link GitHubService}. */
class GitHubServiceArgsTest {

    /** G8: a path that starts with a dash must reach gh as a path — flags first, then {@code --}. */
    @Test
    void browsePutsThePathAfterADoubleDash() {
        assertEquals(
                List.of("browse", "--no-browser", "--branch", "main", "--", "-weird.txt:3"),
                GitHubService.browseArgs("-weird.txt:3", "main"));
        assertEquals(
                List.of("browse", "--no-browser", "--", "src/A.java:10"),
                GitHubService.browseArgs("src/A.java:10", " "));
        assertEquals(List.of("browse", "--no-browser", "--", "a:1"), GitHubService.browseArgs("a:1", null));
    }

    /** G2: the path Settings' Browse… button writes is one executable, spaces and all. */
    @Test
    void aGhPathWithSpacesIsOneToken(@TempDir Path dir) throws Exception {
        Path gh = Files.createFile(
                Files.createDirectories(dir.resolve("GitHub CLI")).resolve("gh.exe"));

        assertEquals(List.of(gh.toString()), GitHubService.commandTokens(gh.toString()));
        assertEquals(List.of("gh"), GitHubService.commandTokens(""));
        assertEquals(List.of("sh", "/x/fake gh"), GitHubService.commandTokens("sh \"/x/fake gh\""));
    }

    /** G3: a diff cut off by the capture limit keeps only the files that arrived whole. */
    @Test
    void aCutDiffLosesItsIncompleteLastFile() {
        String whole = "diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +1 @@\n-x\n+y\n";
        String cut = whole + "diff --git a/b b/b\n--- a/b\n+++ b/b\n@@ -1,9 +1,9 @@\n-on";

        assertEquals(whole, GitHubService.wholeFileSections(cut));
        assertEquals("", GitHubService.wholeFileSections("diff --git a/a b/a\n--- a/a\n+++ b/a\n@@ -1 +"));
    }

    @Test
    void unverifiedIsReadyButNotAuthenticated() {
        GitHubService.Availability offline = GitHubService.Availability.found(
                "gh version 2.96.0", GitHubService.AuthState.UNVERIFIED, List.of("github.com"), "no such host");
        assertTrue(offline.ready());
        assertTrue(offline.unverified());
        assertFalse(offline.authenticated());

        GitHubService.Availability signedOut = GitHubService.Availability.found(
                "gh version 2.96.0", GitHubService.AuthState.SIGNED_OUT, List.of(), "");
        assertFalse(signedOut.ready());
        assertFalse(GitHubService.Availability.UNKNOWN.ready());
        assertTrue(new GitHubService.Availability(true, true, "gh version 2.40.0").ready());
        assertFalse(new GitHubService.Availability(true, true, "gh version 2.40.0").supportsChecks());
    }

    /** G13: a repository name is never read as an option — flags, then {@code --}, then the name. */
    @Test
    void aRepositoryNameComesAfterADoubleDashAndCannotStartWithADash() {
        assertEquals(
                List.of("repo", "create", "--private", "--description", "d", "--", "editora-sync"),
                List.of(GitHubService.createRepoArgs("editora-sync", "d")));
        assertTrue(GitHubService.validRepositoryName("editora-sync"));
        assertTrue(GitHubService.validRepositoryName("my.repo_2"));
        assertFalse(GitHubService.validRepositoryName("--public"));
        assertFalse(GitHubService.validRepositoryName("-x"));
        assertFalse(GitHubService.validRepositoryName(".."));
        assertFalse(GitHubService.validRepositoryName("a b"));
        assertFalse(GitHubService.validRepositoryName(""));
    }

    /** G2: "no pull request" and "no checks" are answers; anything else gh says is a failure. */
    @Test
    void aFailedLookupIsNotNoPullRequest() {
        assertTrue(GitHubService.noPullRequest("no pull requests found for branch \"topic\""));
        assertFalse(GitHubService.noPullRequest("HTTP 401: Bad credentials (https://api.github.com/graphql)"));
        assertFalse(GitHubService.noPullRequest("API rate limit exceeded for user ID 1."));
        assertTrue(GitHubService.noChecks("no checks reported on the 'topic' branch"));
        assertFalse(GitHubService.noChecks("error connecting to api.github.com"));
        assertTrue(GitHubService.BranchChecks.failed("HTTP 401").failed());
        assertFalse(GitHubService.BranchChecks.NONE.failed());
    }

    /** G10: GitHub's single sign-on refusal is told apart from a passing failure — asking again cannot help. */
    @Test
    void singleSignOnRefusalIsRecognized() {
        assertTrue(GitHubService.ssoRefused(
                "GraphQL: Resource protected by organization SAML enforcement. You must grant your OAuth token access"
                        + " to this organization. (repository)"));
        assertFalse(GitHubService.ssoRefused("error connecting to api.github.com"));
    }

    /** G14: a call that ran on another machine is logged as such. */
    @Test
    void theLogNamesTheMachineACallRanOn() {
        assertEquals(List.of("gh", "pr", "list"), GitHubService.loggedArgv(List.of("gh"), "", "pr", "list"));
        assertEquals(
                List.of("[ssh:box]", "/opt/gh", "pr", "list"),
                GitHubService.loggedArgv(List.of("/opt/gh"), "ssh:box", "pr", "list"));
    }
}
