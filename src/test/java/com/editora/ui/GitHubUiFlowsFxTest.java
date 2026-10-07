package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

import com.editora.command.CommandRegistry;
import com.editora.diff.DiffModels.DiffModel;
import com.editora.diff.PatchParser.FilePatch;
import com.editora.github.GitHubListQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GitHub flows a user drives — the checks segment, the tool window's lists and row commands, the review
 * tab and the two forms — against an offline stand-in for {@code gh} (a shell script answering from canned
 * files; nothing touches the network and nothing is pushed).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitHubUiFlowsFxTest {

    private static final long RUN_ID = 37562222495L;

    private static final String FAKE_GH = """
            #!/bin/sh
            D="$(dirname "$0")"
            printf '%s | cwd=%s\\n' "$*" "$(pwd)" >> "$D/gh.log"
            case "$1" in
              --version) echo "gh version 2.96.0 (test stand-in)"; exit 0;;
              auth) exit 0;;
              api) cat "$D/prfiles.json"; exit 0;;
            esac
            case "$1 $2" in
              "pr list") cat "$D/prlist.json"; exit 0;;
              "issue list") echo "[]"; exit 0;;
              "run list") cat "$D/runlist.json"; exit 0;;
              "repo view") cat "$D/repo.json"; exit 0;;
              "pr view")
                if [ "$3" = "--json" ]; then
                  if [ -f "$D/branchpr.json" ]; then cat "$D/branchpr.json"; exit 0; fi
                  echo "no pull requests found for branch" >&2; exit 1
                fi
                cat "$D/prview.json"; exit 0;;
              "pr diff")
                if [ "$3" = "9" ]; then
                  echo "could not find pull request diff: HTTP 406: Sorry, the diff exceeded the maximum number of files (300)." >&2
                  echo "PullRequest.diff too_large" >&2
                  exit 1
                fi
                cat "$D/prdiff.patch"; exit 0;;
              "pr checks") cat "$D/checks.json"; exit 0;;
              "pr review")
                if [ -f "$D/delay" ]; then sleep "$(cat "$D/delay")"; fi
                if [ -f "$D/review.fail" ]; then echo "GraphQL: Can not approve your own pull request" >&2; exit 1; fi
                exit 0;;
              "pr create")
                if [ -f "$D/create.fail" ]; then echo "GraphQL: No commits between main and feature" >&2; exit 1; fi
                echo "https://github.com/upstream-org/r/pull/12"; exit 0;;
            esac
            exit 0
            """;

    private static final String PENDING =
            "[{\"name\":\"build\",\"state\":\"IN_PROGRESS\",\"bucket\":\"pending\",\"workflow\":\"CI\","
                    + "\"link\":\"https://github.com/upstream-org/r/actions/runs/" + RUN_ID + "/job/1\"}]";
    private static final String PASSED =
            PENDING.replace("IN_PROGRESS", "SUCCESS").replace("pending", "pass");
    private static final String MIXED =
            "[{\"name\":\"lint\",\"state\":\"SUCCESS\",\"bucket\":\"pass\",\"workflow\":\"CI\",\"link\":\"\"},"
                    + "{\"name\":\"build\",\"state\":\"FAILURE\",\"bucket\":\"fail\",\"workflow\":\"CI\","
                    + "\"link\":\"https://github.com/upstream-org/r/actions/runs/" + RUN_ID + "/job/1\"}]";

    private Path base;
    private Path bin;
    private Path repo;
    private FxWindowFixture fx;
    private GitCoordinator git;
    private GitHubCoordinator github;
    private GitHubPanel panel;
    private ToolWindowManager toolWindows;
    private ToolWindow githubWindow;
    private EditorArea area;
    private OverlayHost overlay;
    private CommandRegistry registry;
    private Label echo;
    private Label checks;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeFalse(GitTestRepo.windows(), "the gh stand-in is a POSIX shell script");
        FxTestSupport.bootToolkit();
        base = Files.createTempDirectory("editora-gh-flows").toRealPath();
        bin = Files.createDirectories(base.resolve("bin"));
        Files.writeString(bin.resolve("fakegh"), FAKE_GH);
        StringBuilder prs = new StringBuilder("[");
        for (int n = 51; n >= 1; n--) { // one more than a page
            prs.append("{\"number\":")
                    .append(n)
                    .append(",\"title\":\"Change ")
                    .append(n)
                    .append("\",\"author\":{\"login\":\"alice\"},\"headRefName\":\"b")
                    .append(n)
                    .append("\",\"baseRefName\":\"main\",\"state\":\"OPEN\",\"isDraft\":false,")
                    .append("\"updatedAt\":\"2026-01-01T00:00:00Z\",\"url\":\"https://github.com/upstream-org/r/pull/")
                    .append(n)
                    .append("\"}")
                    .append(n > 1 ? "," : "]");
        }
        Files.writeString(bin.resolve("prlist.json"), prs.toString());
        Files.writeString(
                bin.resolve("runlist.json"),
                "[{\"databaseId\":" + RUN_ID + ",\"displayTitle\":\"Build\",\"workflowName\":\"CI\","
                        + "\"headBranch\":\"main\",\"status\":\"in_progress\",\"conclusion\":\"\",\"event\":\"push\","
                        + "\"createdAt\":\"2026-01-01T00:00:00Z\",\"url\":\"https://github.com/upstream-org/r/actions/runs/1\"}]");
        Files.writeString(
                bin.resolve("repo.json"),
                "{\"nameWithOwner\":\"upstream-org/r\",\"defaultBranchRef\":{\"name\":\"main\"},"
                        + "\"url\":\"https://github.com/upstream-org/r\"}");
        String pr7 = "{\"number\":7,\"title\":\"Fix thing\",\"body\":\"Body\",\"author\":{\"login\":\"alice\"},"
                + "\"baseRefName\":\"main\",\"headRefName\":\"fix\",\"state\":\"OPEN\","
                + "\"url\":\"https://github.com/upstream-org/r/pull/7\",\"additions\":1,\"deletions\":1}";
        Files.writeString(bin.resolve("prview.json"), pr7);
        Files.writeString(bin.resolve("branchpr.json"), pr7);
        // One file, two hunks far apart, and a new side that does not end in a newline.
        Files.writeString(bin.resolve("prdiff.patch"), """
                diff --git a/a.txt b/a.txt
                index 1111111..2222222 100644
                --- a/a.txt
                +++ b/a.txt
                @@ -40,2 +40,2 @@
                 before
                -old forty-one
                +new forty-one
                @@ -500,2 +500,2 @@
                 far away
                -old last
                +new last
                \\ No newline at end of file
                """);
        Files.writeString(
                bin.resolve("prfiles.json"),
                "[{\"filename\":\"a.txt\",\"status\":\"modified\",\"additions\":1,\"deletions\":1,"
                        + "\"patch\":\"@@ -40,2 +40,2 @@\\n before\\n-old\\n+new\"}]\n"
                        + "[{\"filename\":\"huge.json\",\"status\":\"modified\",\"additions\":9000,\"deletions\":1}]\n");
        Files.writeString(bin.resolve("checks.json"), PENDING);

        GitTestRepo testRepo = GitTestRepo.init(base);
        repo = testRepo.root;
        testRepo.write("a.txt", "a1\n");
        testRepo.commitAll("first");
        testRepo.git("remote", "add", "origin", "https://github.com/o/r.git");
        testRepo.git("checkout", "-b", "feature");
        testRepo.write("a.txt", "a2\n");
        testRepo.commitAll("Add the feature\n\nIt was missing.");
        testRepo.git("checkout", "-");

        String command = "sh " + bin.resolve("fakegh");
        fx = FxWindowFixture.create(Files.createDirectories(base.resolve("cfg")), shared -> {
            shared.getSettings().setGitSupport(true);
            shared.getSettings().setGithubSupport(true);
            shared.getSettings().setGhPath(command);
        });
        git = FxTestSupport.field(fx.controller, "git");
        github = FxTestSupport.field(fx.controller, "github");
        panel = FxTestSupport.field(fx.controller, "githubPanel");
        toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        githubWindow = FxTestSupport.field(fx.controller, "githubToolWindow");
        area = FxTestSupport.field(fx.controller, "editorArea");
        overlay = FxTestSupport.field(fx.controller, "overlayHost");
        registry = FxTestSupport.field(fx.controller, "registry");
        Object statusBar = FxTestSupport.field(fx.controller, "statusBar");
        echo = FxTestSupport.field(statusBar, "echo");
        checks = FxTestSupport.field(statusBar, "githubChecks");

        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(repo.resolve("a.txt"), 0));
        await(
                "the repository and gh to be detected",
                () -> repo.equals(git.repoRoot())
                        && github.service().availability() != null
                        && github.service().availability().ready());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
        if (base != null) {
            try (var paths = Files.walk(base)) {
                for (Path path :
                        paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // --- G12: the checks segment -----------------------------------------------------------------

    /** G12: the roll-up used to appear only after a manual github.refresh or a PR checkout. */
    @Test
    @Order(1)
    void theChecksSegmentAppearsOnItsOwnAndNamesThePullRequest() throws Exception {
        await("the checks roll-up, with no refresh asked for", checks::isVisible);

        String text = FxTestSupport.callOnFx(checks::getText);
        assertTrue(text.contains("#7"), "it names the pull request: " + text);
        assertTrue(text.startsWith("○"), "one check is pending: " + text);
    }

    /** G12: a pending "○ Checks" stayed pending for ever. */
    @Test
    @Order(2)
    void aPendingRollUpIsPolledUntilItFinishes() throws Exception {
        FxTestSupport.runOnFx(() -> {
            github.pollDelayMillisForTest = 40;
            github.windowFocusedForTest = () -> true;
            github.refresh();
        });
        await(
                "the pending roll-up",
                () -> checks.isVisible() && checks.getText().startsWith("○"));
        long before = logged("pr checks");

        Files.writeString(bin.resolve("checks.json"), PASSED);

        await(
                "the roll-up to turn green without a refresh",
                () -> checks.getText().startsWith("✓"));
        assertTrue(logged("pr checks") > before, "it was asked for again");
        long settled = logged("pr checks");
        Thread.sleep(400);
        assertEquals(settled, logged("pr checks"), "a finished roll-up is not polled any more");
    }

    /** G12: nobody is looking at an unfocused window; the poll waits for the focus to come back. */
    @Test
    @Order(3)
    void aPollThatComesDueInAnUnfocusedWindowWaitsForTheFocus() throws Exception {
        Files.writeString(bin.resolve("checks.json"), PENDING);
        FxTestSupport.runOnFx(() -> {
            github.windowFocusedForTest = () -> false;
            github.refresh();
        });
        await("the poll to come due and be deferred", () -> FxTestSupport.<Boolean>field(github, "pollDeferred"));
        long asked = logged("pr checks");
        Files.writeString(bin.resolve("checks.json"), PASSED);
        Thread.sleep(300);
        assertEquals(asked, logged("pr checks"), "no gh call while the window is unfocused");

        FxTestSupport.runOnFx(() -> {
            github.windowFocusedForTest = () -> true;
            github.resumeDeferredPoll(); // what the focus listener does
        });
        await("the roll-up once the focus is back", () -> checks.getText().startsWith("✓"));
    }

    /** G12: the names and links ChecksParser parses were never shown; the click only refreshed. */
    @Test
    @Order(4)
    void theSegmentOpensTheListOfChecks() throws Exception {
        Files.writeString(bin.resolve("checks.json"), MIXED);
        FxTestSupport.runOnFx(github::refresh);
        await("the failing roll-up", () -> checks.getText().startsWith("✗"));
        assertEquals("✗ #7 " + tr("statusbar.checks") + " 1", FxTestSupport.callOnFx(checks::getText));

        FxTestSupport.runOnFx(() -> registry.run("github.showChecks"));
        await("the checks list", overlay::isShowing);
        try {
            ChecksListCard card = FxTestSupport.callOnFx(() -> FxTestSupport.field(github, "checksCard"));
            assertEquals(List.of("✗ build", "✓ lint"), FxTestSupport.callOnFx(card::rowTexts), "failed first");
            List<String> links = FxTestSupport.callOnFx(() -> card.lookupAll(".hyperlink").stream()
                    .map(n -> ((javafx.scene.control.Hyperlink) n).getText())
                    .toList());
            assertEquals(
                    List.of(tr("github.checks.viewLog"), tr("github.checks.open")),
                    links,
                    "the failed Actions job offers its log and its page; the linkless check offers neither");
        } finally {
            FxTestSupport.runOnFx(overlay::hide);
        }
    }

    // --- G13 / G11: the tool window --------------------------------------------------------------

    /** G13: the list stopped at 50 with no sign that more exist, and never said whose rows these are. */
    @Test
    @Order(5)
    void theListNamesItsRepositoryAndOffersToLoadMore() throws Exception {
        FxTestSupport.runOnFx(() -> toolWindows.open(githubWindow));
        await("the first page", () -> panel.rows().size() == GitHubListQuery.DEFAULT_LIMIT + 1);

        List<Object> rows = FxTestSupport.callOnFx(panel::rows);
        assertInstanceOf(GitHubPanel.MoreRow.class, rows.get(rows.size() - 1), "the last row says more exist");
        await("the resolved repository", () -> "upstream-org/r".equals(panel.repositoryText()));

        ListView<Object> list = FxTestSupport.field(panel, "list");
        FxTestSupport.runOnFx(() -> {
            panel.selectRow(rows.size() - 1);
            list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        await("the longer list", () -> panel.rows().size() == 51);
        assertTrue(logged("pr list --state open --limit 101") > 0, "the limit was raised, plus one to detect more");
        assertFalse(
                FxTestSupport.callOnFx(() -> panel.rows().get(50)) instanceof GitHubPanel.MoreRow,
                "all 51 fit now, so there is no load-more row");
    }

    /** G13: open-only lists with no way to see closed or merged pull requests, or just one's own. */
    @Test
    @Order(6)
    void theStateAndMineFiltersAreSentToGh() throws Exception {
        ChoiceBox<GitHubListQuery.State> state = FxTestSupport.field(panel, "stateFilter");
        ToggleButton mine = FxTestSupport.field(panel, "mineToggle");

        FxTestSupport.runOnFx(() -> state.setValue(GitHubListQuery.State.MERGED));
        await("the merged list to be asked for", () -> logged("pr list --state merged --limit 51") > 0);

        FxTestSupport.runOnFx(mine::fire);
        await("mine", () -> logged("pr list --state merged --author @me --limit 51") > 0);

        FxTestSupport.runOnFx(() -> {
            mine.fire();
            state.setValue(GitHubListQuery.State.OPEN);
        });
        await(
                "the open list again",
                () -> panel.placeholderText() != null && panel.rows().size() == 51);
    }

    /** G11: only github.showRuns existed, so Issues could not be reached from the keyboard. */
    @Test
    @Order(7)
    void everySegmentHasACommandAndIsFocusable() throws Exception {
        FxTestSupport.runOnFx(() -> registry.run("github.showIssues"));
        await("the Issues segment", () -> panel.mode() == GitHubPanel.Mode.ISSUES);
        FxTestSupport.runOnFx(() -> registry.run("github.showPrs"));
        await("the Pull Requests segment", () -> panel.mode() == GitHubPanel.Mode.PRS);

        for (String field : List.of("prsToggle", "issuesToggle", "runsToggle")) {
            ToggleButton toggle = FxTestSupport.field(panel, field);
            assertTrue(FxTestSupport.callOnFx(toggle::isFocusTraversable), field + " is reachable with Tab");
        }
    }

    /** G11: rerun / cancel / copy-URL were only in the mouse menu. G19: a run id is not a quantity. */
    @Test
    @Order(8)
    void theRunCommandsActOnTheSelectedRowAndNeverOnAHiddenOne() throws Exception {
        FxTestSupport.runOnFx(() -> registry.run("github.showRuns"));
        await(
                "the run list",
                () -> panel.mode() == GitHubPanel.Mode.RUNS && panel.rows().size() == 1);
        FxTestSupport.runOnFx(() -> panel.selectRow(0));

        // Closed: the selection is not on screen, so the command opens the window and asks instead.
        FxTestSupport.runOnFx(() -> toolWindows.close(githubWindow));
        FxTestSupport.runOnFx(() -> registry.run("github.cancelRun"));
        assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(githubWindow)), "the window was opened");
        assertEquals(tr("status.github.row.choose"), FxTestSupport.callOnFx(echo::getText));
        assertEquals(0, logged("run cancel"), "nothing was cancelled");

        await(
                "the reloaded run list",
                () -> panel.placeholderText() != null && panel.rows().size() == 1);
        FxTestSupport.runOnFx(() -> {
            panel.selectRow(0);
            registry.run("github.rerunRun"); // still in progress: refused, with the reason
        });
        assertEquals(tr("status.github.row.stillActive"), FxTestSupport.callOnFx(echo::getText));

        FxTestSupport.runOnFx(() -> registry.run("github.cancelRun"));
        assertTrue(
                FxTestSupport.callOnFx(echo::getText).contains(String.valueOf(RUN_ID)),
                "the run id is written as GitHub writes it, not digit-grouped: "
                        + FxTestSupport.callOnFx(echo::getText));
        await("gh run cancel", () -> logged("run cancel " + RUN_ID) == 1);
    }

    /** G11: the Menu key / Shift+F10 arrive at the list, whose cells hold the menus. */
    @Test
    @Order(9)
    void aRowsMenuOpensFromTheKeyboard() throws Exception {
        await(
                "the run list",
                () -> panel.placeholderText() != null && panel.rows().size() == 1);
        ListView<Object> list = FxTestSupport.field(panel, "list");
        boolean[] reached = new boolean[1];
        FxTestSupport.runOnFx(() -> {
            panel.selectRow(0);
            panel.applyCss();
            panel.layout();
            list.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
                if (e.getTarget() instanceof ListCell) {
                    reached[0] = true;
                    e.consume(); // the routing is what is under test; do not pop a real menu up
                }
            });
            list.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, true, null));
        });
        assertTrue(reached[0], "the selected row's menu is requested for the keyboard too");
    }

    // --- G15 / G5 / G4: the review tab -----------------------------------------------------------

    /** G15: a one-file pull request opened the bare file diff; its description and actions were unreachable. */
    @Test
    @Order(10)
    void aSingleFilePullRequestOpensTheReviewTab() throws Exception {
        FxTestSupport.runOnFx(() -> github.reviewPrNumber(7));
        await("the review tab", () -> selected() instanceof PrReviewPane);

        PrReviewPane pane = (PrReviewPane) FxTestSupport.callOnFx(this::selected);
        assertEquals(1, FxTestSupport.callOnFx(() -> pane.files().size()));
        assertEquals("", FxTestSupport.callOnFx(pane::notice), "an ordinary diff needs no notice");
    }

    /** G5: hunk lines were joined and numbered from 1, and the no-final-newline flag was dropped. */
    @Test
    @Order(11)
    void aPullRequestFileKeepsItsHunksLineNumbersAndItsMissingFinalNewline() throws Exception {
        PrReviewPane pane = (PrReviewPane) FxTestSupport.callOnFx(this::selected);
        FxTestSupport.runOnFx(() -> {
            FilePatch file = pane.files().get(0);
            FxTestSupport.<Consumer<FilePatch>>field(pane, "onOpenFile").accept(file);
        });
        await("the file's diff tab", () -> selected() instanceof DiffViewerPane);

        DiffViewerPane diffPane = (DiffViewerPane) FxTestSupport.callOnFx(this::selected);
        DiffModel model = FxTestSupport.callOnFx(() -> FxTestSupport.field(diffPane, "model"));
        List<Integer> rightLines =
                model.rows().stream().map(r -> r.rightLine()).filter(n -> n > 0).toList();
        assertEquals(List.of(40, 41, 500, 501), rightLines, "each hunk at the lines its header states");
        assertFalse(model.rightFinalNewline(), "the head side does not end in a newline, and says so");
        assertTrue(model.leftFinalNewline());
    }

    /** G4: above 300 files GitHub answers HTTP 406 to `gh pr diff`, and the review could not open at all. */
    @Test
    @Order(12)
    void aPullRequestTooLargeForOneDiffOpensFromTheFilesApiAndSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> github.reviewPrNumber(9));
        await(
                "the review tab of pull request 9",
                () -> selected() instanceof PrReviewPane p && p.title().contains("9"));

        PrReviewPane pane = (PrReviewPane) FxTestSupport.callOnFx(this::selected);
        assertEquals(2, FxTestSupport.callOnFx(() -> pane.files().size()), "both pages of the files API");
        String notice = FxTestSupport.callOnFx(pane::notice);
        assertTrue(notice.startsWith(tr("github.review.fallback")), notice);
        assertTrue(notice.contains(tr("github.review.fallbackNoPatch", 1)), "one file came without its hunks");
        assertTrue(logged("api repos/{owner}/{repo}/pulls/9/files?per_page=100 --paginate") > 0);
    }

    // --- G6: the forms keep what was typed -------------------------------------------------------

    /** G6: the review form hid before gh ran, so a failure discarded the typed review. */
    @Test
    @Order(13)
    void aFailedReviewKeepsTheFormAndTheTextAndSucceedsOnRetry() throws Exception {
        PrReviewPane pane = (PrReviewPane) FxTestSupport.callOnFx(this::selected);
        Files.writeString(bin.resolve("review.fail"), "1");
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<Runnable>field(pane, "onSubmitReview").run());
        await("the review form", overlay::isShowing);
        TextArea body = (TextArea) FxTestSupport.callOnFx(() -> card().lookup(".text-area"));
        FxTestSupport.runOnFx(() -> {
            body.setText("Looks good to me");
            button(tr("dialog.review.button")).fire();
        });
        await("gh's refusal, in the form", () -> {
            Node error = card().lookup(".overlay-form-error");
            return error != null && error.isVisible();
        });

        assertTrue(FxTestSupport.callOnFx(overlay::isShowing), "the form is still open");
        assertEquals("Looks good to me", FxTestSupport.callOnFx(body::getText), "with the review in it");
        assertFalse(FxTestSupport.callOnFx(body::isDisabled), "and editable again");
        String shown = FxTestSupport.callOnFx(() -> ((Label) card().lookup(".overlay-form-error")).getText());
        assertTrue(shown.contains("Can not approve your own pull request"), shown);

        Files.delete(bin.resolve("review.fail"));
        FxTestSupport.runOnFx(() -> button(tr("dialog.review.button")).fire());
        await("the form to close once gh succeeds", () -> !overlay.isShowing());
        assertEquals(tr("status.github.reviewed", 9), FxTestSupport.callOnFx(echo::getText));
    }

    /** G6: dismissing the form while gh runs must not cost the text when gh then fails. */
    @Test
    @Order(14)
    void aFormDismissedWhileBusyComesBackWithItsTextWhenTheActionFails() throws Exception {
        PrReviewPane pane = (PrReviewPane) FxTestSupport.callOnFx(this::selected);
        Files.writeString(bin.resolve("review.fail"), "1");
        Files.writeString(bin.resolve("delay"), "0.4");
        try {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Runnable>field(pane, "onSubmitReview").run());
            await("the review form", overlay::isShowing);
            FxTestSupport.runOnFx(() -> {
                ((TextArea) card().lookup(".text-area")).setText("Typed, then dismissed");
                button(tr("dialog.review.button")).fire();
                assertTrue(card().lookup(".text-area").isDisabled(), "busy: the fields are locked");
                overlay.hide(); // Esc while gh is still running
            });
            await("the form to come back with the failure", overlay::isShowing);
            assertEquals(
                    "Typed, then dismissed",
                    FxTestSupport.callOnFx(() -> ((TextArea) card().lookup(".text-area")).getText()));
        } finally {
            Files.deleteIfExists(bin.resolve("review.fail"));
            Files.deleteIfExists(bin.resolve("delay"));
            FxTestSupport.runOnFx(overlay::hide);
        }
    }

    // --- G6 / G17: create pull request -----------------------------------------------------------

    /** G6: the form opened on the default branch, to fail only after it was filled in. */
    @Test
    @Order(15)
    void createPullRequestOnTheDefaultBranchSaysSoInsteadOfOpeningTheForm() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(repo.resolve("a.txt"), 0));
        await("the repository again", () -> repo.equals(git.repoRoot()));
        String branch = FxTestSupport.callOnFx(git::branchName);

        FxTestSupport.runOnFx(github::createPr);
        await(
                "the default-branch message",
                () -> tr("status.github.prOnDefaultBranch", branch).equals(echo.getText()));
        assertFalse(FxTestSupport.callOnFx(overlay::isShowing), "no form");
    }

    /** G6: a branch that already has a pull request is offered that pull request. */
    @Test
    @Order(16)
    void createPullRequestOffersTheOneThatAlreadyExists() throws Exception {
        FxTestSupport.runOnFx(() -> git.checkoutBranch("feature"));
        await("the feature branch", () -> "feature".equals(git.branchName()));

        FxTestSupport.runOnFx(github::createPr);
        await("the offer", overlay::isShowing);
        try {
            String message = FxTestSupport.callOnFx(() -> card().lookupAll(".label").stream()
                    .map(n -> ((Label) n).getText())
                    .filter(t -> t != null && t.contains("#7"))
                    .findFirst()
                    .orElse(""));
            assertEquals(tr("dialog.createPr.exists", 7, "Fix thing"), message);
            assertTrue(
                    FxTestSupport.callOnFx(() -> button(tr("dialog.createPr.openExisting")) != null),
                    "with a button that opens it");
            assertTrue(
                    FxTestSupport.callOnFx(() -> card().lookup(".text-field") == null),
                    "and no form to fill in for gh to reject");
        } finally {
            FxTestSupport.runOnFx(overlay::hide);
        }
    }

    /** G17: the form started blank, with a free-text base. G6: a failure discarded what was typed. */
    @Test
    @Order(17)
    void theCreateFormStartsFromTheBranchsCommitAndSurvivesAFailure() throws Exception {
        Files.delete(bin.resolve("branchpr.json")); // the branch has no pull request yet
        Files.writeString(bin.resolve("create.fail"), "1");
        FxTestSupport.runOnFx(github::createPr);
        await("the create form", () -> overlay.isShowing() && card().lookup(".text-field") != null);

        TextField title = (TextField) FxTestSupport.callOnFx(() -> card().lookup(".text-field"));
        TextArea body = (TextArea) FxTestSupport.callOnFx(() -> card().lookup(".text-area"));
        @SuppressWarnings("unchecked")
        ComboBox<String> base = (ComboBox<String>) FxTestSupport.callOnFx(() -> card().lookup(".combo-box"));
        assertEquals("Add the feature", FxTestSupport.callOnFx(title::getText), "the one commit's subject");
        assertEquals("It was missing.", FxTestSupport.callOnFx(body::getText), "and its body");
        assertEquals("main", FxTestSupport.callOnFx(() -> base.getEditor().getText()), "the default branch");
        assertTrue(
                FxTestSupport.callOnFx(() ->
                        card().lookupAll(".label").stream().anyMatch(n -> "feature".equals(((Label) n).getText()))),
                "the head branch is shown");
        CheckBox push = (CheckBox) FxTestSupport.callOnFx(() -> card().lookupAll(".check-box").stream()
                .filter(n -> tr("dialog.createPr.push", "feature").equals(((CheckBox) n).getText()))
                .findFirst()
                .orElse(null));
        assertTrue(push != null && FxTestSupport.callOnFx(push::isSelected), "a branch with no upstream offers a push");

        FxTestSupport.runOnFx(() -> {
            push.setSelected(false); // the stand-in remote does not exist: do not push
            title.setText("My careful title");
            button(tr("dialog.createPr.button")).fire();
        });
        await("gh's refusal, in the form", () -> {
            Node error = card().lookup(".overlay-form-error");
            return error != null && error.isVisible();
        });
        assertEquals("My careful title", FxTestSupport.callOnFx(title::getText), "the typed title is still there");
        assertTrue(logged("pr create --title My careful title") > 0);

        Files.delete(bin.resolve("create.fail"));
        FxTestSupport.runOnFx(() -> button(tr("dialog.createPr.button")).fire());
        await("the form to close once gh succeeds", () -> !overlay.isShowing());
    }

    /** G19: a row with no URL answered "No file to open on GitHub". */
    @Test
    @Order(18)
    void aRowWithoutAUrlSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> github.openUrl(" "));
        assertEquals(tr("status.github.noUrl"), FxTestSupport.callOnFx(echo::getText));
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** The card the overlay is showing. FX thread. */
    private Node card() {
        StackPane root = FxTestSupport.field(overlay, "overlayRoot");
        return root.getChildren().size() > 1 ? root.getChildren().get(1) : root;
    }

    /** The card's button labelled {@code text}, or null. FX thread. */
    private Button button(String text) {
        return (Button) card().lookupAll(".button").stream()
                .filter(n -> n instanceof Button b && text.equals(b.getText()))
                .findFirst()
                .orElse(null);
    }

    private Object selected() {
        Tab tab = area.selectedTab();
        return tab == null ? null : tab.getUserData();
    }

    private long logged(String needle) throws Exception {
        Path log = bin.resolve("gh.log");
        if (!Files.exists(log)) {
            return 0;
        }
        try (var lines = Files.lines(log)) {
            return lines.filter(line -> line.contains(needle)).count();
        }
    }

    /** Polls {@code condition} on the FX thread until it holds. */
    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!FxTestSupport.callOnFx(condition)) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }
}
