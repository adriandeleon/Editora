package com.editora.github;

import java.util.List;

import com.editora.github.IssueListParser.Issue;
import com.editora.github.PrListParser.PullRequest;
import com.editora.github.RunListParser.WorkflowRun;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubItemFilterTest {

    private static PullRequest pr() {
        return new PullRequest(
                42,
                "Fix the split buttons",
                "octocat",
                "fix/split",
                "master",
                "OPEN",
                false,
                "2026-07-26",
                "https://example.test/pull/42");
    }

    private static Issue issue() {
        return new Issue(7, "Dark theme is unreadable", "hubot", "OPEN", List.of("bug", "ui"), "2026-07-26", "u");
    }

    private static WorkflowRun run() {
        return new WorkflowRun(
                1234567890123L, "Nightly build", "release", "main", "completed", "failure", "push", "2026-07-26", "u");
    }

    @Test
    void blankQueryMatchesEverything() {
        for (String q : new String[] {null, "", "   "}) {
            assertTrue(GitHubItemFilter.matches(pr(), q));
            assertTrue(GitHubItemFilter.matches(issue(), q));
            assertTrue(GitHubItemFilter.matches(run(), q));
        }
    }

    @Test
    void matchingIsCaseInsensitiveAndSubstring() {
        assertTrue(GitHubItemFilter.matches(pr(), "SPLIT"));
        assertTrue(GitHubItemFilter.matches(pr(), "  the split  "));
        assertFalse(GitHubItemFilter.matches(pr(), "nowhere"));
    }

    @Test
    void aLeadingHashIsDroppedSoBothFormsFindTheNumber() {
        assertTrue(GitHubItemFilter.matches(pr(), "42"));
        assertTrue(GitHubItemFilter.matches(pr(), "#42"));
        assertTrue(GitHubItemFilter.matches(issue(), "#7"));
    }

    @Test
    void pullRequestsMatchOnAuthorAndBranches() {
        assertTrue(GitHubItemFilter.matches(pr(), "octocat"));
        assertTrue(GitHubItemFilter.matches(pr(), "fix/"));
        assertTrue(GitHubItemFilter.matches(pr(), "master"));
    }

    @Test
    void issuesMatchOnLabels() {
        assertTrue(GitHubItemFilter.matches(issue(), "bug"));
        assertTrue(GitHubItemFilter.matches(issue(), "ui"));
        assertFalse(GitHubItemFilter.matches(issue(), "enhancement"));
    }

    @Test
    void runsMatchOnWorkflowBranchEventAndConclusion() {
        assertTrue(GitHubItemFilter.matches(run(), "release"));
        assertTrue(GitHubItemFilter.matches(run(), "nightly"));
        assertTrue(GitHubItemFilter.matches(run(), "main"));
        assertTrue(GitHubItemFilter.matches(run(), "push"));
        assertTrue(GitHubItemFilter.matches(run(), "failure"));
        assertFalse(GitHubItemFilter.matches(run(), "success"));
    }

    /** A row type the filter doesn't model must stay visible rather than silently vanish. */
    @Test
    void unknownItemTypesAreKept() {
        assertTrue(GitHubItemFilter.matches(new Object(), "anything"));
    }

    /** Null string fields are common in {@code gh} output (no conclusion while a run is queued). */
    @Test
    void nullFieldsAreSkippedNotThrown() {
        WorkflowRun queued = new WorkflowRun(1L, "t", "wf", "main", "queued", null, "push", "now", "u");
        assertFalse(GitHubItemFilter.matches(queued, "failure"));
        assertTrue(GitHubItemFilter.matches(queued, "queued"));
    }
}
