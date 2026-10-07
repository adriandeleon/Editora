package com.editora.github;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.diff.PatchParser;
import com.editora.process.CommandLog;
import com.editora.process.ProcessRunner;

/**
 * The native-{@code gh} facade — a structural clone of {@code GitService}. Every GitHub command shells out via
 * {@link ProcessRunner} on a single daemon executor thread and posts results back on the JavaFX thread, so the
 * UI thread is never blocked. Availability ({@code gh} on PATH + {@code gh auth status} succeeding) is probed
 * once and cached; list refreshes carry a generation guard so a stale background result is dropped.
 *
 * <p>Every editor read (owner/repo resolution) is delegated to {@code gh} itself: commands run with a
 * working directory inside the repo, so {@code gh} resolves the host + owner/repo from the git remote — no
 * host config lives in Editora. Security: {@code gh} holds the user's GitHub credentials; Editora never sees,
 * stores, or transmits a token — it only shells out, exactly like {@code GitService} does for {@code git}.
 */
public final class GitHubService {

    private static final Duration QUICK = Duration.ofSeconds(10);
    private static final Duration NETWORK = Duration.ofSeconds(120);
    /**
     * Ceiling for {@code gh pr checkout}: a fetch plus a branch switch. It rewrites the working tree, so —
     * like {@code GitService}'s mutations — killing it after two minutes on a large repository or a slow link
     * leaves a half-switched tree; the limit only exists so a hung child cannot hold the lane forever.
     */
    static final Duration CHECKOUT = Duration.ofMinutes(30);

    /**
     * The child environment for every {@code gh} call: never prompt, never page, never colorize, never phone
     * home for update notifications — so output parses cleanly and nothing can block waiting for a TTY.
     * ({@link ProcessRunner} closes the child's stdin, so a would-be prompt EOFs immediately.)
     */
    private static final Map<String, String> GH_ENV = Map.of(
            "GH_PROMPT_DISABLED", "1",
            "GH_NO_UPDATE_NOTIFIER", "1",
            "GH_PAGER", "cat",
            "CLICOLOR", "0",
            "NO_COLOR", "1");

    private final ThreadPoolExecutor exec =
            new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
                Thread t = new Thread(r, "github-service");
                t.setDaemon(true);
                return t;
            });

    /** Working-tree mutations ({@code gh pr checkout}) currently running; {@link #shutdown()} lets them finish. */
    private final AtomicInteger runningMutations = new AtomicInteger();

    /** The configured {@code gh} command tokens (default {@code ["gh"]}); a blank override resets to gh. */
    private volatile List<String> command = List.of("gh");

    /** null = not yet probed; cached after the first {@link #detect}. */
    private volatile Availability availability;

    /**
     * Where completed {@code gh} commands are reported (the Output "GitHub" tab). Volatile: installed
     * from the FX thread, read on {@link #exec}.
     */
    private volatile CommandLog commandLog = CommandLog.none();

    /**
     * One generation per list kind: a newer request for the <em>same</em> list supersedes an older one. They
     * were one shared counter, so asking for issues dropped a pull-request answer still in flight (and the
     * other way round) and its consumer waited forever.
     */
    private final AtomicLong prListGen = new AtomicLong();

    private final AtomicLong issueListGen = new AtomicLong();
    private final AtomicLong runListGen = new AtomicLong();

    /** Whether {@code gh} is on PATH, whether it reports an authenticated host, and its version line. */
    public record Availability(boolean found, boolean authenticated, String version) {
        public static final Availability UNKNOWN = new Availability(false, false, "");

        /** Both present and authenticated — GitHub commands can actually run. */
        public boolean ready() {
            return found && authenticated;
        }
    }

    /** Sets the {@code gh} command/path (whitespace-tokenized); blank ⇒ resolve {@code gh} on PATH. */
    public void setCommand(String configured) {
        if (configured == null || configured.isBlank()) {
            command = List.of("gh");
        } else {
            List<String> tokens = new ArrayList<>();
            for (String t : configured.strip().split("\\s+")) {
                if (!t.isBlank()) {
                    tokens.add(t);
                }
            }
            command = tokens.isEmpty() ? List.of("gh") : List.copyOf(tokens);
        }
        availability = null; // re-probe with the new command
    }

    /** The last probed availability (may be {@code null} before the first {@link #detect}). */
    public Availability availability() {
        return availability;
    }

    // --- detection -------------------------------------------------------------------------------

    /**
     * Probes {@code gh --version} + {@code gh auth status} off the FX thread and posts an {@link Availability}
     * on the FX thread (also caching it). {@code gh auth status} is gated on the exit code only — its human
     * text has moved between stdout/stderr across gh versions, so it must never be parsed.
     */
    public void detect(Consumer<Availability> onResult) {
        submit(() -> {
            boolean found = false;
            boolean auth = false;
            String version = "";
            try {
                ProcessRunner.Result v = ghSilent(null, QUICK, "--version");
                found = v.ok();
                if (found) {
                    version = firstLine(v.out());
                }
            } catch (RuntimeException ignored) {
                // gh not launchable → not found
            }
            if (found) {
                try {
                    auth = ghSilent(null, QUICK, "auth", "status").ok();
                } catch (RuntimeException ignored) {
                    // treat a failed probe as unauthenticated
                }
            }
            Availability a = new Availability(found, auth, version);
            availability = a;
            Platform.runLater(() -> onResult.accept(a));
        });
    }

    // --- pull requests ---------------------------------------------------------------------------

    /** Result of a PR list: {@code ok} distinguishes a failed {@code gh} call from a genuinely empty list. */
    public record PrListResult(boolean ok, List<PrListParser.PullRequest> prs, String error) {}

    /** Lists open PRs ({@code gh pr list --json …}); generation-guarded so a stale refresh is dropped. */
    public void prList(Path dir, Consumer<PrListResult> onResult) {
        prList(dir, prListGen, onResult);
    }

    /**
     * {@link #prList} for a one-shot consumer (a picker) that must get its answer whatever the tool window
     * asks for meanwhile — and must not take the tool window's answer away either.
     */
    public void prListOnce(Path dir, Consumer<PrListResult> onResult) {
        prList(dir, null, onResult);
    }

    private void prList(Path dir, AtomicLong generation, Consumer<PrListResult> onResult) {
        long gen = generation == null ? 0 : generation.incrementAndGet();
        submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "pr",
                    "list",
                    "--limit",
                    "50",
                    "--json",
                    "number,title,author,headRefName,baseRefName,state,isDraft,updatedAt,url");
            PrListResult res = r.ok()
                    ? new PrListResult(true, PrListParser.parse(r.out()), "")
                    : new PrListResult(false, List.of(), r.message());
            if (generation == null || gen == generation.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    /**
     * {@link #prList(Path, Consumer)} for a {@link GitHubListQuery} (state, "mine", limit). The answer holds up
     * to {@link GitHubListQuery#fetchLimit()} rows — one more than is shown — for {@link GitHubListQuery#page}.
     */
    public void listPrs(Path dir, GitHubListQuery query, Consumer<PrListResult> onResult) {
        listPrs(dir, query, prListGen, onResult);
    }

    /** {@link #listPrs(Path, GitHubListQuery, Consumer)} for a one-shot consumer; see {@link #prListOnce(Path, Consumer)}. */
    public void listPrsOnce(Path dir, GitHubListQuery query, Consumer<PrListResult> onResult) {
        listPrs(dir, query, null, onResult);
    }

    private void listPrs(Path dir, GitHubListQuery query, AtomicLong generation, Consumer<PrListResult> onResult) {
        long gen = generation == null ? 0 : generation.incrementAndGet();
        submit(() -> {
            ProcessRunner.Result r = gh(dir, NETWORK, query.prArgs().toArray(new String[0]));
            PrListResult res = r.ok()
                    ? new PrListResult(true, PrListParser.parse(r.out()), "")
                    : new PrListResult(false, List.of(), r.message());
            if (generation == null || gen == generation.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    /** A PR's detail ({@code gh pr view <n> --json …}); posts {@code null} on failure. */
    public void prView(Path dir, int number, Consumer<PrViewParser.PrDetail> onResult) {
        submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "pr",
                    "view",
                    String.valueOf(number),
                    "--json",
                    "number,title,body,author,baseRefName,headRefName,state,url,additions,deletions");
            PrViewParser.PrDetail d = r.ok() ? PrViewParser.parse(r.out()) : null;
            Platform.runLater(() -> onResult.accept(d));
        });
    }

    /** Result of a PR diff: the parsed per-file patches, or an error message. */
    public record DiffResult(boolean ok, List<PatchParser.FilePatch> files, String error) {}

    /** Fetches a PR's whole unified diff ({@code gh pr diff <n>}) and parses it off-thread into file patches. */
    public void prDiff(Path dir, int number, Consumer<DiffResult> onResult) {
        submit(() -> {
            ProcessRunner.Result r = gh(dir, NETWORK, "pr", "diff", String.valueOf(number));
            DiffResult res = r.ok()
                    ? new DiffResult(true, PatchParser.parseAllSections(r.out()), "")
                    : new DiffResult(false, List.of(), r.message());
            Platform.runLater(() -> onResult.accept(res));
        });
    }

    /** Result of {@link #prFiles}: the per-file patches, how many files came without one, or an error. */
    public record PrFilesResult(boolean ok, PrFilesParser.Result files, String error) {}

    /**
     * A PR's files from the REST API ({@code gh api repos/{owner}/{repo}/pulls/<n>/files --paginate}) — what
     * {@link #prDiff} falls back to when GitHub refuses the whole diff for its size (HTTP 406). {@code gh}
     * fills {@code {owner}/{repo}} and the host in from the working directory's repository, as it does for
     * {@code gh pr diff}, so a fork or a GitHub Enterprise host needs nothing extra and no ref is fetched
     * into the user's repository.
     */
    public void prFiles(Path dir, int number, Consumer<PrFilesResult> onResult) {
        submit(() -> {
            ProcessRunner.Result r = gh(
                    dir, NETWORK, "api", "repos/{owner}/{repo}/pulls/" + number + "/files?per_page=100", "--paginate");
            PrFilesResult res = r.ok()
                    ? new PrFilesResult(true, PrFilesParser.parse(r.out()), "")
                    : new PrFilesResult(false, new PrFilesParser.Result(List.of(), 0), r.message());
            Platform.runLater(() -> onResult.accept(res));
        });
    }

    /** Checks out a PR branch ({@code gh pr checkout <n>}); posts the raw result for status/error reporting. */
    public void prCheckout(Path dir, int number, Consumer<ProcessRunner.Result> onResult) {
        submit(() -> {
            runningMutations.incrementAndGet();
            ProcessRunner.Result r;
            try {
                r = gh(dir, CHECKOUT, "pr", "checkout", String.valueOf(number));
            } finally {
                runningMutations.decrementAndGet();
            }
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Creates a PR ({@code gh pr create …}); posts the raw result (the created PR URL is on stdout). */
    public void prCreate(Path dir, List<String> ghArgs, Consumer<ProcessRunner.Result> onResult) {
        run(dir, NETWORK, onResult, ghArgs.toArray(new String[0]));
    }

    /** Submits a top-level PR review ({@code gh pr review …}); posts the raw result (argv from {@link PrReviewArgs}). */
    public void prReview(Path dir, List<String> ghArgs, Consumer<ProcessRunner.Result> onResult) {
        run(dir, NETWORK, onResult, ghArgs.toArray(new String[0]));
    }

    /** Result of a PR checks query: the runs, or an error. Parsed regardless of exit code (see {@link ChecksParser}). */
    public record ChecksResult(boolean ok, List<ChecksParser.CheckRun> runs, String error) {}

    /**
     * A PR's CI checks ({@code gh pr checks --json …}); the JSON is parsed even on a non-zero exit. A
     * {@code number <= 0} omits the PR argument, so {@code gh} resolves the checks for the current branch's PR.
     */
    public void prChecks(Path dir, int number, Consumer<ChecksResult> onResult) {
        submit(() -> {
            List<String> args = new ArrayList<>(List.of("pr", "checks"));
            if (number > 0) {
                args.add(String.valueOf(number));
            }
            args.add("--json");
            args.add("name,state,bucket,link,workflow");
            ProcessRunner.Result r = gh(dir, NETWORK, args.toArray(new String[0]));
            // gh pr checks exits 1 (failing) / 8 (pending) with the JSON still on stdout — parse it regardless.
            List<ChecksParser.CheckRun> runs = ChecksParser.parse(r.out());
            ChecksResult res = !runs.isEmpty() || r.ok()
                    ? new ChecksResult(true, runs, "")
                    : new ChecksResult(false, List.of(), r.message());
            Platform.runLater(() -> onResult.accept(res));
        });
    }

    /**
     * The current branch's pull request and its checks.
     *
     * @param ok whether the branch has a pull request ({@code false}: none, or {@code gh} failed)
     * @param pr the pull request ({@code null} when {@code !ok})
     * @param runs its check runs, with names and links; empty when it has none
     */
    public record BranchChecks(boolean ok, PrViewParser.PrDetail pr, List<ChecksParser.CheckRun> runs) {
        public static final BranchChecks NONE = new BranchChecks(false, null, List.of());
    }

    /**
     * Which pull request the checked-out branch belongs to ({@code gh pr view}, no number) and that pull
     * request's checks ({@code gh pr checks <n>}) — {@link #prChecks} alone cannot say which pull request it
     * answered for. A branch without a pull request costs the one {@code gh pr view} call.
     */
    public void branchChecks(Path dir, Consumer<BranchChecks> onResult) {
        submit(() -> {
            BranchChecks res = BranchChecks.NONE;
            PrViewParser.PrDetail pr = currentBranchPr(dir);
            if (pr != null && pr.number() > 0) {
                ProcessRunner.Result r = gh(
                        dir,
                        NETWORK,
                        "pr",
                        "checks",
                        String.valueOf(pr.number()),
                        "--json",
                        "name,state,bucket,link,workflow");
                res = new BranchChecks(true, pr, ChecksParser.parse(r.out())); // exits 1 / 8 with the JSON
            }
            BranchChecks posted = res;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** The pull request of the checked-out branch, or {@code null} when it has none. Runs on the lane. */
    private PrViewParser.PrDetail currentBranchPr(Path dir) {
        ProcessRunner.Result r = gh(
                dir,
                NETWORK,
                "pr",
                "view",
                "--json",
                "number,title,body,author,baseRefName,headRefName,state,url,additions,deletions");
        return r.ok() ? PrViewParser.parse(r.out()) : null;
    }

    // --- issues ----------------------------------------------------------------------------------

    /** Result of an issue list. */
    public record IssueListResult(boolean ok, List<IssueListParser.Issue> issues, String error) {}

    /** Lists open issues ({@code gh issue list --json …}); generation-guarded. */
    public void issueList(Path dir, Consumer<IssueListResult> onResult) {
        long gen = issueListGen.incrementAndGet();
        submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "issue",
                    "list",
                    "--limit",
                    "50",
                    "--json",
                    "number,title,author,state,labels,updatedAt,url");
            IssueListResult res = r.ok()
                    ? new IssueListResult(true, IssueListParser.parse(r.out()), "")
                    : new IssueListResult(false, List.of(), r.message());
            if (gen == issueListGen.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    /** {@link #issueList(Path, Consumer)} for a {@link GitHubListQuery}; see {@link #listPrs(Path, GitHubListQuery, Consumer)}. */
    public void listIssues(Path dir, GitHubListQuery query, Consumer<IssueListResult> onResult) {
        long gen = issueListGen.incrementAndGet();
        submit(() -> {
            ProcessRunner.Result r = gh(dir, NETWORK, query.issueArgs().toArray(new String[0]));
            IssueListResult res = r.ok()
                    ? new IssueListResult(true, IssueListParser.parse(r.out()), "")
                    : new IssueListResult(false, List.of(), r.message());
            if (gen == issueListGen.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    // --- workflow runs (GitHub Actions) ----------------------------------------------------------

    /** Result of a run list: {@code ok} distinguishes a failed {@code gh} call from a genuinely empty list. */
    public record RunListResult(boolean ok, List<RunListParser.WorkflowRun> runs, String error) {}

    /** Lists recent workflow runs ({@code gh run list --json …}); generation-guarded like {@link #prList}. */
    public void runList(Path dir, Consumer<RunListResult> onResult) {
        runList(dir, runListGen, onResult);
    }

    /** {@link #runList} for a one-shot consumer (a picker); see {@link #prListOnce}. */
    public void runListOnce(Path dir, Consumer<RunListResult> onResult) {
        runList(dir, null, onResult);
    }

    private void runList(Path dir, AtomicLong generation, Consumer<RunListResult> onResult) {
        long gen = generation == null ? 0 : generation.incrementAndGet();
        submit(() -> {
            ProcessRunner.Result r = gh(
                    dir,
                    NETWORK,
                    "run",
                    "list",
                    "--limit",
                    "30",
                    "--json",
                    "databaseId,displayTitle,workflowName,headBranch,status,conclusion,event,createdAt,url");
            RunListResult res = r.ok()
                    ? new RunListResult(true, RunListParser.parse(r.out()), "")
                    : new RunListResult(false, List.of(), r.message());
            if (generation == null || gen == generation.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    /** {@link #runList(Path, Consumer)} for a {@link GitHubListQuery} (only its limit applies to runs). */
    public void listRuns(Path dir, GitHubListQuery query, Consumer<RunListResult> onResult) {
        listRuns(dir, query, runListGen, onResult);
    }

    /** {@link #listRuns(Path, GitHubListQuery, Consumer)} for a one-shot consumer (a picker). */
    public void listRunsOnce(Path dir, GitHubListQuery query, Consumer<RunListResult> onResult) {
        listRuns(dir, query, null, onResult);
    }

    private void listRuns(Path dir, GitHubListQuery query, AtomicLong generation, Consumer<RunListResult> onResult) {
        long gen = generation == null ? 0 : generation.incrementAndGet();
        submit(() -> {
            ProcessRunner.Result r = gh(dir, NETWORK, query.runArgs().toArray(new String[0]));
            RunListResult res = r.ok()
                    ? new RunListResult(true, RunListParser.parse(r.out()), "")
                    : new RunListResult(false, List.of(), r.message());
            if (generation == null || gen == generation.get()) {
                Platform.runLater(() -> onResult.accept(res));
            }
        });
    }

    /** How many trailing log lines are kept — the failure tail is what matters, and this keeps the FX thread
     *  from ever receiving megabytes of text (the console trims to its own char cap anyway). */
    private static final int MAX_LOG_LINES = 3000;

    /** A run's failure log: the (tail-capped) lines, and whether earlier output was dropped. */
    public record RunLogResult(boolean ok, List<String> lines, boolean truncated, String error) {}

    /**
     * Fetches a failed run's log ({@code gh run view <id> --log-failed}). Deliberately one-shot rather than
     * streamed: gh downloads the completed run's log archive and prints it, so there's nothing to stream — a
     * finished run's log never grows. Capped twice: {@link ProcessRunner}'s 10 MB capture, then trimmed here
     * (off the FX thread) to the last {@link #MAX_LOG_LINES} lines.
     */
    public void runFailedLog(Path dir, long id, Consumer<RunLogResult> onResult) {
        submit(() -> {
            ProcessRunner.Result r = gh(dir, NETWORK, "run", "view", String.valueOf(id), "--log-failed");
            RunLogResult res;
            if (!r.ok()) {
                res = new RunLogResult(false, List.of(), false, r.message());
            } else {
                List<String> all = List.of(r.out().split("\n", -1));
                boolean truncated = all.size() > MAX_LOG_LINES;
                List<String> kept = truncated ? List.copyOf(all.subList(all.size() - MAX_LOG_LINES, all.size())) : all;
                res = new RunLogResult(true, kept, truncated, "");
            }
            RunLogResult posted = res;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** Re-runs a workflow run ({@code gh run rerun <id> [--failed]}); posts the raw result. */
    public void runRerun(Path dir, long id, boolean failedOnly, Consumer<ProcessRunner.Result> onResult) {
        if (failedOnly) {
            run(dir, NETWORK, onResult, "run", "rerun", String.valueOf(id), "--failed");
        } else {
            run(dir, NETWORK, onResult, "run", "rerun", String.valueOf(id));
        }
    }

    /** Cancels an in-flight workflow run ({@code gh run cancel <id>}); posts the raw result. */
    public void runCancel(Path dir, long id, Consumer<ProcessRunner.Result> onResult) {
        run(dir, NETWORK, onResult, "run", "cancel", String.valueOf(id));
    }

    // --- availability probe (does the repo have anything to review?) -----------------------------

    /**
     * Whether the repo has at least one open PR, open issue, <em>or</em> workflow run — a cheap one-item probe
     * of each, used to decide whether to show the GitHub tool-window stripe. Runs are included (checked last,
     * so the {@code ||} short-circuits and most repos never pay for it) because a trunk-based repo with no open
     * PRs/issues but a red CI run is exactly the case the Runs tab exists for — hiding the stripe there would
     * bury it. No generation guard: it's a one-shot availability check, not a refreshing list.
     */
    public void hasOpenActivity(Path dir, Consumer<Boolean> onResult) {
        submit(() -> {
            boolean any = hasAny(dir, "pr") || hasAny(dir, "issue") || hasAnyRun(dir);
            Platform.runLater(() -> onResult.accept(any));
        });
    }

    private boolean hasAny(Path dir, String kind) {
        ProcessRunner.Result r = ghSilent(dir, NETWORK, kind, "list", "--limit", "1", "--json", "number");
        if (!r.ok()) {
            return false;
        }
        return kind.equals("pr")
                ? !PrListParser.parse(r.out()).isEmpty()
                : !IssueListParser.parse(r.out()).isEmpty();
    }

    /** Whether the repo has at least one workflow run (Actions enabled + something has run). */
    private boolean hasAnyRun(Path dir) {
        ProcessRunner.Result r = ghSilent(dir, NETWORK, "run", "list", "--limit", "1", "--json", "databaseId");
        return r.ok() && !RunListParser.parse(r.out()).isEmpty();
    }

    // --- the resolved repository, and what the create-PR form needs to know ----------------------

    /**
     * The repository {@code gh} resolves for {@code dir} ({@code gh repo view --json nameWithOwner,…}), or
     * {@code null} when it cannot tell. With a fork's {@code origin} and an {@code upstream} remote this is
     * the upstream repository — the one every list and run action of the tool window then addresses.
     */
    public void repoInfo(Path dir, Consumer<RepoViewParser.RepoInfo> onResult) {
        submit(() -> {
            RepoViewParser.RepoInfo info = repoInfoNow(dir);
            Platform.runLater(() -> onResult.accept(info));
        });
    }

    private RepoViewParser.RepoInfo repoInfoNow(Path dir) {
        ProcessRunner.Result r = gh(dir, NETWORK, "repo", "view", "--json", "nameWithOwner,defaultBranchRef,url");
        return r.ok() ? RepoViewParser.parse(r.out()) : null;
    }

    /**
     * What is looked up before the create-PR form opens.
     *
     * @param repo the resolved repository with its default branch, or {@code null} when unknown
     * @param existing the checked-out branch's pull request, or {@code null} when it has none
     * @param template the repository's pull-request template, {@code ""} when it has none
     */
    public record CreateContext(RepoViewParser.RepoInfo repo, PrViewParser.PrDetail existing, String template) {}

    /** Gathers the {@link CreateContext} for the repository at {@code root} ({@code gh} runs in {@code dir}). */
    public void prCreateContext(Path dir, Path root, Consumer<CreateContext> onResult) {
        submit(() -> {
            CreateContext ctx = new CreateContext(repoInfoNow(dir), currentBranchPr(dir), PrDraft.template(root));
            Platform.runLater(() -> onResult.accept(ctx));
        });
    }

    // --- open on github --------------------------------------------------------------------------

    /**
     * Resolves the canonical GitHub URL for {@code repoRel} at {@code line} on {@code branch} via
     * {@code gh browse --no-browser <rel>:<line> --branch <branch>} (which prints the URL to stdout). Routing
     * through gh gets GHES hosts, renamed default branches, and permalink escaping right for free.
     */
    public void browse(Path dir, String repoRelWithLine, String branch, Consumer<ProcessRunner.Result> onResult) {
        List<String> args = new ArrayList<>(List.of("browse", "--no-browser", repoRelWithLine));
        if (branch != null && !branch.isBlank()) {
            args.add("--branch");
            args.add(branch);
        }
        run(dir, QUICK, onResult, args.toArray(new String[0]));
    }

    // --- internals -------------------------------------------------------------------------------

    /**
     * Queues {@code task} on the service lane. After {@link #shutdown()} it is dropped: a Git refresh that
     * lands while the window closes may still ask for a probe, and that must not throw on the FX thread.
     */
    private void submit(Runnable task) {
        try {
            exec.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            // the window is closing — nothing is waiting for the answer
        }
    }

    private void run(Path dir, Duration timeout, Consumer<ProcessRunner.Result> onResult, String... args) {
        submit(() -> {
            ProcessRunner.Result r = gh(dir, timeout, args);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Installs the sink that receives every {@code gh} command the user's actions cause. Unlike git there is
     * no polling here, so everything is logged except the two availability probes ({@link #detect} and the
     * tool-window {@link #hasOpenActivity} gate), which run with no user intent behind them.
     */
    public void setCommandLog(CommandLog log) {
        this.commandLog = log == null ? CommandLog.none() : log;
    }

    /** Runs {@code gh} and reports it to the {@link CommandLog}. */
    private ProcessRunner.Result gh(Path dir, Duration timeout, String... args) {
        long startNanos = System.nanoTime();
        ProcessRunner.Result r = ghSilent(dir, timeout, args);
        List<String> argv = new ArrayList<>(command);
        argv.addAll(List.of(args));
        commandLog.record(
                new CommandLog.Entry(argv, r.exit(), r.out(), r.err(), (System.nanoTime() - startNanos) / 1_000_000L));
        return r;
    }

    /** The raw invocation, used directly by the availability probes so they never reach the console. */
    private ProcessRunner.Result ghSilent(Path dir, Duration timeout, String... args) {
        List<String> cmd = new ArrayList<>(command);
        for (String a : args) {
            cmd.add(a);
        }
        return ProcessRunner.run(dir, timeout, cmd, GH_ENV);
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int nl = s.indexOf('\n');
        return (nl >= 0 ? s.substring(0, nl) : s).strip();
    }

    /**
     * Stops the service on window close. Queued work is dropped and a running read is interrupted, but a
     * {@code gh pr checkout} already running is left to finish — interrupting it kills {@code gh} and its
     * {@code git} child halfway through the branch switch (the same rule as {@code GitService.shutdown()}).
     */
    public void shutdown() {
        if (runningMutations.get() > 0) {
            exec.shutdown();
            exec.getQueue().clear();
        } else {
            exec.shutdownNow();
        }
    }
}
