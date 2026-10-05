package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.ToggleButton;

import com.editora.github.GitHubService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The GitHub surfaces against an offline stand-in for {@code gh} (a shell script answering from canned files;
 * nothing touches the network): PR actions stay bound to the repository they were listed from, a failed or
 * superseded list fetch ends the loading state, the CI roll-up and the tool-window gating follow the Git
 * state, and a running {@code gh pr checkout} survives the window closing.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitHubRepositoryBindingFxTest {

    private static final String FAKE_GH = """
            #!/bin/sh
            D="$(dirname "$0")"
            printf '%s | cwd=%s\\n' "$*" "$(pwd)" >> "$D/gh.log"
            case "$1" in
              --version) echo "gh version 2.60.0 (test stand-in)"; exit 0;;
              auth) exit 0;;
            esac
            if [ -f "$D/delay" ]; then sleep "$(cat "$D/delay")"; fi
            case "$1 $2" in
              "pr list") cat "$D/prlist.json"; exit 0;;
              "issue list") if [ -f "$D/issue.fail" ]; then echo "HTTP 403: API rate limit exceeded" >&2; exit 1; fi; echo "[]"; exit 0;;
              "run list") echo "[]"; exit 0;;
              "pr view") cat "$D/prview.json"; exit 0;;
              "pr diff") cat "$D/prdiff.patch"; exit 0;;
              "pr checks") cat "$D/checks.json"; exit 0;;
              "pr checkout") echo started > "$D/checkout.state"; sleep 2; echo finished > "$D/checkout.state"; exit 0;;
            esac
            exit 0
            """;

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
    private Label echo;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeFalse(GitTestRepo.windows(), "the gh stand-in is a POSIX shell script");
        FxTestSupport.bootToolkit();
        base = Files.createTempDirectory("editora-gh-binding").toRealPath();
        bin = Files.createDirectories(base.resolve("bin"));
        Files.writeString(bin.resolve("fakegh"), FAKE_GH);
        Files.writeString(
                bin.resolve("prlist.json"),
                "[{\"number\":7,\"title\":\"Fix thing\",\"author\":{\"login\":\"alice\"},\"headRefName\":\"fix\","
                        + "\"baseRefName\":\"main\",\"state\":\"OPEN\",\"isDraft\":false,"
                        + "\"updatedAt\":\"2026-01-01T00:00:00Z\",\"url\":\"https://github.com/o/r/pull/7\"}]");
        Files.writeString(
                bin.resolve("prview.json"),
                "{\"number\":7,\"title\":\"Fix thing\",\"body\":\"Body\",\"author\":{\"login\":\"alice\"},"
                        + "\"baseRefName\":\"main\",\"headRefName\":\"fix\",\"state\":\"OPEN\","
                        + "\"url\":\"https://github.com/o/r/pull/7\",\"additions\":3,\"deletions\":2}");
        Files.writeString(bin.resolve("prdiff.patch"), """
                diff --git a/a.txt b/a.txt
                index 1111111..2222222 100644
                --- a/a.txt
                +++ b/a.txt
                @@ -1 +1 @@
                -a1
                +a2
                diff --git a/b.txt b/b.txt
                index 3333333..4444444 100644
                --- a/b.txt
                +++ b/b.txt
                @@ -1 +1,2 @@
                -b1
                +b2
                +more
                """);
        Files.writeString(
                bin.resolve("checks.json"),
                "[{\"name\":\"build\",\"state\":\"FAILURE\",\"bucket\":\"fail\",\"link\":\"\",\"workflow\":\"CI\"}]");

        GitTestRepo testRepo = GitTestRepo.init(base);
        repo = testRepo.root;
        testRepo.write("a.txt", "a1\n");
        testRepo.commitAll("first");
        testRepo.git("remote", "add", "origin", "https://github.com/o/r.git");
        testRepo.git("branch", "other");

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
        echo = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "echo");

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

    /** A7-7 (2): the stripe is re-gated when the Git refresh lands, not on the next tab switch. */
    @Test
    @Order(1)
    void theToolWindowBecomesAvailableWithoutAnotherTabSwitch() throws Exception {
        Set<ToolWindow> unavailable = FxTestSupport.field(toolWindows, "unavailable");

        await("the GitHub tool window to become available", () -> !unavailable.contains(githubWindow));
    }

    /** A7-4 / E1-7: with the review tab active there is no Git context, yet its own links must work. */
    @Test
    @Order(2)
    void theReviewTabsLinksActInTheRepositoryThePullRequestCameFrom() throws Exception {
        FxTestSupport.runOnFx(() -> github.reviewPrNumber(7));
        await("the review tab", () -> selected() instanceof PrReviewPane);
        await("the Git context to follow the review tab", () -> git.repoRoot() == null);
        PrReviewPane pane = (PrReviewPane) FxTestSupport.callOnFx(this::selected);
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");

        long diffsBefore = logged("pr diff 7 | cwd=" + repo);
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<Runnable>field(pane, "onRefresh").run());
        await(
                "the refresh to run gh in the pull request's repository",
                () -> logged("pr diff 7 | cwd=" + repo) > diffsBefore);

        FxTestSupport.runOnFx(
                () -> FxTestSupport.<Runnable>field(pane, "onSubmitReview").run());
        assertTrue(
                FxTestSupport.callOnFx(overlay::isShowing),
                "the submit-review form opens (it used to stop at: " + FxTestSupport.callOnFx(echo::getText) + ")");
        FxTestSupport.runOnFx(overlay::hide);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(repo.resolve("a.txt"), 0));
        await("the repository again", () -> repo.equals(git.repoRoot()));
    }

    /** A7-6 (a): a failed list is shown as a failure, with gh's message — not as an empty list. */
    @Test
    @Order(3)
    void aFailedIssueListShowsGhsErrorInsteadOfNoOpenIssues() throws Exception {
        FxTestSupport.runOnFx(() -> toolWindows.open(githubWindow));
        Files.writeString(bin.resolve("issue.fail"), "1");
        try {
            FxTestSupport.runOnFx(() ->
                    FxTestSupport.<ToggleButton>field(panel, "issuesToggle").fire());
            await("the issue fetch to end", () -> panel.placeholderText() != null);

            String shown = FxTestSupport.callOnFx(panel::placeholderText);
            assertTrue(shown.contains("HTTP 403: API rate limit exceeded"), shown);
            assertTrue(FxTestSupport.callOnFx(echo::getText).contains("HTTP 403"), "and in the status bar");
        } finally {
            Files.delete(bin.resolve("issue.fail"));
        }
    }

    /** A7-6 (b): a PR picker asked for while the panel loads issues must not strand the panel's spinner. */
    @Test
    @Order(4)
    void aPickerDoesNotSupersedeThePanelsOwnFetch() throws Exception {
        OverlayHost overlay = FxTestSupport.field(fx.controller, "overlayHost");
        Files.writeString(bin.resolve("delay"), "0.5");
        try {
            FxTestSupport.runOnFx(() -> {
                FxTestSupport.<ToggleButton>field(panel, "prsToggle").fire();
                FxTestSupport.<ToggleButton>field(panel, "issuesToggle").fire(); // gh takes half a second
                github.viewPrDiff(); // the palette's picker asks for the PR list meanwhile
            });
            await("the panel's issue list", () -> panel.placeholderText() != null);
            await("the picker", overlay::isShowing);
        } finally {
            Files.delete(bin.resolve("delay"));
            FxTestSupport.runOnFx(overlay::hide);
        }
    }

    /** A7-7 (3): github.refresh re-probes the repository without closing the open tool window. */
    @Test
    @Order(5)
    void refreshKeepsTheOpenToolWindowOpen() throws Exception {
        FxTestSupport.runOnFx(() -> toolWindows.open(githubWindow));
        assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(githubWindow)), "precondition");
        Label checks = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "githubChecks");

        FxTestSupport.runOnFx(github::refresh);
        await("the CI roll-up", checks::isVisible);

        assertTrue(FxTestSupport.callOnFx(() -> toolWindows.isOpen(githubWindow)), "still open after the refresh");
    }

    /** A7-7 (1): the roll-up describes one branch's pull request; another branch must not inherit it. */
    @Test
    @Order(6)
    void theChecksRollUpFollowsABranchSwitch() throws Exception {
        Label checks = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "githubChecks");
        assertTrue(FxTestSupport.callOnFx(checks::isVisible), "precondition: main's pull request has a failing check");
        Files.writeString(bin.resolve("checks.json"), "[]"); // the other branch has no pull request

        FxTestSupport.runOnFx(() -> git.checkoutBranch("other"));
        await("the branch switch", () -> "other".equals(git.branchName()));
        await("the stale roll-up to go", () -> !checks.isVisible());
    }

    /** C2-7: closing the window must not kill gh (and its git child) halfway through a branch switch. */
    @Test
    @Order(7)
    void aRunningPrCheckoutIsLeftToFinishOnShutdown() throws Exception {
        GitHubService service = new GitHubService();
        service.setCommand("sh " + bin.resolve("fakegh"));
        Path state = bin.resolve("checkout.state");

        service.prCheckout(repo, 7, result -> {});
        awaitOffFx("gh pr checkout to start", () -> Files.exists(state));
        service.shutdown();
        awaitOffFx(
                "gh pr checkout to finish",
                () -> "finished".equals(Files.readString(state).strip()));
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

    private static void awaitOffFx(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.call()) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }
}
