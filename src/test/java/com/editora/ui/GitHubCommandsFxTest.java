package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Tab;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.github.GitHubListQuery;
import com.editora.github.PrListParser;
import com.editora.github.RunListParser;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GitHub commands when {@code gh} answers — and when it refuses: each failure is shown with gh's own
 * words, an empty answer is not passed off as a failure, and nothing is left half done. {@code gh} is a shell
 * script in the temp folder that answers from files the test writes; nothing reaches the network.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GitHubCommandsFxTest {

    /**
     * Answers {@code gh <a> <b> …} from {@code out.<a>-<b>} (stdout, exit 0) or {@code fail.<a>-<b>} (stderr,
     * exit 1); with neither, prints nothing and succeeds. Every call is appended to {@code gh.log}.
     */
    private static final String FAKE_GH = """
            #!/bin/sh
            D="$(dirname "$0")"
            printf '%s\\n' "$*" >> "$D/gh.log"
            case "$1" in
              --version) echo "gh version 2.96.0 (test stand-in)"; exit 0;;
              auth) exit 0;;
              api|browse) key="$1";;
              *) key="$1-$2";;
            esac
            if [ -f "$D/fail.$key" ]; then cat "$D/fail.$key" >&2; exit 1; fi
            if [ -f "$D/out.$key" ]; then cat "$D/out.$key"; exit 0; fi
            case "$key" in
              pr-view|pr-checks) echo "no pull requests found for branch" >&2; exit 1;;
              pr-list|issue-list|run-list) echo "[]";;
            esac
            exit 0
            """;

    private static final String TWO_PRS = "[" + pr(7, "Fix thing") + "," + pr(8, "Add thing") + "]";

    private Path base;
    private Path bin;
    private Path repo;
    private Path file;
    private AsyncTestScope async;
    private GitFeatureFx w;
    private GitHubCoordinator github;

    private static String pr(int number, String title) {
        return "{\"number\":" + number + ",\"title\":\"" + title + "\",\"author\":{\"login\":\"alice\"},"
                + "\"headRefName\":\"b" + number + "\",\"baseRefName\":\"main\",\"state\":\"OPEN\",\"isDraft\":false,"
                + "\"updatedAt\":\"2026-01-01T00:00:00Z\",\"url\":\"https://github.com/o/r/pull/" + number + "\"}";
    }

    private static String run(long id, String workflow, String conclusion) {
        return "{\"databaseId\":" + id + ",\"displayTitle\":\"Build\",\"workflowName\":\"" + workflow + "\","
                + "\"headBranch\":\"main\",\"status\":\"completed\",\"conclusion\":\"" + conclusion + "\","
                + "\"event\":\"push\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                + "\"url\":\"https://github.com/o/r/actions/runs/" + id + "\"}";
    }

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeFalse(GitTestRepo.windows(), "the gh stand-in is a POSIX shell script");
        FxTestSupport.bootToolkit();
        base = Files.createTempDirectory("editora-gh-commands").toRealPath();
        bin = Files.createDirectories(base.resolve("bin"));
        Files.writeString(bin.resolve("fakegh"), FAKE_GH);

        GitTestRepo testRepo = GitTestRepo.init(base);
        repo = testRepo.root;
        file = testRepo.write("a.txt", "a1\n");
        testRepo.commitAll("first");
        testRepo.git("remote", "add", "origin", "https://github.com/o/r.git");

        async = new AsyncTestScope();
        String command = "sh " + bin.resolve("fakegh");
        FxWindowFixture fx = async.own(FxWindowFixture.create(Files.createDirectories(base.resolve("cfg")), shared -> {
            shared.getSettings().setGitSupport(true);
            shared.getSettings().setGithubSupport(true);
            shared.getSettings().setGhPath(command);
        }));
        w = GitFeatureFx.attach(fx);
        github = FxTestSupport.field(fx.controller, "github");
        w.open(file);
        OverlayTestKit.await(
                async,
                "gh to be detected",
                () -> github.service().availability() != null
                        && github.service().availability().ready());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (async != null) {
            async.close();
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

    /** Every test starts from a gh that answers nothing special, on the repository's file tab. */
    @BeforeEach
    void reset() throws Exception {
        try (var entries = Files.list(bin)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith("out.") || name.startsWith("fail.") || name.equals("gh.log")) {
                    Files.delete(entry);
                }
            }
        }
        FxTestSupport.runOnFx(() -> OverlayTestKit.cancelPicker(w.scene()));
        w.open(file);
        w.clearStatus();
    }

    private void answer(String key, String stdout) throws Exception {
        Files.writeString(bin.resolve("out." + key), stdout);
    }

    private void refuse(String key, String stderr) throws Exception {
        Files.writeString(bin.resolve("fail." + key), stderr);
    }

    private List<String> calls(String prefix) throws Exception {
        Path log = bin.resolve("gh.log");
        if (!Files.exists(log)) {
            return List.of();
        }
        return Files.readAllLines(log).stream()
                .filter(line -> line.startsWith(prefix))
                .toList();
    }

    private void awaitMessage(String message) throws Exception {
        OverlayTestKit.await(async, "\"" + message + "\"", () -> message.equals(w.status()));
    }

    /** Runs {@code action} and returns the error dialog headed {@code header} that it ends in. */
    private OverlayTestKit.Shown errorDialog(String header, Runnable action) throws Exception {
        AtomicReference<OverlayTestKit.Shown> error = new AtomicReference<>();
        CountDownLatch shown = OverlayTestKit.answerDialog(
                async, pane -> header.equals(pane.getHeaderText()), ButtonBar.ButtonData.OK_DONE, error);
        FxTestSupport.runOnFx(action);
        async.await(shown, "the error dialog \"" + header + "\"");
        async.awaitFx();
        assertEquals(header, w.status(), "the status bar carries the summary");
        return error.get();
    }

    // --- the tool window's lists -------------------------------------------------------------------

    @Test
    void aListGhRefusesIsReportedWithItsFirstLineAndNeverAsAnEmptyList() throws Exception {
        refuse("pr-list", "\n   HTTP 502: Bad gateway\nsecond line\n");
        refuse("issue-list", "the repository has disabled issues\n");
        refuse("run-list", ""); // gh said nothing at all
        GitHubListQuery query = GitHubListQuery.open(GitHubListQuery.DEFAULT_LIMIT);
        List<String> errors = new CopyOnWriteArrayList<>();
        List<Object> pages = new CopyOnWriteArrayList<>();

        FxTestSupport.runOnFx(() -> github.fetchPrs(query, pages::add, errors::add));
        OverlayTestKit.await(async, "the pull request failure", () -> errors.size() == 1);
        assertEquals(tr("status.github.prListFailed") + ": HTTP 502: Bad gateway", errors.get(0));
        assertEquals(errors.get(0), w.status());

        FxTestSupport.runOnFx(() -> github.fetchIssues(query, pages::add, errors::add));
        OverlayTestKit.await(async, "the issue failure", () -> errors.size() == 2);
        assertEquals(tr("status.github.issueListFailed") + ": the repository has disabled issues", errors.get(1));

        FxTestSupport.runOnFx(() -> github.fetchRuns(query, pages::add, errors::add));
        OverlayTestKit.await(async, "the run failure", () -> errors.size() == 3);
        assertTrue(errors.get(2).startsWith(tr("status.github.runListFailed")), errors.get(2));
        assertEquals(List.of(), pages, "a failure never also delivers a page");
    }

    @Test
    void theListsDeliverWhatGhReturned() throws Exception {
        answer("pr-list", TWO_PRS);
        answer("run-list", "[" + run(11, "CI", "success") + "]");
        GitHubListQuery query = GitHubListQuery.open(GitHubListQuery.DEFAULT_LIMIT);
        List<GitHubListQuery.Page<PrListParser.PullRequest>> prs = new CopyOnWriteArrayList<>();
        List<GitHubListQuery.Page<RunListParser.WorkflowRun>> runs = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();

        FxTestSupport.runOnFx(() -> {
            github.fetchPrs(query, prs::add, errors::add);
            github.fetchRuns(query, runs::add, errors::add);
        });
        OverlayTestKit.await(async, "both lists", () -> prs.size() == 1 && runs.size() == 1);

        assertEquals(
                List.of(7, 8),
                prs.get(0).items().stream()
                        .map(PrListParser.PullRequest::number)
                        .toList());
        assertFalse(prs.get(0).more());
        assertEquals("CI", runs.get(0).items().get(0).workflowName());
        assertEquals(List.of(), errors);
    }

    // --- checkout ----------------------------------------------------------------------------------

    @Test
    void checkingOutAPullRequestRunsGhInTheRepositoryAndReportsARefusal() throws Exception {
        FxTestSupport.runOnFx(() -> github.checkoutNumber(7));
        awaitMessage(tr("status.github.checkedOut", 7));
        assertEquals(List.of("pr checkout 7"), calls("pr checkout"));

        refuse("pr-checkout", "error: Your local changes to the following files would be overwritten\n");
        OverlayTestKit.Shown error = errorDialog(tr("status.github.checkoutFailed"), () -> github.checkoutNumber(8));
        assertTrue(error.content().contains("local changes"), error.content());
        assertEquals("a1\n", Files.readString(file));
    }

    @Test
    void thePullRequestPickerListsOpenOnesAndChecksOutThePickedOne() throws Exception {
        answer("pr-list", TWO_PRS);
        FxTestSupport.runOnFx(github::checkoutPr);
        OverlayTestKit.await(
                async, "the picker", () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
        assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pick(w.scene(), "Add thing")));
        awaitMessage(tr("status.github.checkedOut", 8));
        assertEquals(List.of("pr checkout 8"), calls("pr checkout"));
    }

    @Test
    void thePullRequestPickerSaysWhenThereAreNoneAndShowsAFailure() throws Exception {
        FxTestSupport.runOnFx(github::viewPrDiff);
        awaitMessage(tr("status.github.noPrs"));
        assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.picker(w.scene())));

        refuse("pr-list", "GraphQL: Could not resolve to a Repository\n");
        OverlayTestKit.Shown error = errorDialog(tr("status.github.prListFailed"), github::checkoutPr);
        assertTrue(error.content().contains("Could not resolve to a Repository"), error.content());
        assertEquals(List.of(), calls("pr checkout"), "nothing was checked out");
    }

    // --- review ------------------------------------------------------------------------------------

    @Test
    void aPullRequestDiffThatCannotBeFetchedIsShownAsGhsError() throws Exception {
        refuse("pr-diff", "GraphQL: Could not resolve to a PullRequest with the number of 99.\n");
        OverlayTestKit.Shown error = errorDialog(tr("status.github.diffFailed"), () -> github.reviewPrNumber(99));
        assertTrue(error.content().contains("Could not resolve to a PullRequest"), error.content());

        // Too large for one diff, and the files API refuses too: that second reason is the one shown.
        refuse("pr-diff", "HTTP 406: Sorry, the diff exceeded the maximum number of files (300).\ndiff too_large\n");
        refuse("api", "HTTP 403: rate limit exceeded\n");
        OverlayTestKit.Shown second = errorDialog(tr("status.github.diffFailed"), () -> github.reviewPrNumber(9));
        assertTrue(second.content().contains("rate limit exceeded"), second.content());
        assertFalse(w.activeContent() instanceof PrReviewPane, "no review tab was opened");
    }

    @Test
    void aPullRequestWithoutAnyDiffSaysItHasNoChanges() throws Exception {
        FxTestSupport.runOnFx(() -> github.reviewPrNumber(5));
        awaitMessage(tr("status.github.prDiffEmpty", 5));
        assertFalse(w.activeContent() instanceof PrReviewPane);
    }

    @Test
    void openAllFilesAsksFirstForALargePullRequest() throws Exception {
        StringBuilder diff = new StringBuilder();
        for (int i = 1; i <= 13; i++) {
            diff.append("diff --git a/f")
                    .append(i)
                    .append(".txt b/f")
                    .append(i)
                    .append(".txt\n--- a/f")
                    .append(i)
                    .append(".txt\n+++ b/f")
                    .append(i)
                    .append(".txt\n@@ -1 +1 @@\n-old\n+new\n");
        }
        answer("pr-diff", diff.toString());
        FxTestSupport.runOnFx(() -> github.reviewPrNumber(13));
        OverlayTestKit.await(async, "the review tab", () -> w.area.selectedTab().getUserData() instanceof PrReviewPane);
        PrReviewPane review = (PrReviewPane) w.activeContent();
        assertEquals(13, FxTestSupport.callOnFx(() -> review.files().size()));
        int tabsBefore = FxTestSupport.callOnFx(() -> w.area.size());
        Hyperlink openAll = FxTestSupport.callOnFx(() -> OverlayTestKit.descendants(review, Hyperlink.class).stream()
                .filter(link -> tr("github.review.openAll", 13).equals(link.getText()))
                .findFirst()
                .orElseThrow());

        AtomicReference<OverlayTestKit.Shown> question = new AtomicReference<>();
        CountDownLatch declined = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.CANCEL_CLOSE, question);
        FxTestSupport.runOnFx(openAll::fire);
        async.await(declined, "the open-all question");
        async.awaitFx();
        assertEquals(tr("dialog.github.openAllConfirm", 13), question.get().content());
        assertEquals(tabsBefore, FxTestSupport.callOnFx(() -> w.area.size()), "declined: no tab was opened");

        CountDownLatch agreed = OverlayTestKit.answerAnyDialog(async, ButtonBar.ButtonData.OK_DONE, null);
        FxTestSupport.runOnFx(openAll::fire);
        async.await(agreed, "the open-all question, accepted");
        OverlayTestKit.await(async, "the thirteen diff tabs", () -> w.area.size() == tabsBefore + 13);
        List<String> titles = FxTestSupport.callOnFx(() -> {
            List<String> out = new ArrayList<>();
            for (Tab tab : w.area.tabs()) {
                if (tab.getUserData() instanceof DiffViewerPane pane) {
                    out.add(pane.title());
                }
            }
            return out;
        });
        assertTrue(titles.contains(tr("diff.title.prFile", "f13.txt", 13)), titles.toString());

        // Clean up the fourteen tabs this test opened, so the shared window is as the next test expects.
        FxTestSupport.runOnFx(() -> {
            for (Tab tab : List.copyOf(w.area.tabs())) {
                if (tab.getUserData() instanceof DiffViewerPane || tab.getUserData() instanceof PrReviewPane) {
                    w.area.remove(tab);
                }
            }
        });
    }

    // --- workflow runs -----------------------------------------------------------------------------

    @Test
    void aFailedRunsLogGoesToTheCiConsoleAndAFailureToFetchItIsShown() throws Exception {
        answer("run-view", "build\tUNKNOWN STEP\tcom.example.FooTest > fails FAILED\nbuild\tUNKNOWN STEP\tdone\n");
        FxTestSupport.runOnFx(() -> github.viewRunLog(42L, "CI"));
        OverlayTestKit.await(async, "the log in the console", () -> ciConsole().contains("FooTest > fails FAILED"));
        String shown = FxTestSupport.callOnFx(this::ciConsole);
        assertTrue(shown.indexOf("FooTest > fails FAILED") < shown.indexOf("done"), "in gh's order: " + shown);
        assertTrue(shown.contains("done"), shown);
        assertEquals(List.of("run view 42 --log-failed"), calls("run view"));

        refuse("run-view", "run 43 has no failed jobs\n");
        OverlayTestKit.Shown error = errorDialog(tr("status.github.runLogFailed"), () -> github.viewRunLog(43L, "CI"));
        assertTrue(error.content().contains("no failed jobs"), error.content());
        assertEquals("", FxTestSupport.callOnFx(this::ciConsole), "the previous run's log is gone");
        assertTrue(
                FxTestSupport.callOnFx(this::ciConsoleStatus).contains("no failed jobs"),
                "the console's status line says why too");
    }

    @Test
    void theRunPickerOffersOnlyFailedRunsWhenThereAreAny() throws Exception {
        answer("run-list", "[" + run(21, "CI", "success") + "," + run(22, "Release", "failure") + "]");
        answer("run-view", "release\tstep\tboom\n");
        FxTestSupport.runOnFx(github::viewRunLogPicked);
        OverlayTestKit.await(
                async,
                "the run picker",
                () -> !OverlayTestKit.pickerItems(w.scene()).isEmpty());
        List<?> offered = FxTestSupport.callOnFx(() -> OverlayTestKit.pickerItems(w.scene()));
        assertEquals(
                List.of(22L),
                offered.stream()
                        .map(run -> ((RunListParser.WorkflowRun) run).databaseId())
                        .toList());
        assertTrue(FxTestSupport.callOnFx(() -> OverlayTestKit.pickRow(w.scene(), 0)));
        OverlayTestKit.await(async, "the picked run's log", () -> ciConsole().contains("boom"));
        assertEquals(List.of("run view 22 --log-failed"), calls("run view"));

        // Nothing failed: every run is offered rather than an empty picker.
        answer("run-list", "[" + run(31, "CI", "success") + "," + run(32, "Docs", "success") + "]");
        FxTestSupport.runOnFx(github::viewRunLogPicked);
        OverlayTestKit.await(
                async,
                "the run picker",
                () -> OverlayTestKit.pickerItems(w.scene()).size() == 2);
        FxTestSupport.runOnFx(() -> OverlayTestKit.cancelPicker(w.scene()));
    }

    @Test
    void theRunPickerSaysWhenThereAreNoRunsAndShowsAFailure() throws Exception {
        FxTestSupport.runOnFx(github::viewRunLogPicked);
        awaitMessage(tr("status.github.noRuns"));
        assertNull(FxTestSupport.callOnFx(() -> OverlayTestKit.picker(w.scene())));

        refuse("run-list", "HTTP 403: Resource not accessible by integration\n");
        OverlayTestKit.Shown error = errorDialog(tr("status.github.runListFailed"), github::viewRunLogPicked);
        assertTrue(error.content().contains("Resource not accessible"), error.content());
    }

    @Test
    void reRunAndCancelTellGhWhichRunAndReportARefusal() throws Exception {
        FxTestSupport.runOnFx(() -> github.rerunRun(51L, true));
        awaitMessage(tr("status.github.rerunStarted", 51L));
        FxTestSupport.runOnFx(() -> github.rerunRun(52L, false));
        awaitMessage(tr("status.github.rerunStarted", 52L));
        assertEquals(List.of("run rerun 51 --failed", "run rerun 52"), calls("run rerun"));

        FxTestSupport.runOnFx(() -> github.cancelRun(53L));
        awaitMessage(tr("status.github.runCancelled", 53L));
        assertEquals(List.of("run cancel 53"), calls("run cancel"));

        refuse("run-rerun", "run 54 cannot be rerun; its workflow file may be broken\n");
        OverlayTestKit.Shown rerun = errorDialog(tr("status.github.rerunFailed"), () -> github.rerunRun(54L, false));
        assertTrue(rerun.content().contains("cannot be rerun"), rerun.content());
        refuse("run-cancel", "Cannot cancel a workflow run that is completed\n");
        OverlayTestKit.Shown cancel = errorDialog(tr("status.github.cancelFailed"), () -> github.cancelRun(55L));
        assertTrue(cancel.content().contains("that is completed"), cancel.content());
    }

    // --- links -------------------------------------------------------------------------------------

    @Test
    void copyUrlPutsTheRowsUrlOnTheClipboardAndIgnoresARowWithoutOne() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ClipboardContent before = new ClipboardContent();
            before.putString("what was there");
            Clipboard.getSystemClipboard().setContent(before);
            github.copyUrl("  ");
            github.copyUrl(null);
        });
        assertEquals(
                "what was there",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        assertEquals("", w.status());

        FxTestSupport.runOnFx(() -> github.copyUrl("https://github.com/o/r/pull/7"));
        assertEquals(
                "https://github.com/o/r/pull/7",
                FxTestSupport.callOnFx(() -> Clipboard.getSystemClipboard().getString()));
        assertEquals(tr("status.github.copiedUrl"), w.status());
    }

    @Test
    void openOnGitHubAsksGhForTheFilesUrlAtTheCaretLine() throws Exception {
        answer("browse", "https://github.com/o/r/blob/main/a.txt#L1\n");
        FxTestSupport.runOnFx(github::openOnGitHub);
        awaitMessage(tr("status.github.opened"));
        List<String> browse = calls("browse");
        assertEquals(1, browse.size());
        assertTrue(browse.get(0).contains("a.txt:1"), browse.get(0));
        assertTrue(browse.get(0).startsWith("browse --no-browser"), "gh only resolves the URL; it opens nothing");

        // gh answers with no URL: that is a failure, not "opened".
        answer("browse", "\n");
        errorDialog(tr("status.github.browseFailed"), github::openOnGitHub);
        refuse("browse", "could not determine the remote for this branch\n");
        OverlayTestKit.Shown error = errorDialog(tr("status.github.browseFailed"), github::openOnGitHub);
        assertTrue(error.content().contains("could not determine the remote"), error.content());
    }

    @Test
    void openOnGitHubNeedsAFileOfTheRepository() throws Exception {
        Path outside = Files.writeString(base.resolve("outside.txt"), "not in the repository\n");
        try {
            FxTestSupport.runOnFx(() -> w.fx.controller.openAndNavigate(outside, 0));
            OverlayTestKit.await(
                    async,
                    "the outside tab",
                    () -> w.active() != null && outside.equals(w.active().getPath()));
            FxTestSupport.runOnFx(w.git::refresh);
            OverlayTestKit.await(async, "git to notice", () -> w.git.repoRoot() == null);

            FxTestSupport.runOnFx(github::openOnGitHub);
            assertEquals(tr("status.github.noRepo"), w.status());
            assertEquals(List.of(), calls("browse"));
        } finally {
            FxTestSupport.runOnFx(() -> {
                for (Tab tab : List.copyOf(w.area.tabs())) {
                    if (tab.getUserData() instanceof com.editora.editor.EditorBuffer buffer
                            && outside.equals(buffer.getPath())) {
                        w.area.remove(tab);
                    }
                }
            });
            Files.deleteIfExists(outside);
        }
    }

    // --- the setting -------------------------------------------------------------------------------

    @Test
    void toggleSupportFlipsTheSettingAndSaysWhichWayAndTheCommandsThenRefuse() throws Exception {
        try {
            FxTestSupport.runOnFx(github::toggleSupport);
            assertFalse(w.fx.shared.getSettings().isGithubSupport());
            assertEquals(tr("status.toggle.github", tr("common.off")), w.status());

            List<String> errors = new CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(() -> github.fetchPrs(
                    GitHubListQuery.open(GitHubListQuery.DEFAULT_LIMIT),
                    page -> errors.add("a page was delivered"),
                    errors::add));
            assertEquals(1, errors.size(), "the refusal is delivered at once");
            assertEquals(errors.get(0), w.status());
            assertEquals(List.of(), calls("pr list"), "gh is not run while GitHub support is off");
        } finally {
            if (!w.fx.shared.getSettings().isGithubSupport()) {
                FxTestSupport.runOnFx(github::toggleSupport);
            }
        }
        assertTrue(w.fx.shared.getSettings().isGithubSupport());
        assertEquals(tr("status.toggle.github", tr("common.on")), w.status());
    }

    // --- plumbing ----------------------------------------------------------------------------------

    /** The status line above the CI console. FX thread. */
    private String ciConsoleStatus() {
        Object panel = FxTestSupport.field(w.fx.controller, "buildOutputPanel");
        Map<Object, Object> consoles = FxTestSupport.field(panel, "consoles");
        return FxTestSupport.<javafx.scene.control.Label>field(consoles.get(github), "status")
                .getText();
    }

    /** The text of the Output window's CI console ({@code ""} before the first log). FX thread. */
    private String ciConsole() {
        Object panel = FxTestSupport.field(w.fx.controller, "buildOutputPanel");
        Map<Object, Object> consoles = FxTestSupport.field(panel, "consoles");
        Object console = consoles.get(github);
        return console == null
                ? ""
                : FxTestSupport.<CodeArea>field(console, "output").getText();
    }
}
