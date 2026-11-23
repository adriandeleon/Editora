package com.editora.ui;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.editora.github.PrListParser.PullRequest;
import com.editora.github.RunListParser.WorkflowRun;
import com.editora.ui.GitHubPanel.RowAction;
import com.editora.ui.GitHubPanel.RowAnswer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GitHub window's row commands added for its right-click rows ({@code github.openSelected},
 * {@code github.viewSelectedRunLog}, {@code github.checkoutSelectedPr}, {@code github.reviewSelectedPr}) and its
 * repository commands ({@code github.openRepository}, {@code github.copyRepositoryUrl}): what each asks the window to
 * do for the selected row, and nothing where its row would not be offered.
 */
@Tag("fx")
class GitHubRowActionsFxTest {

    private static final PullRequest PR =
            new PullRequest(7, "Fix", "alice", "fix", "main", "OPEN", false, "", "https://x/pull/7");
    private static final WorkflowRun FAILED =
            new WorkflowRun(37562222495L, "title", "CI", "main", "completed", "failure", "push", "", "https://x/run");

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private final List<String> calls = new ArrayList<>();

    private GitHubPanel panel() {
        return new GitHubPanel((GitHubPanel.Actions) Proxy.newProxyInstance(
                GitHubPanel.Actions.class.getClassLoader(), new Class<?>[] {GitHubPanel.Actions.class}, (p, m, a) -> {
                    calls.add(m.getName() + (a == null ? "" : " " + Arrays.toString(a)));
                    return null;
                }));
    }

    @Test
    void thePullRequestRowCommandsActOnTheSelectedPullRequest() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitHubPanel panel = panel();
            panel.select(GitHubPanel.Mode.PRS);
            panel.setPrs(List.of(PR));
            panel.selectRow(0);
            calls.clear();
            assertEquals(RowAnswer.RUN, panel.rowAnswer(RowAction.CHECKOUT_PR, true));
            panel.perform(RowAction.CHECKOUT_PR);
            panel.perform(RowAction.REVIEW_PR);
            panel.perform(RowAction.OPEN_URL);
            assertEquals(RowAnswer.NOT_A_RUN, panel.rowAnswer(RowAction.VIEW_LOG, true));
            panel.perform(RowAction.VIEW_LOG); // not a run: nothing
            assertEquals(List.of("checkoutPr [7]", "reviewPr [7]", "openUrl [https://x/pull/7]"), calls);
        });
    }

    /** G15: a filter that matches none of the listed rows, with more on GitHub, offers to search GitHub for it. */
    @Test
    void aFilterWithNoMatchOffersASearchOfGitHub() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitHubPanel panel = panel();
            panel.select(GitHubPanel.Mode.PRS);
            panel.setPrs(List.of(PR), true); // gh has more than the one listed
            panel.typeFilter("Fix");
            assertEquals(List.of(PR, new GitHubPanel.MoreRow(1)), panel.rows(), "a match: nothing is offered");

            panel.typeFilter("crash on save");
            assertEquals(
                    new GitHubPanel.SearchRow("crash on save"), panel.rows().getFirst());
            panel.selectRow(0);
            calls.clear();
            panel.activate();
            assertEquals(List.of("showPrs"), calls, "the list is fetched again");
            assertEquals("crash on save", panel.query(GitHubPanel.Mode.PRS).search());

            panel.setPrs(List.of(PR)); // GitHub's answer is shown as it is, not filtered again
            assertEquals(List.of(PR), panel.rows());

            calls.clear();
            panel.typeFilter(""); // leaving the search goes back to the plain list
            assertEquals("", panel.query(GitHubPanel.Mode.PRS).search());
            assertEquals(List.of("showPrs"), calls);

            panel.setPrs(List.of(PR), true);
            panel.typeFilter("#1234");
            panel.selectRow(0);
            calls.clear();
            panel.activate();
            assertEquals(List.of("reviewPr [1234]"), calls, "a number opens that pull request");
        });
    }

    /** G15: with every row listed there is nothing more to search for. */
    @Test
    void aCompleteListOffersNoSearch() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitHubPanel panel = panel();
            panel.select(GitHubPanel.Mode.PRS);
            panel.setPrs(List.of(PR), false);
            panel.typeFilter("crash");
            assertEquals(List.of(), panel.rows());
        });
    }

    @Test
    void theRunRowCommandsActOnTheSelectedRun() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitHubPanel panel = panel();
            panel.select(GitHubPanel.Mode.RUNS);
            panel.setRuns(List.of(FAILED));
            panel.selectRow(0);
            calls.clear();
            panel.perform(RowAction.VIEW_LOG);
            panel.perform(RowAction.OPEN_URL);
            assertEquals(RowAnswer.NOT_A_PR, panel.rowAnswer(RowAction.REVIEW_PR, true));
            panel.perform(RowAction.REVIEW_PR); // not a pull request: nothing
            assertEquals(List.of("viewRunLog [37562222495, CI]", "openUrl [https://x/run]"), calls);
        });
    }

    @Test
    void theRepositoryCommandsOpenOrCopyTheShownRepositoryAndSayWhenThereIsNone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            GitHubPanel panel = panel();
            calls.clear();
            assertFalse(panel.repositoryAction(true), "no repository known yet");
            assertFalse(panel.repositoryAction(false));
            panel.setContext(new GitHubPanel.Context("team/app", "https://github.com/team/app", "main", "alice"));
            calls.clear();
            assertTrue(panel.repositoryAction(true));
            assertTrue(panel.repositoryAction(false));
            assertEquals(
                    List.of("openUrl [https://github.com/team/app]", "copyUrl [https://github.com/team/app]"), calls);
        });
    }
}
