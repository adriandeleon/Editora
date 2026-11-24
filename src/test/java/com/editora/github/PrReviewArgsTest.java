package com.editora.github;

import java.util.List;

import com.editora.github.PrReviewArgs.ReviewAction;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrReviewArgsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PR7 = "https://github.com/o/r/pull/7";

    /**
     * R3-G1: gh api sends a request to github.com unless told the host, so a pinned review of a GitHub Enterprise
     * pull request went to the wrong site. The host and repository come from the pull request's own address.
     */
    @Test
    void aPinnedReviewGoesToThePullRequestsOwnHostAndRepository() {
        GhInvocation call =
                PrReviewArgs.invocation(5, ReviewAction.APPROVE, "", "abc", "https://GHE.example.com/team/app/pull/5");
        assertEquals(
                List.of(
                        "api",
                        "--hostname",
                        "ghe.example.com",
                        "--method",
                        "POST",
                        "repos/team/app/pulls/5/reviews",
                        "--input",
                        "-"),
                call.args());
        // gh api --hostname rejects host:port, so an address with a port is not pinned.
        assertNull(PrReviewArgs.target("https://ghe.example.com:8443/team/app/pull/5", 5), "a port");
        assertEquals(
                List.of("pr", "review", "5", "--approve"),
                PrReviewArgs.invocation(
                                5, ReviewAction.APPROVE, "", "abc", "https://ghe.example.com:8443/team/app/pull/5")
                        .args());
        assertNull(PrReviewArgs.target("https://ghe.example.com/team/app/pull/6", 5), "another pull request");
        assertNull(PrReviewArgs.target("https://ghe.example.com/team/app/issues/5", 5));
        assertNull(PrReviewArgs.target("https://ghe.example.com/-x/app/pull/5", 5), "nothing gh would take as a flag");
        assertNull(PrReviewArgs.target("file:///team/app/pull/5", 5));
        assertNull(PrReviewArgs.target("", 5));
        // Without an address the host cannot be known: gh pr review finds it, unpinned.
        assertEquals(
                List.of("pr", "review", "5", "--approve"),
                PrReviewArgs.invocation(5, ReviewAction.APPROVE, "", "abc", "").args());
    }

    @Test
    void approveOmitsBodyWhenBlank() {
        assertEquals(List.of("pr", "review", "42", "--approve"), PrReviewArgs.build(42, ReviewAction.APPROVE, ""));
        assertEquals(List.of("pr", "review", "42", "--approve"), PrReviewArgs.build(42, ReviewAction.APPROVE, null));
        assertNull(
                PrReviewArgs.invocation(42, ReviewAction.APPROVE, " ", null, null)
                        .stdin(),
                "nothing to feed gh");
    }

    @Test
    void approveIncludesBodyWhenPresent() {
        assertEquals(
                List.of("pr", "review", "42", "--approve", "--body-file", "-"),
                PrReviewArgs.build(42, ReviewAction.APPROVE, "LGTM"));
        assertEquals(
                "LGTM",
                PrReviewArgs.invocation(42, ReviewAction.APPROVE, "LGTM", "", "")
                        .stdin());
    }

    /** G4: the body goes to gh's standard input, never the command line. */
    @Test
    void requestChangesAndCommentAlwaysPassBodyOnStandardInput() {
        assertEquals(
                List.of("pr", "review", "7", "--request-changes", "--body-file", "-"),
                PrReviewArgs.build(7, ReviewAction.REQUEST_CHANGES, "fix this"));
        GhInvocation comment = PrReviewArgs.invocation(7, ReviewAction.COMMENT, "note", null, null);
        assertEquals(List.of("pr", "review", "7", "--comment", "--body-file", "-"), comment.args());
        assertEquals("note", comment.stdin());
        assertFalse(comment.args().contains("note"));
    }

    /** G7: a review from the review tab is pinned to the head commit the reviewer saw. */
    @Test
    void aKnownHeadPinsTheReviewThroughTheApi() throws Exception {
        GhInvocation call = PrReviewArgs.invocation(7, ReviewAction.APPROVE, "Looks \"good\"\nyes", "abc123", PR7);
        assertEquals(
                List.of(
                        "api",
                        "--hostname",
                        "github.com",
                        "--method",
                        "POST",
                        "repos/o/r/pulls/7/reviews",
                        "--input",
                        "-"),
                call.args());
        JsonNode json = JSON.readTree(call.stdin());
        assertEquals("abc123", json.path("commit_id").asText());
        assertEquals("APPROVE", json.path("event").asText());
        assertEquals("Looks \"good\"\nyes", json.path("body").asText());
        JsonNode bare = JSON.readTree(PrReviewArgs.invocation(7, ReviewAction.APPROVE, "", "abc123", PR7)
                .stdin());
        assertFalse(bare.has("body"), "an approve with no comment sends no body");
        JsonNode changes = JSON.readTree(PrReviewArgs.invocation(7, ReviewAction.REQUEST_CHANGES, "x", "abc", PR7)
                .stdin());
        assertEquals("REQUEST_CHANGES", changes.path("event").asText());
    }

    @Test
    void bodyRequiredExceptForApprove() {
        assertFalse(PrReviewArgs.bodyRequired(ReviewAction.APPROVE));
        assertTrue(PrReviewArgs.bodyRequired(ReviewAction.REQUEST_CHANGES));
        assertTrue(PrReviewArgs.bodyRequired(ReviewAction.COMMENT));
    }

    /** G3: an empty approve is valid only when the reviewer chose Approve; no choice is never valid. */
    @Test
    void anApproveNeedsAnExplicitChoice() {
        assertFalse(PrReviewArgs.canSubmit(null, "text", false));
        assertFalse(PrReviewArgs.canSubmit(ReviewAction.APPROVE, "", false));
        assertTrue(PrReviewArgs.canSubmit(ReviewAction.APPROVE, "", true));
        assertFalse(PrReviewArgs.canSubmit(ReviewAction.COMMENT, " ", true));
        assertTrue(PrReviewArgs.canSubmit(ReviewAction.COMMENT, "note", false));
    }
}
