package com.editora.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import com.editora.github.ChecksParser.CheckRun;
import com.editora.github.ChecksParser.ChecksSummary;
import com.editora.github.IssueListParser.Issue;
import com.editora.github.PrListParser.PullRequest;
import com.editora.github.RunListParser.WorkflowRun;
import com.editora.ui.GitHubPanel.RowAction;
import com.editora.ui.GitHubPanel.RowAnswer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The toolkit-free decisions of the GitHub tool window, the checks segment and the PR flows. */
class GitHubUiDecisionsTest {

    private static WorkflowRun run(String status, String conclusion) {
        return new WorkflowRun(37562222495L, "title", "CI", "main", status, conclusion, "push", "", "https://x/run");
    }

    private static final PullRequest PR =
            new PullRequest(7, "Fix", "alice", "fix", "main", "OPEN", false, "", "https://x/pull/7");

    // --- G11: the row commands ---------------------------------------------------------------------

    @Test
    void aRowCommandNeverActsOnASelectionInAClosedWindow() {
        for (RowAction action : RowAction.values()) {
            assertEquals(RowAnswer.OPEN_AND_ASK, GitHubPanel.decide(action, false, run("completed", "failure")));
        }
    }

    @Test
    void aRowCommandNeedsASelectedRow() {
        assertEquals(RowAnswer.NO_SELECTION, GitHubPanel.decide(RowAction.RERUN, true, null));
        assertEquals(RowAnswer.NO_SELECTION, GitHubPanel.decide(RowAction.COPY_URL, true, new GitHubPanel.MoreRow(50)));
    }

    @Test
    void theRunCommandsFollowTheRowMenusRules() {
        WorkflowRun failed = run("completed", "failure");
        WorkflowRun passed = run("completed", "success");
        WorkflowRun running = run("in_progress", "");

        assertEquals(RowAnswer.RUN, GitHubPanel.decide(RowAction.RERUN, true, failed));
        assertEquals(RowAnswer.RUN, GitHubPanel.decide(RowAction.RERUN, true, passed));
        assertEquals(RowAnswer.STILL_ACTIVE, GitHubPanel.decide(RowAction.RERUN, true, running));

        assertEquals(RowAnswer.RUN, GitHubPanel.decide(RowAction.RERUN_FAILED, true, failed));
        assertEquals(RowAnswer.NOT_FAILED, GitHubPanel.decide(RowAction.RERUN_FAILED, true, passed));

        assertEquals(RowAnswer.RUN, GitHubPanel.decide(RowAction.CANCEL, true, running));
        assertEquals(RowAnswer.NOT_ACTIVE, GitHubPanel.decide(RowAction.CANCEL, true, failed));

        assertEquals(RowAnswer.NOT_A_RUN, GitHubPanel.decide(RowAction.CANCEL, true, PR));
    }

    @Test
    void copyUrlWorksOnAnyRowThatHasOne() {
        assertEquals(RowAnswer.RUN, GitHubPanel.decide(RowAction.COPY_URL, true, PR));
        assertEquals(RowAnswer.RUN, GitHubPanel.decide(RowAction.COPY_URL, true, run("completed", "success")));
        Issue blank = new Issue(3, "t", "a", "OPEN", List.of(), "", "");
        assertEquals(RowAnswer.NO_URL, GitHubPanel.decide(RowAction.COPY_URL, true, blank));
        assertEquals("status.github.noUrl", RowAnswer.NO_URL.messageKey(), "not the 'No file to open' message");
    }

    // --- G19: times --------------------------------------------------------------------------------

    @Test
    void aTimestampIsShownRelativeAndInLocalTime() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        String shown = GitHubPanel.when("2026-10-04T07:15:00Z", now, ZoneId.of("America/Monterrey"), Locale.ENGLISH);

        assertFalse(shown.contains("T07:15:00Z"), "not the raw ISO-8601 UTC value: " + shown);
        assertTrue(shown.contains("1:15"), "the local time (UTC-6): " + shown);
        assertTrue(shown.contains("2026"), shown);
        assertTrue(shown.contains(" · "), "relative part first: " + shown);
    }

    @Test
    void anUnparsableTimestampIsShownAsItIs() {
        Instant now = Instant.parse("2026-10-07T12:00:00Z");
        assertEquals("yesterday-ish", GitHubPanel.when("yesterday-ish", now, ZoneId.of("UTC"), Locale.ENGLISH));
        assertEquals("", GitHubPanel.when("", now, ZoneId.of("UTC"), Locale.ENGLISH));
        assertEquals("", GitHubPanel.when(null, now, ZoneId.of("UTC"), Locale.ENGLISH));
    }

    // --- G12: the status-bar segment ---------------------------------------------------------------

    @Test
    void theChecksSegmentNamesThePullRequestAndTheRollUp() {
        assertEquals("✓ #12 Checks", StatusBar.checksText(12, new ChecksSummary(9, 0, 0, 1), "Checks"));
        assertEquals("✗ #12 Checks 2", StatusBar.checksText(12, new ChecksSummary(5, 2, 3, 0), "Checks"));
        assertEquals("○ #12 Checks 5/9", StatusBar.checksText(12, new ChecksSummary(5, 0, 4, 2), "Checks"));
        assertEquals("✓ Checks", StatusBar.checksText(0, new ChecksSummary(1, 0, 0, 0), "Checks"));
        assertEquals(
                "✓ #1234567 Checks",
                StatusBar.checksText(1234567, new ChecksSummary(1, 0, 0, 0), "Checks"),
                "a pull request number is an identifier: no digit grouping");
    }

    @Test
    void theChecksListPutsWhatNeedsAttentionFirst() {
        List<CheckRun> runs = List.of(
                new CheckRun("lint", "CI", "pass", ""),
                new CheckRun("docs", "CI", "skipping", ""),
                new CheckRun("deploy", "CD", "pending", ""),
                new CheckRun("test", "CI", "fail", ""),
                new CheckRun("build", "CI", "pass", ""));
        assertEquals(
                List.of("test", "deploy", "lint", "build", "docs"),
                ChecksListCard.ordered(runs).stream().map(CheckRun::name).toList());
        assertEquals("✗", ChecksListCard.glyph("fail")[0]);
        assertEquals("✓", ChecksListCard.glyph("PASS")[0]);
        assertEquals("○", ChecksListCard.glyph("pending")[0]);
        assertEquals("–", ChecksListCard.glyph(null)[0]);
    }

    // --- G9: where a checkout's pending saves are superseded ---------------------------------------

    @Test
    void aCheckoutFromAListingMutatesTheRepositoryItWasListedIn() {
        Path listed = Path.of("/work/repo-a");
        Path active = Path.of("/work/repo-b");
        assertEquals(listed, GitHubCoordinator.mutationRoot(listed, active), "not the active tab's repository");
        assertEquals(listed, GitHubCoordinator.mutationRoot(listed, null), "no active repository at all");
    }

    @Test
    void aCheckoutFromInsideTheActiveRepositoryMutatesThatRepository() {
        Path active = Path.of("/work/repo-b");
        assertEquals(active, GitHubCoordinator.mutationRoot(active, active));
        assertEquals(active, GitHubCoordinator.mutationRoot(active.resolve("src/main"), active));
    }
}
