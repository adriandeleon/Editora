package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;

import com.editora.config.Settings;
import com.editora.github.GitHubListQuery;
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

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * When the GitHub surfaces consider {@code gh} usable, against an offline stand-in (a shell script steered
 * by marker files): a probe that could not find out is not a "no", a cached negative is asked again, an
 * unrelated settings save does not reset anything, any remote on a host gh is signed in to counts, Git
 * support being off is said in so many words, and the CI log's Stop stops {@code gh}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitHubReadinessFxTest {

    private static final String SIGNED_IN = "{\"hosts\":{\"git.corp.example\":[{\"state\":\"success\","
            + "\"active\":true,\"host\":\"git.corp.example\",\"login\":\"octocat\"}]}}";
    private static final String OFFLINE = "{\"hosts\":{\"git.corp.example\":[{\"state\":\"error\",\"error\":"
            + "\"dial tcp: lookup git.corp.example: no such host\",\"active\":true}]}}";
    private static final String SIGNED_OUT = "{\"hosts\":{}}";

    private static final String FAKE_GH = """
            #!/bin/sh
            D="$(dirname "$0")"
            printf '%s\\n' "$*" >> "$D/gh.log"
            if [ "$1" = "--version" ]; then
              if [ -f "$D/version.old" ]; then echo "gh version 2.40.1 (2023-12-13)"; exit 0; fi
              echo "gh version 2.96.0 (test stand-in)"; exit 0
            fi
            if [ "$1 $2" = "auth status" ]; then
              if [ "$3" = "--json" ]; then cat "$D/auth.json"; exit 0; fi
              exit 1
            fi
            case "$1 $2" in
              "pr list")
                if [ -f "$D/pr.fail" ]; then echo "error connecting to git.corp.example" >&2; exit 1; fi
                cat "$D/prlist.json"; exit 0;;
              "issue list") echo "[]"; exit 0;;
              "run list") echo "[]"; exit 0;;
              "run view") echo $$ >> "$D/pids"; sleep 30; echo late; exit 0;;
            esac
            exit 0
            """;

    private Path base;
    private Path bin;
    private Path repo;
    private FxWindowFixture fx;
    private Settings settings;
    private GitCoordinator git;
    private GitHubCoordinator github;
    private ToolWindowManager toolWindows;
    private ToolWindow githubWindow;
    private Label echo;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeFalse(GitTestRepo.windows(), "the gh stand-in is a POSIX shell script");
        FxTestSupport.bootToolkit();
        base = Files.createTempDirectory("editora-gh-ready").toRealPath();
        bin = Files.createDirectories(base.resolve("bin"));
        Files.writeString(bin.resolve("fakegh"), FAKE_GH);
        Files.writeString(bin.resolve("auth.json"), SIGNED_IN);
        Files.writeString(bin.resolve("pr.fail"), "1"); // GitHub is unreachable when the window opens
        Files.writeString(
                bin.resolve("prlist.json"),
                "[{\"number\":7,\"title\":\"Fix thing\",\"author\":{\"login\":\"alice\"},\"headRefName\":\"fix\","
                        + "\"baseRefName\":\"main\",\"state\":\"OPEN\",\"isDraft\":false,"
                        + "\"updatedAt\":\"2026-01-01T00:00:00Z\",\"url\":\"https://git.corp.example/o/r/pull/7\"}]");

        GitTestRepo testRepo = GitTestRepo.init(base);
        repo = testRepo.root;
        testRepo.write("a.txt", "a1\n");
        testRepo.commitAll("first");
        // G14: origin is not on GitHub at all; the GitHub remote is "upstream", in the scp form without a
        // user, on an Enterprise host whose name says nothing about GitHub — gh's own hosts decide.
        testRepo.git("remote", "add", "origin", "https://gitlab.com/me/fork.git");
        testRepo.git("remote", "add", "upstream", "git.corp.example:o/r.git");

        String command = "sh " + bin.resolve("fakegh");
        AtomicReference<Settings> shared = new AtomicReference<>();
        fx = FxWindowFixture.create(Files.createDirectories(base.resolve("cfg")), config -> {
            config.getSettings().setGitSupport(true);
            config.getSettings().setGithubSupport(true);
            config.getSettings().setGhPath(command);
            shared.set(config.getSettings());
        });
        settings = shared.get();
        git = FxTestSupport.field(fx.controller, "git");
        github = FxTestSupport.field(fx.controller, "github");
        toolWindows = FxTestSupport.field(fx.controller, "toolWindows");
        githubWindow = FxTestSupport.field(fx.controller, "githubToolWindow");
        echo = FxTestSupport.field(FxTestSupport.field(fx.controller, "statusBar"), "echo");
        FxTestSupport.runOnFx(() -> {
            github.activityRetryAfter = Duration.ofMillis(200);
            github.negativeReprobeAfter = Duration.ZERO;
            fx.controller.openAndNavigate(repo.resolve("a.txt"), 0);
        });
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

    /**
     * G1 + G14: the first activity probe fails (unreachable) — that must not be cached as "nothing to show";
     * once GitHub answers, the tool window appears by itself, for a repository whose GitHub remote is not
     * origin.
     */
    @Test
    @Order(1)
    void aFailedActivityProbeIsRetriedAndTheWindowAppears() throws Exception {
        Set<ToolWindow> unavailable = FxTestSupport.field(toolWindows, "unavailable");
        awaitOffFx("the first, failing, activity probe", () -> logged("pr list --limit 1") >= 1);
        assertTrue(
                FxTestSupport.callOnFx(() -> unavailable.contains(githubWindow)),
                "nothing known yet: the window stays hidden");

        Files.delete(bin.resolve("pr.fail"));

        await("the GitHub tool window to become available", () -> !unavailable.contains(githubWindow));
    }

    /** G7: a settings save with the same gh command keeps the cached answer and starts no gh at all. */
    @Test
    @Order(2)
    void reapplyingSettingsKeepsTheCachedAvailability() throws Exception {
        GitHubService.Availability before =
                FxTestSupport.callOnFx(() -> github.service().availability());
        long probes = logged("--version");
        Set<ToolWindow> unavailable = FxTestSupport.field(toolWindows, "unavailable");

        FxTestSupport.runOnFx(() -> {
            github.applySupport();
            assertSame(before, github.service().availability(), "not reset to checking");
            github.refreshAvailability(); // what a tab switch does
            assertFalse(unavailable.contains(githubWindow), "the tool window is not withdrawn meanwhile");
        });
        FxTestSupport.drainFx();
        Thread.sleep(200);

        assertEquals(probes, logged("--version"), "no gh process per settings save");
    }

    /** G1: being offline is said as such, and does not disable the commands. */
    @Test
    @Order(3)
    void offlineIsReportedAsUnverifiedAndCommandsStillRun() throws Exception {
        Files.writeString(bin.resolve("auth.json"), OFFLINE);
        try {
            FxTestSupport.runOnFx(github::refresh);
            await("the offline probe", () -> github.service().availability().unverified());
            await("the status", () -> tr("status.github.unverified").equals(echo.getText()));

            AtomicReference<Object> answer = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> github.fetchPrs(GitHubListQuery.open(50), answer::set, answer::set));
            await("the list fetch", () -> answer.get() != null);
            assertTrue(
                    answer.get() instanceof GitHubListQuery.Page<?> page
                            && page.items().size() == 1,
                    "ran gh: " + answer.get());
        } finally {
            Files.writeString(bin.resolve("auth.json"), SIGNED_IN);
        }
    }

    /** G1: a cached "not signed in" is asked again by the command that meets it — no github.refresh needed. */
    @Test
    @Order(4)
    void aCommandReprobesACachedNegative() throws Exception {
        Files.writeString(bin.resolve("auth.json"), SIGNED_OUT);
        FxTestSupport.runOnFx(github::refresh);
        await("the signed-out probe", () -> !github.service().availability().ready());
        await("the reason", () -> tr("status.github.notAuthenticated").equals(echo.getText()));

        Files.writeString(bin.resolve("auth.json"), SIGNED_IN); // the user ran `gh auth login` in a terminal
        AtomicReference<String> refused = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> github.fetchPrs(GitHubListQuery.open(50), prs -> {}, refused::set));
        assertEquals(tr("status.github.notAuthenticated"), refused.get(), "answered from the cache this once");

        await(
                "the re-probe to find the login",
                () -> github.service().availability().ready());
        await("the status to say so", () -> tr("status.github.ready").equals(echo.getText()));
    }

    /** G18: with Git support off, every GitHub command says so and names the command that turns it on. */
    @Test
    @Order(5)
    void gitSupportOffIsSaidInSoManyWords() throws Exception {
        String expected = tr("status.github.gitDisabled", tr("command.view.toggleGit"));
        FxTestSupport.runOnFx(() -> settings.setGitSupport(false));
        try {
            FxTestSupport.runOnFx(github::openOnGitHub);
            assertEquals(expected, FxTestSupport.callOnFx(echo::getText));
            FxTestSupport.runOnFx(() -> echo.setText(""));
            FxTestSupport.runOnFx(github::checkoutPr);
            assertEquals(expected, FxTestSupport.callOnFx(echo::getText));
            FxTestSupport.runOnFx(() -> echo.setText(""));
            FxTestSupport.runOnFx(github::refresh);
            assertEquals(expected, FxTestSupport.callOnFx(echo::getText));
            AtomicReference<String> refused = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> github.fetchPrs(GitHubListQuery.open(50), prs -> {}, refused::set));
            assertEquals(expected, refused.get());
        } finally {
            FxTestSupport.runOnFx(() -> settings.setGitSupport(true));
        }
    }

    /** G16: the CI log's Stop kills gh (it used to leave it running and only drop the result). */
    @Test
    @Order(6)
    void stoppingTheCiLogKillsGh() throws Exception {
        BuildOutputPanel output = FxTestSupport.field(fx.controller, "buildOutputPanel");
        GitHubPanel panel = FxTestSupport.field(fx.controller, "githubPanel");
        Node spinner = FxTestSupport.field(panel, "busy");
        FxTestSupport.runOnFx(() -> toolWindows.open(githubWindow)); // its first fetch binds the spinner
        await(
                "the panel's own fetches to end",
                () -> github.callsInFlightProperty().get() == 0);
        await("the spinner to rest", () -> !spinner.isVisible());
        FxTestSupport.runOnFx(() -> github.viewRunLog(42L, "CI"));
        long first = awaitPid(1);
        await("the tool window's spinner to show that gh is running", spinner::isVisible);
        assertTrue(FxTestSupport.callOnFx(() -> github.callsInFlightProperty().get() > 0), "the busy signal is up");

        // Asking for another log supersedes the first: its gh is killed, not left to finish.
        FxTestSupport.runOnFx(() -> github.viewRunLog(43L, "CI"));
        awaitOffFx("the superseded gh to be killed", () -> !alive(first));
        long second = awaitPid(2);

        Button stop = FxTestSupport.callOnFx(() -> {
            for (Node node : output.lookupAll(".run-stop")) {
                if (node instanceof Button b && !b.isDisabled()) {
                    return b;
                }
            }
            return null;
        });
        assertNotNull(stop, "the CI tab's Stop button");
        FxTestSupport.runOnFx(stop::fire);

        awaitOffFx("gh to be killed by Stop", () -> !alive(second));
        await("the busy signal to clear", () -> github.callsInFlightProperty().get() == 0);
        await("the spinner to stop", () -> !spinner.isVisible());
    }

    /** G19: a gh before 2.50 has no `pr checks --json` — it is not asked, and the command says what it needs. */
    @Test
    @Order(7)
    void anOldGhIsNotAskedForChecksAndTheCommandSaysWhy() throws Exception {
        Files.writeString(bin.resolve("version.old"), "1");
        try {
            FxTestSupport.runOnFx(github::refresh);
            await(
                    "the old gh to be probed",
                    () -> !github.service().availability().supportsChecks());
            FxTestSupport.drainFx();
            Thread.sleep(300); // anything the refresh itself started has reached gh by now
            long viewsBefore = logged("pr view");
            long checksBefore = logged("pr checks");

            FxTestSupport.runOnFx(github::showChecks);

            await(
                    "the reason",
                    () -> tr("status.github.checksCannotUseGh", "2.40.1", "2.50")
                            .equals(echo.getText()));
            Thread.sleep(300);
            assertEquals(viewsBefore, logged("pr view"), "no lookup of the branch's pull request");
            assertEquals(checksBefore, logged("pr checks"), "and no gh pr checks --json it would reject");
        } finally {
            Files.delete(bin.resolve("version.old"));
            FxTestSupport.runOnFx(github::refresh);
            await("the current gh again", () -> github.service().availability().supportsChecks());
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private long awaitPid(int count) throws Exception {
        Path pids = bin.resolve("pids");
        awaitOffFx(
                "gh to start",
                () -> Files.exists(pids) && Files.readAllLines(pids).size() >= count);
        return Long.parseLong(Files.readAllLines(pids).get(count - 1).strip());
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
