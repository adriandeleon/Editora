package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.github.GitHubListQuery;
import com.editora.github.GitHubService;
import com.editora.github.GitHubService.Activity;
import com.editora.github.GitHubService.AuthState;
import com.editora.github.GitHubService.Availability;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GitHubService} against an offline stand-in for {@code gh} (a shell script steered by marker files;
 * nothing touches the network): what the availability probe concludes and caches, what survives of an
 * oversized log or diff, and that a cancelled or superseded call really stops {@code gh}.
 */
@Tag("fx")
class GitHubServiceFxTest {

    private static final String OFFLINE = "{\"hosts\":{\"github.com\":[{\"state\":\"error\",\"error\":"
            + "\"Get \\\"https://api.github.com/\\\": dial tcp: lookup api.github.com: no such host\","
            + "\"active\":true,\"host\":\"github.com\",\"login\":\"octocat\"}]}}";
    private static final String SIGNED_IN = "{\"hosts\":{\"github.com\":[{\"state\":\"success\",\"active\":true,"
            + "\"host\":\"github.com\",\"login\":\"octocat\"}]}}";
    private static final String SIGNED_OUT = "{\"hosts\":{}}";

    private static final String FAKE_GH = """
            #!/bin/sh
            D="$(dirname "$0")"
            printf '%s\\n' "$*" >> "$D/gh.log"
            if [ "$1" = "--version" ]; then
              if [ -f "$D/version.slow" ]; then sleep 5; fi
              echo "gh version 2.96.0 (test stand-in)"; exit 0
            fi
            if [ "$1 $2" = "auth status" ]; then
              if [ "$3" = "--json" ]; then
                if [ -f "$D/auth.old" ]; then echo "unknown flag: --json" >&2; exit 1; fi
                cat "$D/auth.json"; exit 0
              fi
              if [ -f "$D/auth.plain.ok" ]; then exit 0; fi
              if [ -f "$D/auth.plain.nologin" ]; then echo "You are not logged into any GitHub hosts. Run gh auth login to authenticate." >&2; exit 1; fi
              echo "X Failed to log in to github.com account octocat (keyring)" >&2; exit 1
            fi
            if [ "$1" = "api" ]; then cat "$D/big.files"; exit 0; fi
            case "$1 $2" in
              "pr list")
                if [ -f "$D/pr.fail" ]; then echo "error connecting to api.github.com" >&2; exit 1; fi
                if [ -f "$D/pr.slow" ]; then echo $$ >> "$D/pids"; sleep 30; fi
                echo "[]"; exit 0;;
              "issue list") if [ -f "$D/issue.fail" ]; then echo "the repository has disabled issues" >&2; exit 1; fi; echo "[]"; exit 0;;
              "run list") echo "[]"; exit 0;;
              "run view")
                if [ -f "$D/log.slow" ]; then echo $$ >> "$D/pids"; sleep 30; fi
                cat "$D/big.log"; exit 0;;
              "pr diff") cat "$D/big.diff"; exit 0;;
              "pr view") echo '{"number":7,"title":"Fix","state":"OPEN","headRefName":"fix","baseRefName":"main"}'; exit 0;;
              "pr checks") echo '[{"name":"build","state":"PENDING","bucket":"pending","link":"","workflow":"CI"}]'; exit 8;;
              "repo view") echo '{"nameWithOwner":"o/r","defaultBranchRef":{"name":"main"},"url":"https://github.com/o/r"}'; exit 0;;
            esac
            exit 0
            """;

    private Path dir;
    private GitHubService service;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeFalse(GitTestRepo.windows(), "the gh stand-in is a POSIX shell script");
        FxTestSupport.bootToolkit();
        // A directory with a space in it: the stand-in is configured the way Settings' Browse… writes it (G2).
        dir = Files.createDirectories(
                Files.createTempDirectory("editora-gh-service").toRealPath().resolve("GitHub CLI"));
        Path gh = Files.writeString(dir.resolve("gh"), FAKE_GH);
        assertTrue(gh.toFile().setExecutable(true));
        Files.writeString(dir.resolve("auth.json"), SIGNED_IN);
        service = new GitHubService();
        assertTrue(service.setCommand(gh.toString()), "a new command");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (service != null) {
            service.shutdown();
        }
        if (dir != null) {
            try (var paths = Files.walk(dir.getParent())) {
                for (Path path :
                        paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** G2: an executable whose path has a space is found (it used to be split into "…/GitHub" + "CLI/gh"). */
    @Test
    void aGhPathWithASpaceIsFound() throws Exception {
        Availability a = detect();

        assertTrue(a.found());
        assertTrue(a.authenticated());
        assertEquals(List.of("github.com"), a.hosts());
        assertEquals("octocat", a.account("github.com"), "the account the tool window names");
        assertEquals("octocat", a.account(""), "the only account, while the repository's host is not known");
        assertEquals("", a.account("ghe.example.com"));
        assertEquals("gh version 2.96.0 (test stand-in)", a.version());
    }

    /** G1: offline, gh exits 1 from `auth status`; that must read as "could not check", and stay usable. */
    @Test
    void offlineIsUnverifiedNotSignedOut() throws Exception {
        Files.writeString(dir.resolve("auth.json"), OFFLINE);

        Availability a = detect();

        assertEquals(AuthState.UNVERIFIED, a.auth());
        assertTrue(a.ready(), "commands may run: gh reports its own network error if GitHub is still unreachable");
        assertFalse(a.authenticated());
        assertEquals(List.of("github.com"), a.hosts());
        assertTrue(a.detail().contains("no such host"), a.detail());
    }

    /** G1: a cached "signed out" is not final — asking again after `gh auth login` sees the login. */
    @Test
    void aCachedNegativeIsReprobed() throws Exception {
        Files.writeString(dir.resolve("auth.json"), SIGNED_OUT);
        assertEquals(AuthState.SIGNED_OUT, detect().auth());
        assertFalse(service.availability().ready());

        CompletableFuture<Availability> tooSoon = new CompletableFuture<>();
        assertFalse(
                service.redetectIfOlderThan(Duration.ofMinutes(5), tooSoon::complete),
                "bounded: no gh process for every command that meets the negative");

        Files.writeString(dir.resolve("auth.json"), SIGNED_IN);
        CompletableFuture<Availability> again = new CompletableFuture<>();
        assertTrue(service.redetectIfOlderThan(Duration.ZERO, again::complete));
        assertTrue(again.get(20, TimeUnit.SECONDS).ready());
        assertTrue(service.availability().ready());
        assertFalse(tooSoon.isDone());
    }

    /** A gh before 2.81 has no `auth status --json`: the exit code decides, and a failure is not "signed out". */
    @Test
    void anOlderGhFallsBackToTheExitCode() throws Exception {
        Files.writeString(dir.resolve("auth.old"), "1");
        Files.writeString(dir.resolve("auth.plain.ok"), "1");
        Availability ok = detect();
        assertEquals(AuthState.SIGNED_IN, ok.auth());
        assertEquals(List.of(), ok.hosts(), "hosts unknown: the remote heuristic decides");

        Files.delete(dir.resolve("auth.plain.ok"));
        assertEquals(AuthState.UNVERIFIED, detect().auth(), "exit 1 is also what offline looks like");

        Files.writeString(dir.resolve("auth.plain.nologin"), "1");
        assertEquals(AuthState.SIGNED_OUT, detect().auth());
    }

    /** G1: a gh that does not answer in time is not evidence that gh is missing, and is never cached. */
    @Test
    void anInconclusiveProbeIsNotAnAnswer() throws Exception {
        Files.writeString(dir.resolve("version.slow"), "1");

        assertNull(GitHubService.probe(List.of(dir.resolve("gh").toString()), Duration.ofMillis(300)));
        assertEquals(Availability.UNKNOWN, GitHubService.probe(List.of("/no/such/dir/gh"), Duration.ofSeconds(5)));
    }

    /** G7: re-applying the same command (every settings save does) keeps the cached answer. */
    @Test
    void reapplyingTheSameCommandKeepsTheCachedAvailability() throws Exception {
        Availability first = detect();

        assertFalse(service.setCommand(dir.resolve("gh").toString()), "unchanged");
        assertFalse(service.setCommand("  " + dir.resolve("gh") + "  "), "unchanged after trimming");
        assertEquals(first, service.availability(), "still answering from the cache");

        assertTrue(service.setCommand("sh '" + dir.resolve("gh") + "'"), "a different command");
        assertNull(service.availability(), "the old answer was about another program");
        assertTrue(detect().found());
    }

    /** Concurrent detects share one probe: startup asks from several places at once. */
    @Test
    void concurrentDetectsShareOneProbe() throws Exception {
        CompletableFuture<Availability> a = new CompletableFuture<>();
        CompletableFuture<Availability> b = new CompletableFuture<>();
        Files.writeString(dir.resolve("version.slow"), "1"); // holds the first probe so the second can join it
        service.detect(a::complete);
        service.detect(b::complete);
        Files.delete(dir.resolve("version.slow"));

        assertEquals(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        assertEquals(1, logged("--version"));
    }

    /** G3: a failed log over the 10 MB capture limit — its end (the failure) is what must be shown. */
    @Test
    void theEndOfAnOversizedFailedLogIsKept() throws Exception {
        String filler = "build\tCompile\t2026-01-01T00:00:00.0000000Z ordinary output " + "x".repeat(140) + "\n";
        StringBuilder log = new StringBuilder();
        while (log.length() < 12 * 1024 * 1024) {
            log.append(filler);
        }
        log.append("build\tTest\t##[error]THE REAL FAILURE\n");
        Files.writeString(dir.resolve("big.log"), log);

        CompletableFuture<GitHubService.RunLogResult> got = new CompletableFuture<>();
        service.runFailedLog(dir, 42L, got::complete);
        GitHubService.RunLogResult res = got.get(60, TimeUnit.SECONDS);

        assertTrue(res.ok(), res.error());
        assertTrue(res.truncated(), "earlier output was dropped, and says so");
        assertEquals(3000, res.lines().size());
        assertEquals(
                "build\tTest\t##[error]THE REAL FAILURE",
                res.lines().get(res.lines().size() - 1));
    }

    /** G3: a diff over the capture limit is reported as incomplete, and holds only whole files. */
    @Test
    void anOversizedDiffIsReportedAsTruncated() throws Exception {
        StringBuilder diff = new StringBuilder();
        for (int f = 0; f < 700; f++) {
            diff.append("diff --git a/f").append(f).append(".txt b/f").append(f).append(".txt\n");
            diff.append("new file mode 100644\n--- /dev/null\n+++ b/f")
                    .append(f)
                    .append(".txt\n@@ -0,0 +1,400 @@\n");
            for (int l = 0; l < 400; l++) {
                diff.append("+a line of forty-odd characters of content ")
                        .append(l)
                        .append('\n');
            }
        }
        assertTrue(diff.length() > 11 * 1024 * 1024, "the fixture must exceed the capture limit");
        Files.writeString(dir.resolve("big.diff"), diff);

        CompletableFuture<GitHubService.DiffResult> got = new CompletableFuture<>();
        service.prDiff(dir, 7, got::complete);
        GitHubService.DiffResult res = got.get(60, TimeUnit.SECONDS);

        assertTrue(res.ok(), res.error());
        assertTrue(res.truncated(), "the review must be able to say the file list is incomplete");
        assertTrue(
                res.files().size() > 100 && res.files().size() < 700,
                "files: " + res.files().size());
        assertEquals(
                400,
                res.files().get(res.files().size() - 1).newLines().size(),
                "the file the capture stopped in is left out rather than shown cut short");
    }

    /** G1: gh failing is "could not find out", never "this repository has nothing". */
    @Test
    void aFailedActivityProbeIsUnknownNotNone() throws Exception {
        assertEquals(Activity.NONE, activity());

        Files.writeString(dir.resolve("pr.fail"), "1");
        assertEquals(Activity.UNKNOWN, activity());
        CompletableFuture<Boolean> yesNo = new CompletableFuture<>();
        service.hasOpenActivity(dir, yesNo::complete);
        assertFalse(yesNo.get(20, TimeUnit.SECONDS));

        Files.delete(dir.resolve("pr.fail"));
        Files.writeString(dir.resolve("issue.fail"), "1"); // issues switched off in this repository
        assertEquals(Activity.NONE, activity(), "a feature the repository has disabled is not an outage");
    }

    /** G16: Stop kills gh instead of abandoning it, the consumer is not called, and the lane is free again. */
    @Test
    void cancellingARunningCallKillsGh() throws Exception {
        Files.writeString(dir.resolve("log.slow"), "1");
        Files.writeString(dir.resolve("big.log"), "late\n");
        AtomicBoolean delivered = new AtomicBoolean();
        AtomicInteger maxInFlight = new AtomicInteger();
        FxTestSupport.runOnFx(() -> service.activeCallsProperty()
                .addListener((o, was, now) -> maxInFlight.accumulateAndGet(now.intValue(), Math::max)));

        ProcessRunner.Cancellation call = service.runFailedLog(dir, 42L, res -> delivered.set(true));
        long pid = awaitPid(1);
        assertTrue(FxTestSupport.callOnFx(() -> service.activeCallsProperty().get() == 1), "busy while gh runs");

        FxTestSupport.runOnFx(call::cancel);

        await(
                "gh to be killed",
                () -> ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true));
        await(
                "the busy count to return to zero",
                () -> FxTestSupport.callOnFx(() -> service.activeCallsProperty().get() == 0));
        FxTestSupport.drainFx();
        assertFalse(delivered.get(), "a cancelled call's consumer is never called");
        assertEquals(1, maxInFlight.get());

        Files.delete(dir.resolve("log.slow"));
        CompletableFuture<GitHubService.RunLogResult> next = new CompletableFuture<>();
        service.runFailedLog(dir, 43L, next::complete);
        assertEquals(List.of("late"), next.get(20, TimeUnit.SECONDS).lines(), "the read lane was not left blocked");
    }

    /** G16: a newer request for the same list kills the older one's gh; a picker's one-shot is left alone. */
    @Test
    void aNewerListRequestKillsTheOlderOne() throws Exception {
        Files.writeString(dir.resolve("pr.slow"), "1");
        AtomicBoolean firstDelivered = new AtomicBoolean();
        service.listPrs(dir, GitHubListQuery.open(50), res -> firstDelivered.set(true));
        long pid = awaitPid(1);

        Files.delete(dir.resolve("pr.slow"));
        CompletableFuture<GitHubService.PrListResult> second = new CompletableFuture<>();
        service.listPrs(dir, GitHubListQuery.open(50), second::complete);

        assertTrue(second.get(20, TimeUnit.SECONDS).ok(), "the newer request did not wait 30 s behind the older");
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false), "the superseded gh was killed");
        FxTestSupport.drainFx();
        assertFalse(firstDelivered.get());
    }

    /** G16: a probe never waits behind a slow user call (it has its own lane). */
    @Test
    void probesDoNotQueueBehindASlowRead() throws Exception {
        Files.writeString(dir.resolve("pr.slow"), "1");
        ProcessRunner.Cancellation slow = service.listPrs(dir, GitHubListQuery.open(50), res -> {});
        awaitPid(1);

        assertTrue(detect().found(), "answered while the read lane is busy");

        slow.cancel();
    }

    /** G3: the files-API fallback obeys the same rule — an answer over the capture limit says it is cut. */
    @Test
    void anOversizedFilesAnswerIsReportedAsTruncated() throws Exception {
        StringBuilder json = new StringBuilder("[");
        String patch = "@@ -0,0 +1 @@\\n+" + "x".repeat(20_000);
        for (int f = 0; f < 600; f++) {
            json.append(f == 0 ? "" : ",")
                    .append("{\"filename\":\"f")
                    .append(f)
                    .append(".txt\",\"status\":\"added\",\"additions\":1,\"deletions\":0,\"patch\":\"")
                    .append(patch)
                    .append("\"}");
        }
        json.append("]");
        assertTrue(json.length() > 11 * 1024 * 1024, "the fixture must exceed the capture limit");
        Files.writeString(dir.resolve("big.files"), json);

        CompletableFuture<GitHubService.PrFilesResult> got = new CompletableFuture<>();
        service.prFiles(dir, 7, got::complete);
        GitHubService.PrFilesResult res = got.get(60, TimeUnit.SECONDS);

        assertTrue(res.ok(), res.error());
        assertTrue(res.truncated(), "the review must be able to say the file list is incomplete");
    }

    /**
     * G16: the status bar's checks poll and the repository lookup are background calls — they answer while
     * the read lane is busy, do not hold up a list, and do not spin the busy indicator.
     */
    @Test
    void lookupsNeitherWaitBehindAReadNorCountAsBusy() throws Exception {
        AtomicInteger maxInFlight = new AtomicInteger();
        FxTestSupport.runOnFx(() -> service.activeCallsProperty()
                .addListener((o, was, now) -> maxInFlight.accumulateAndGet(now.intValue(), Math::max)));

        CompletableFuture<GitHubService.BranchChecks> idle = new CompletableFuture<>();
        service.branchChecks(dir, idle::complete);
        assertTrue(idle.get(20, TimeUnit.SECONDS).ok());
        FxTestSupport.drainFx();
        assertEquals(0, maxInFlight.get(), "a background poll does not spin the indicator");
        assertEquals(0, logged("pr list"));

        Files.writeString(dir.resolve("pr.slow"), "1");
        ProcessRunner.Cancellation slow = service.listPrs(dir, GitHubListQuery.open(50), res -> {});
        awaitPid(1);
        CompletableFuture<GitHubService.BranchChecks> checks = new CompletableFuture<>();
        CompletableFuture<Object> repo = new CompletableFuture<>();
        CompletableFuture<GitHubService.CreateContext> context = new CompletableFuture<>();
        service.branchChecks(dir, checks::complete);
        service.repoInfo(dir, repo::complete);
        service.prCreateContext(dir, dir, context::complete);

        GitHubService.BranchChecks answered = checks.get(20, TimeUnit.SECONDS);
        assertEquals(7, answered.pr().number());
        assertEquals(1, answered.runs().size(), "parsed although gh exits 8 while a check is pending");
        assertNotNull(repo.get(20, TimeUnit.SECONDS), "the repository name did not wait for the list");
        assertEquals(7, context.get(20, TimeUnit.SECONDS).existing().number(), "nor did the create-PR lookups");

        slow.cancel();
    }

    private Availability detect() throws Exception {
        CompletableFuture<Availability> got = new CompletableFuture<>();
        service.detect(got::complete);
        Availability a = got.get(20, TimeUnit.SECONDS);
        assertNotNull(a);
        return a;
    }

    private Activity activity() throws Exception {
        CompletableFuture<Activity> got = new CompletableFuture<>();
        service.openActivity(dir, got::complete);
        return got.get(20, TimeUnit.SECONDS);
    }

    private long awaitPid(int count) throws Exception {
        Path pids = dir.resolve("pids");
        await(
                "gh to start",
                () -> Files.exists(pids) && Files.readAllLines(pids).size() >= count);
        return Long.parseLong(Files.readAllLines(pids).get(count - 1).strip());
    }

    private long logged(String needle) throws Exception {
        try (var lines = Files.lines(dir.resolve("gh.log"))) {
            return lines.filter(line -> line.contains(needle)).count();
        }
    }

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!condition.call()) {
            assertFalse(System.nanoTime() > deadline, "timed out waiting for " + what);
            Thread.sleep(25);
        }
    }
}
