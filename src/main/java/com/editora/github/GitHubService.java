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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;

import com.editora.diff.PatchParser;
import com.editora.process.CommandLog;
import com.editora.process.ConfiguredCommand;
import com.editora.process.ProcessRunner;

/**
 * The native-{@code gh} facade — a structural clone of {@code GitService}. Every GitHub command shells out via
 * {@link ProcessRunner} on a daemon executor thread and posts results back on the JavaFX thread, so the UI
 * thread is never blocked. There are four lanes, so one slow call cannot hold up the rest: working-tree and
 * remote <em>mutations</em> (checkout, create, review, rerun, cancel) in order on one; <em>reads</em> (lists,
 * a PR's detail / diff / files, a CI log) on another; small <em>lookups</em> (the status bar's checks poll,
 * the resolved repository, what the create-PR form needs) on a third; and the silent <em>probes</em> (is gh
 * there and signed in, does the repository have anything to show) on a fourth, with a short timeout. Every
 * read and lookup returns a {@link ProcessRunner.Cancellation}: cancelling it kills {@code gh} and its
 * consumer is never called — a newer request for the same list does that to the older one. Availability is
 * probed and cached ({@link #detect}); {@link #activeCallsProperty()} says how many of the user's calls are
 * queued or running (background polls and probes are not counted).
 *
 * <p>Every editor read (owner/repo resolution) is delegated to {@code gh} itself: commands run with a
 * working directory inside the repo, so {@code gh} resolves the host + owner/repo from the git remote — no
 * host config lives in Editora. Security: {@code gh} holds the user's GitHub credentials; Editora never sees,
 * stores, or transmits a token — it only shells out, exactly like {@code GitService} does for {@code git}.
 */
public final class GitHubService {

    private static final Duration QUICK = Duration.ofSeconds(10);
    private static final Duration NETWORK = Duration.ofSeconds(120);
    /** Ceiling for one background probe call: nobody is waiting for it, and two more may queue behind it. */
    private static final Duration PROBE = Duration.ofSeconds(20);
    /** Ceiling for one call on the lookup lane: a single small JSON answer. */
    private static final Duration LOOKUP = Duration.ofSeconds(30);
    /** Ceiling for downloading a failed run's log — long, because the fetch can be stopped ({@link #runFailedLog}). */
    private static final Duration LOG = Duration.ofMinutes(5);
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

    /** The mutation lane: {@code gh pr checkout / create / review}, {@code gh run rerun / cancel}, in order. */
    private final ThreadPoolExecutor exec = lane("github-service");

    /** The read lane, so a list or a diff never waits behind a long checkout. */
    private final ThreadPoolExecutor reads = lane("github-read");

    /** The probe lane ({@link #detect}, {@link #openActivity}): background checks never delay a user's call. */
    private final ThreadPoolExecutor probes = lane("github-probe");

    /**
     * The lookup lane: small metadata queries that something is waiting on but that must neither wait behind
     * a big read (a diff, a CI log) nor delay one — the status bar's checks poll ({@link #branchChecks}), the
     * tool window's repository name ({@link #repoInfo}), what the create-PR form needs
     * ({@link #prCreateContext}). They queue only behind each other, and each is short ({@link #LOOKUP}).
     * (Not the probe lane: a checks poll there would hold up the "is gh usable" answer commands wait for.)
     */
    private final ThreadPoolExecutor lookups = lane("github-lookup");

    private static ThreadPoolExecutor lane(String name) {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /** Working-tree mutations ({@code gh pr checkout}) currently running; {@link #shutdown()} lets them finish. */
    private final AtomicInteger runningMutations = new AtomicInteger();

    /** The configured {@code gh} command tokens (default {@code ["gh"]}); a blank override resets to gh. */
    private volatile List<String> command = List.of("gh");

    /** null = not yet probed (for the current {@link #command}); cached by every conclusive {@link #detect}. */
    private volatile Availability availability;

    /** Guards {@link #probeWaiters}, and pairs {@link #command} with the {@link #availability} it was probed for. */
    private final Object probeLock = new Object();

    /** The consumers of the probe that is queued or running ({@code null} when none is): detects coalesce. */
    private List<Consumer<Availability>> probeWaiters;

    /** {@link System#nanoTime} when the last probe finished ({@link #redetectIfOlderThan}). */
    private volatile long lastProbeNanos = System.nanoTime() - TimeUnit.HOURS.toNanos(1);

    /** User-facing calls queued or running; mirrored into {@link #activeCalls} on the FX thread. */
    private final AtomicInteger pendingCalls = new AtomicInteger();

    private final ReadOnlyIntegerWrapper activeCalls = new ReadOnlyIntegerWrapper(this, "activeCalls", 0);

    /**
     * Where completed {@code gh} commands are reported (the Output "GitHub" tab). Volatile: installed
     * from the FX thread, read on {@link #exec}.
     */
    private volatile CommandLog commandLog = CommandLog.none();

    /**
     * The request in flight for each refreshing list: a newer request for the <em>same</em> list supersedes
     * the older one (its {@code gh} is killed and its consumer never called). One slot per list kind — asking
     * for issues must not drop a pull-request answer still in flight.
     */
    private final AtomicReference<ProcessRunner.Cancellation> prListCall = new AtomicReference<>();

    private final AtomicReference<ProcessRunner.Cancellation> issueListCall = new AtomicReference<>();
    private final AtomicReference<ProcessRunner.Cancellation> runListCall = new AtomicReference<>();
    private final AtomicReference<ProcessRunner.Cancellation> branchChecksCall = new AtomicReference<>();

    /** What is known about {@code gh}'s sign-in; see {@link GhAuthStatus.State}. */
    public enum AuthState {
        /** A host accepted gh's token. */
        SIGNED_IN,
        /** gh has an account, but GitHub could not be reached to check it (offline) — commands may still work. */
        UNVERIFIED,
        /** gh has no account: {@code gh auth login} is needed. */
        SIGNED_OUT,
        /** GitHub refused gh's saved token: {@code gh auth refresh} / {@code gh auth login} is needed. */
        REJECTED
    }

    /**
     * Whether {@code gh} is on PATH, its version line, and its sign-in: {@code authenticated} is true only
     * when a host confirmed the token, {@code auth} tells "signed out" from "could not check", {@code hosts}
     * are the hosts gh has an account on (empty = not known, e.g. gh older than 2.81), {@code detail} is
     * gh's own reason for a failed check, and {@code accounts} is the login gh uses on each host (empty when
     * gh did not say).
     */
    public record Availability(
            boolean found,
            boolean authenticated,
            String version,
            AuthState auth,
            List<String> hosts,
            String detail,
            java.util.Map<String, String> accounts) {
        public static final Availability UNKNOWN = new Availability(false, false, "");

        public Availability(
                boolean found,
                boolean authenticated,
                String version,
                AuthState auth,
                List<String> hosts,
                String detail) {
            this(found, authenticated, version, auth, hosts, detail, java.util.Map.of());
        }

        public Availability(boolean found, boolean authenticated, String version) {
            this(
                    found,
                    authenticated,
                    version,
                    authenticated ? AuthState.SIGNED_IN : AuthState.SIGNED_OUT,
                    List.of(),
                    "");
        }

        static Availability found(String version, AuthState auth, List<String> hosts, String detail) {
            return found(version, auth, hosts, detail, java.util.Map.of());
        }

        static Availability found(
                String version,
                AuthState auth,
                List<String> hosts,
                String detail,
                java.util.Map<String, String> accounts) {
            return new Availability(
                    true,
                    auth == AuthState.SIGNED_IN,
                    version,
                    auth,
                    List.copyOf(hosts),
                    detail,
                    java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(accounts)));
        }

        /**
         * The login gh uses on {@code host} ({@code ""} when not known). With the host unknown — the
         * repository not resolved yet — a single account is still the answer; several are not guessed among.
         */
        public String account(String host) {
            String h = host == null ? "" : host.strip().toLowerCase(java.util.Locale.ROOT);
            if (h.isEmpty()) {
                return accounts.size() == 1 ? accounts.values().iterator().next() : "";
            }
            return accounts.getOrDefault(h, "");
        }

        /**
         * GitHub commands can be attempted: gh is there and has an account. That includes
         * {@link AuthState#UNVERIFIED} — being offline when the probe ran must not disable the integration
         * for the session; a command that still cannot reach GitHub reports gh's own network error.
         */
        public boolean ready() {
            return found && (auth == AuthState.SIGNED_IN || auth == AuthState.UNVERIFIED);
        }

        /** gh has an account but GitHub could not be reached to check it. */
        public boolean unverified() {
            return found && auth == AuthState.UNVERIFIED;
        }

        /** Whether this gh has {@code pr checks --json} (gh {@value GhVersion#MINIMUM}+) — the CI-checks roll-up. */
        public boolean supportsChecks() {
            return GhVersion.supportsChecksJson(version);
        }
    }

    /**
     * Sets the {@code gh} command/path ({@link #commandTokens}); blank ⇒ resolve {@code gh} on PATH. Returns
     * whether the command actually changed — only then is the cached availability dropped (it was probed for
     * another program). Re-applying the same setting, which happens on every settings save, changes nothing.
     */
    public boolean setCommand(String configured) {
        List<String> next = commandTokens(configured);
        synchronized (probeLock) {
            if (next.equals(command)) {
                return false;
            }
            command = next;
            availability = null; // re-probe with the new command
            return true;
        }
    }

    /**
     * The argv prefix for a configured gh command: an existing file is one executable, whatever spaces its
     * path has; anything else is tokenized quote-aware. Public so the Doctor screen checks exactly the
     * command this service runs.
     */
    public static List<String> commandTokens(String configured) {
        return ConfiguredCommand.tokens(configured, "gh");
    }

    /** The last probed availability (may be {@code null} before the first {@link #detect}). */
    public Availability availability() {
        return availability;
    }

    /**
     * How many user-facing {@code gh} calls are queued or running (the background probes are not counted) —
     * bind a busy indicator to {@code activeCallsProperty().greaterThan(0)}. Changes on the FX thread.
     */
    public ReadOnlyIntegerProperty activeCallsProperty() {
        return activeCalls.getReadOnlyProperty();
    }

    // --- detection -------------------------------------------------------------------------------

    /**
     * Probes {@code gh} off the FX thread ({@link #probe}) and posts an {@link Availability} on the FX thread,
     * caching it. The previous answer stays in place while the probe runs, and an inconclusive probe (gh did
     * not answer in time) never replaces it — it posts the previous answer, or {@link Availability#UNKNOWN}
     * with nothing cached, so the next command probes again. Concurrent requests share one probe.
     */
    public void detect(Consumer<Availability> onResult) {
        synchronized (probeLock) {
            if (probeWaiters != null) {
                probeWaiters.add(onResult);
                return;
            }
            probeWaiters = new ArrayList<>(List.of(onResult));
        }
        submit(probes, this::runDetect);
    }

    /**
     * {@link #detect} unless a probe finished less than {@code age} ago — how a command that meets a cached
     * "not installed / not signed in" asks again without starting {@code gh} on every keystroke. Returns
     * whether a probe was requested ({@code onResult} is called only then).
     */
    public boolean redetectIfOlderThan(Duration age, Consumer<Availability> onResult) {
        synchronized (probeLock) {
            if (probeWaiters == null && System.nanoTime() - lastProbeNanos < age.toNanos()) {
                return false;
            }
        }
        detect(onResult);
        return true;
    }

    private void runDetect() {
        Availability posted = Availability.UNKNOWN;
        List<Consumer<Availability>> waiters = List.of();
        try {
            while (true) {
                List<String> probed = command;
                Availability fresh = probe(probed, QUICK);
                synchronized (probeLock) {
                    if (probed != command) {
                        continue; // the command changed while gh ran: that answer is about another program
                    }
                    if (fresh != null) {
                        availability = fresh;
                    }
                    posted = fresh != null ? fresh : availability != null ? availability : Availability.UNKNOWN;
                    break;
                }
            }
        } finally {
            lastProbeNanos = System.nanoTime();
            synchronized (probeLock) {
                if (probeWaiters != null) {
                    waiters = probeWaiters;
                    probeWaiters = null;
                }
            }
        }
        Availability a = posted;
        List<Consumer<Availability>> notify = waiters;
        Platform.runLater(() -> notify.forEach(w -> w.accept(a)));
    }

    /**
     * Runs the availability probe for {@code command} on the calling thread: {@code gh --version}, then
     * {@code gh auth status --json hosts} ({@link GhAuthStatus}), which — unlike the exit code of plain
     * {@code gh auth status} — tells "signed out" from "offline". A gh without {@code --json} there (before
     * 2.81) falls back to that exit code, where a failure can only be read as {@link AuthState#UNVERIFIED}
     * unless gh says outright that there is no login. Returns {@code null} when gh did not answer in time
     * (inconclusive: not evidence that it is missing). Never logged to the {@link CommandLog}. Public so the
     * Doctor screen reports the same answer.
     */
    public static Availability probe(List<String> command, Duration timeout) {
        ProcessRunner.Result v;
        try {
            v = run(command, null, timeout, null, "--version");
        } catch (RuntimeException notLaunchable) {
            return Availability.UNKNOWN;
        }
        if (v.timedOut()) {
            return null;
        }
        if (!v.ok()) {
            return Availability.UNKNOWN;
        }
        String version = firstLine(v.out());
        try {
            ProcessRunner.Result json = run(command, null, timeout, null, "auth", "status", "--json", "hosts");
            if (json.timedOut()) {
                return Availability.found(version, AuthState.UNVERIFIED, List.of(), "");
            }
            GhAuthStatus.Parsed parsed = json.ok() ? GhAuthStatus.parse(json.out()) : null;
            if (parsed != null) {
                return Availability.found(
                        version, authState(parsed.state()), parsed.hosts(), parsed.detail(), parsed.accounts());
            }
            ProcessRunner.Result plain = run(command, null, timeout, null, "auth", "status");
            if (plain.ok()) {
                return Availability.found(version, AuthState.SIGNED_IN, List.of(), "");
            }
            String said = (plain.err() + "\n" + plain.out()).toLowerCase(java.util.Locale.ROOT);
            boolean noLogin = !plain.timedOut() && said.contains("not logged in");
            return Availability.found(version, noLogin ? AuthState.SIGNED_OUT : AuthState.UNVERIFIED, List.of(), "");
        } catch (RuntimeException failed) {
            return Availability.found(version, AuthState.UNVERIFIED, List.of(), "");
        }
    }

    private static AuthState authState(GhAuthStatus.State state) {
        return switch (state) {
            case SIGNED_IN -> AuthState.SIGNED_IN;
            case UNVERIFIED -> AuthState.UNVERIFIED;
            case SIGNED_OUT -> AuthState.SIGNED_OUT;
            case REJECTED -> AuthState.REJECTED;
        };
    }

    // --- pull requests ---------------------------------------------------------------------------

    /** Result of a PR list: {@code ok} distinguishes a failed {@code gh} call from a genuinely empty list. */
    public record PrListResult(boolean ok, List<PrListParser.PullRequest> prs, String error) {}

    /**
     * Lists pull requests for a {@link GitHubListQuery} (state, "mine", limit) with {@code gh pr list --json …};
     * a newer request supersedes (and kills) an older one. The answer holds up to
     * {@link GitHubListQuery#fetchLimit()} rows — one more than is shown — for {@link GitHubListQuery#page}.
     */
    public ProcessRunner.Cancellation listPrs(Path dir, GitHubListQuery query, Consumer<PrListResult> onResult) {
        return listPrs(dir, query, prListCall, onResult);
    }

    /**
     * {@link #listPrs} for a one-shot consumer (a picker) that must get its answer whatever the tool window
     * asks for meanwhile — and must not take the tool window's answer away either.
     */
    public ProcessRunner.Cancellation listPrsOnce(Path dir, GitHubListQuery query, Consumer<PrListResult> onResult) {
        return listPrs(dir, query, null, onResult);
    }

    private ProcessRunner.Cancellation listPrs(
            Path dir,
            GitHubListQuery query,
            AtomicReference<ProcessRunner.Cancellation> slot,
            Consumer<PrListResult> onResult) {
        return read(
                slot,
                cancel -> {
                    ProcessRunner.Result r =
                            gh(dir, NETWORK, cancel, query.prArgs().toArray(new String[0]));
                    return r.ok()
                            ? new PrListResult(true, PrListParser.parse(r.out()), "")
                            : new PrListResult(false, List.of(), r.message());
                },
                onResult);
    }

    /** A PR's detail ({@code gh pr view <n> --json …}); posts {@code null} on failure. */
    public ProcessRunner.Cancellation prView(Path dir, int number, Consumer<PrViewParser.PrDetail> onResult) {
        return read(
                null,
                cancel -> {
                    ProcessRunner.Result r = gh(
                            dir,
                            NETWORK,
                            cancel,
                            "pr",
                            "view",
                            String.valueOf(number),
                            "--json",
                            "number,title,body,author,baseRefName,headRefName,state,url,additions,deletions");
                    return r.ok() ? PrViewParser.parse(r.out()) : null;
                },
                onResult);
    }

    /**
     * Result of a PR diff: the parsed per-file patches, or an error message. {@code truncated} means the diff
     * was larger than {@link ProcessRunner} captures (10 MB): {@code files} then holds only the files whose
     * patch arrived whole, and the review must say that it is incomplete.
     */
    public record DiffResult(boolean ok, List<PatchParser.FilePatch> files, String error, boolean truncated) {
        public DiffResult(boolean ok, List<PatchParser.FilePatch> files, String error) {
            this(ok, files, error, false);
        }
    }

    /** Fetches a PR's whole unified diff ({@code gh pr diff <n>}) and parses it off-thread into file patches. */
    public ProcessRunner.Cancellation prDiff(Path dir, int number, Consumer<DiffResult> onResult) {
        return read(
                null,
                cancel -> {
                    ProcessRunner.Result r = gh(dir, NETWORK, cancel, "pr", "diff", String.valueOf(number));
                    if (!r.ok()) {
                        return new DiffResult(false, List.of(), r.message());
                    }
                    String text = r.outTruncated() ? wholeFileSections(r.out()) : r.out();
                    return new DiffResult(true, PatchParser.parseAllSections(text), "", r.outTruncated());
                },
                onResult);
    }

    /**
     * A diff cut off mid-stream, reduced to the files that arrived whole: everything before the last
     * {@code diff --git} header (the file after it stops wherever the capture did, and would be shown as if
     * its patch ended there).
     */
    static String wholeFileSections(String cutDiff) {
        int last = cutDiff.lastIndexOf("\ndiff --git ");
        return last < 0 ? "" : cutDiff.substring(0, last + 1);
    }

    /**
     * Result of {@link #prFiles}: the per-file patches, how many files came without one, or an error.
     * {@code truncated} means the API's answer was larger than {@link ProcessRunner} captures (10 MB):
     * {@code files} then holds only the files read before the cut, and the review must say so.
     */
    public record PrFilesResult(boolean ok, PrFilesParser.Result files, String error, boolean truncated) {
        public PrFilesResult(boolean ok, PrFilesParser.Result files, String error) {
            this(ok, files, error, false);
        }
    }

    /**
     * A PR's files from the REST API ({@code gh api repos/{owner}/{repo}/pulls/<n>/files --paginate}) — what
     * {@link #prDiff} falls back to when GitHub refuses the whole diff for its size (HTTP 406). {@code gh}
     * fills {@code {owner}/{repo}} and the host in from the working directory's repository, as it does for
     * {@code gh pr diff}, so a fork or a GitHub Enterprise host needs nothing extra and no ref is fetched
     * into the user's repository.
     */
    public ProcessRunner.Cancellation prFiles(Path dir, int number, Consumer<PrFilesResult> onResult) {
        return read(
                null,
                cancel -> {
                    ProcessRunner.Result r = gh(
                            dir,
                            NETWORK,
                            cancel,
                            "api",
                            "repos/{owner}/{repo}/pulls/" + number + "/files?per_page=100",
                            "--paginate");
                    return r.ok()
                            ? new PrFilesResult(true, PrFilesParser.parse(r.out()), "", r.outTruncated())
                            : new PrFilesResult(false, new PrFilesParser.Result(List.of(), 0), r.message());
                },
                onResult);
    }

    /** Checks out a PR branch ({@code gh pr checkout <n>}); posts the raw result for status/error reporting. */
    public void prCheckout(Path dir, int number, Consumer<ProcessRunner.Result> onResult) {
        submitCall(exec, () -> {
            runningMutations.incrementAndGet();
            ProcessRunner.Result r;
            try {
                r = gh(dir, CHECKOUT, null, "pr", "checkout", String.valueOf(number));
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
     * request's checks ({@code gh pr checks <n> --json …}, gh 2.50+ — the caller asks
     * {@link Availability#supportsChecks()} first). A branch without a pull request costs the one
     * {@code gh pr view} call. The JSON is parsed whatever the exit code: {@code gh pr checks} exits 1
     * (failing) / 8 (pending) with it still on stdout.
     *
     * <p>This is what the status bar polls, so it is a background call in every respect: it runs on the
     * lookup lane (never ahead of, or behind, a list the user asked for), is not counted in
     * {@link #activeCallsProperty()}, is not written to the {@link CommandLog} (a poll every few minutes
     * would bury the commands the user ran), and a newer request kills an older one still running.
     */
    public ProcessRunner.Cancellation branchChecks(Path dir, Consumer<BranchChecks> onResult) {
        return call(
                lookups,
                false,
                branchChecksCall,
                cancel -> {
                    PrViewParser.PrDetail pr = currentBranchPr(dir, cancel, false);
                    if (pr == null || pr.number() <= 0) {
                        return BranchChecks.NONE;
                    }
                    ProcessRunner.Result r = ghSilent(
                            dir,
                            LOOKUP,
                            cancel,
                            "pr",
                            "checks",
                            String.valueOf(pr.number()),
                            "--json",
                            "name,state,bucket,link,workflow");
                    return new BranchChecks(true, pr, ChecksParser.parse(r.out()));
                },
                onResult);
    }

    /** The pull request of the checked-out branch, or {@code null} when it has none. Runs on the lane. */
    private PrViewParser.PrDetail currentBranchPr(Path dir, ProcessRunner.Cancellation cancel, boolean logged) {
        String[] args = {
            "pr", "view", "--json", "number,title,body,author,baseRefName,headRefName,state,url,additions,deletions"
        };
        ProcessRunner.Result r = logged ? gh(dir, LOOKUP, cancel, args) : ghSilent(dir, LOOKUP, cancel, args);
        return r.ok() ? PrViewParser.parse(r.out()) : null;
    }

    // --- issues ----------------------------------------------------------------------------------

    /** Result of an issue list. */
    public record IssueListResult(boolean ok, List<IssueListParser.Issue> issues, String error) {}

    /**
     * Lists issues for a {@link GitHubListQuery} ({@code gh issue list --json …}); a newer request supersedes
     * an older one. See {@link #listPrs(Path, GitHubListQuery, Consumer)}.
     */
    public ProcessRunner.Cancellation listIssues(Path dir, GitHubListQuery query, Consumer<IssueListResult> onResult) {
        return read(
                issueListCall,
                cancel -> {
                    ProcessRunner.Result r =
                            gh(dir, NETWORK, cancel, query.issueArgs().toArray(new String[0]));
                    return r.ok()
                            ? new IssueListResult(true, IssueListParser.parse(r.out()), "")
                            : new IssueListResult(false, List.of(), r.message());
                },
                onResult);
    }

    // --- workflow runs (GitHub Actions) ----------------------------------------------------------

    /** Result of a run list: {@code ok} distinguishes a failed {@code gh} call from a genuinely empty list. */
    public record RunListResult(boolean ok, List<RunListParser.WorkflowRun> runs, String error) {}

    /**
     * Lists recent workflow runs for a {@link GitHubListQuery} (only its limit applies to runs) with
     * {@code gh run list --json …}; superseding like {@link #listPrs(Path, GitHubListQuery, Consumer)}.
     */
    public ProcessRunner.Cancellation listRuns(Path dir, GitHubListQuery query, Consumer<RunListResult> onResult) {
        return listRuns(dir, query, runListCall, onResult);
    }

    /** {@link #listRuns(Path, GitHubListQuery, Consumer)} for a one-shot consumer (a picker); see {@link #listPrsOnce}. */
    public ProcessRunner.Cancellation listRunsOnce(Path dir, GitHubListQuery query, Consumer<RunListResult> onResult) {
        return listRuns(dir, query, null, onResult);
    }

    private ProcessRunner.Cancellation listRuns(
            Path dir,
            GitHubListQuery query,
            AtomicReference<ProcessRunner.Cancellation> slot,
            Consumer<RunListResult> onResult) {
        return read(
                slot,
                cancel -> {
                    ProcessRunner.Result r =
                            gh(dir, NETWORK, cancel, query.runArgs().toArray(new String[0]));
                    return r.ok()
                            ? new RunListResult(true, RunListParser.parse(r.out()), "")
                            : new RunListResult(false, List.of(), r.message());
                },
                onResult);
    }

    /** How many trailing log lines are kept — the failure tail is what matters, and this keeps the FX thread
     *  from ever receiving megabytes of text (the console trims to its own char cap anyway). */
    static final int MAX_LOG_LINES = 3000;

    /** A run's failure log: the (tail-capped) lines, and whether earlier output was dropped. */
    public record RunLogResult(boolean ok, List<String> lines, boolean truncated, String error) {}

    /**
     * Fetches a failed run's log ({@code gh run view <id> --log-failed}): gh downloads the completed run's
     * log archive and prints it. The <em>last</em> {@link #MAX_LOG_LINES} lines are kept as they stream past
     * ({@link TailLines}), whatever the log's size — the failure is at the end, beyond what a capture of the
     * first megabytes holds. Cancelling the returned handle kills {@code gh} (the console's Stop).
     */
    public ProcessRunner.Cancellation runFailedLog(Path dir, long id, Consumer<RunLogResult> onResult) {
        return read(
                null,
                cancel -> {
                    TailLines tail = new TailLines(MAX_LOG_LINES);
                    ProcessRunner.Result r =
                            ghTail(dir, LOG, cancel, tail, "run", "view", String.valueOf(id), "--log-failed");
                    return r.ok()
                            ? new RunLogResult(true, tail.lines(), tail.truncated(), "")
                            : new RunLogResult(false, List.of(), false, r.message());
                },
                onResult);
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

    /** Whether a repository has anything for the tool window to show — or that it could not be found out. */
    public enum Activity {
        YES,
        NONE,
        /** {@code gh} could not answer (offline, rate-limited, not a repository it knows): not a "no". */
        UNKNOWN
    }

    /**
     * Whether the repo has at least one open PR, open issue, <em>or</em> workflow run — a cheap one-item probe
     * of each, used to decide whether to show the GitHub tool-window stripe. Runs are included (checked last,
     * so most repos never pay for it) because a trunk-based repo with no open PRs/issues but a red CI run is
     * exactly the case the Runs tab exists for — hiding the stripe there would bury it.
     *
     * <p>A failed pull-request probe is {@link Activity#UNKNOWN} — every GitHub repository can list its pull
     * requests, so that failing means gh could not reach or resolve the repository, and the caller must not
     * cache it as "nothing here". (Issues or Actions can be switched off per repository, so <em>their</em>
     * probes failing only means there are none.) Runs on the probe lane with a short timeout.
     */
    public void openActivity(Path dir, Consumer<Activity> onResult) {
        submit(probes, () -> {
            Activity activity = probeActivity(dir);
            Platform.runLater(() -> onResult.accept(activity));
        });
    }

    /** {@link #openActivity} as a yes/no, for a caller that has no use for "could not check" (it reads as no). */
    public void hasOpenActivity(Path dir, Consumer<Boolean> onResult) {
        openActivity(dir, activity -> onResult.accept(activity == Activity.YES));
    }

    private Activity probeActivity(Path dir) {
        ProcessRunner.Result prs = ghSilent(dir, PROBE, null, "pr", "list", "--limit", "1", "--json", "number");
        if (!prs.ok()) {
            return Activity.UNKNOWN;
        }
        if (!PrListParser.parse(prs.out()).isEmpty()) {
            return Activity.YES;
        }
        ProcessRunner.Result issues = ghSilent(dir, PROBE, null, "issue", "list", "--limit", "1", "--json", "number");
        if (issues.ok() && !IssueListParser.parse(issues.out()).isEmpty()) {
            return Activity.YES;
        }
        // Whether the repo has at least one workflow run (Actions enabled + something has run).
        ProcessRunner.Result runs = ghSilent(dir, PROBE, null, "run", "list", "--limit", "1", "--json", "databaseId");
        return runs.ok() && !RunListParser.parse(runs.out()).isEmpty() ? Activity.YES : Activity.NONE;
    }

    // --- the resolved repository, and what the create-PR form needs to know ----------------------

    /**
     * The repository {@code gh} resolves for {@code dir} ({@code gh repo view --json nameWithOwner,…}), or
     * {@code null} when it cannot tell. With a fork's {@code origin} and an {@code upstream} remote this is
     * the upstream repository — the one every list and run action of the tool window then addresses.
     */
    public ProcessRunner.Cancellation repoInfo(Path dir, Consumer<RepoViewParser.RepoInfo> onResult) {
        // Asked for by the tool window itself, once per repository: a background lookup (see branchChecks).
        return call(lookups, false, null, cancel -> repoInfoNow(dir, cancel, false), onResult);
    }

    private RepoViewParser.RepoInfo repoInfoNow(Path dir, ProcessRunner.Cancellation cancel, boolean logged) {
        String[] args = {"repo", "view", "--json", "nameWithOwner,defaultBranchRef,url"};
        ProcessRunner.Result r = logged ? gh(dir, LOOKUP, cancel, args) : ghSilent(dir, LOOKUP, cancel, args);
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

    /**
     * Gathers the {@link CreateContext} for the repository at {@code root} ({@code gh} runs in {@code dir}).
     * The user is waiting for a form, so this is a counted, logged call — but on the lookup lane: two small
     * metadata queries must not wait behind a diff or a CI log still downloading on the read lane.
     */
    public ProcessRunner.Cancellation prCreateContext(Path dir, Path root, Consumer<CreateContext> onResult) {
        return call(
                lookups,
                true,
                null,
                cancel -> new CreateContext(
                        repoInfoNow(dir, cancel, true), currentBranchPr(dir, cancel, true), PrDraft.template(root)),
                onResult);
    }

    // --- open on github --------------------------------------------------------------------------

    /**
     * Resolves the canonical GitHub URL for {@code repoRel} at {@code line} on {@code branch} via
     * {@code gh browse --no-browser --branch <branch> -- <rel>:<line>} (which prints the URL to stdout).
     * Routing through gh gets GHES hosts, renamed default branches, and permalink escaping right for free.
     */
    public void browse(Path dir, String repoRelWithLine, String branch, Consumer<ProcessRunner.Result> onResult) {
        List<String> args = browseArgs(repoRelWithLine, branch);
        read(null, cancel -> gh(dir, QUICK, cancel, args.toArray(new String[0])), onResult);
    }

    /**
     * The {@code gh browse} sub-args: every flag first, then {@code --}, then the path — a file whose name
     * starts with {@code -} is a path, not an option. Pure.
     */
    static List<String> browseArgs(String repoRelWithLine, String branch) {
        List<String> args = new ArrayList<>(List.of("browse", "--no-browser"));
        if (branch != null && !branch.isBlank()) {
            args.add("--branch");
            args.add(branch);
        }
        args.add("--");
        args.add(repoRelWithLine);
        return args;
    }

    // --- internals -------------------------------------------------------------------------------

    /**
     * Queues {@code task} on {@code lane}; {@code false} when it was dropped. After {@link #shutdown()} it is
     * dropped: a Git refresh that lands while the window closes may still ask for a probe, and that must not
     * throw on the FX thread.
     */
    private static boolean submit(ThreadPoolExecutor lane, Runnable task) {
        try {
            lane.execute(task);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            return false; // the window is closing — nothing is waiting for the answer
        }
    }

    /** {@link #submit} for a user-facing call: counted in {@link #activeCallsProperty()} until it has run. */
    private void submitCall(ThreadPoolExecutor lane, Runnable task) {
        pendingCalls.incrementAndGet();
        boolean queued = submit(lane, () -> {
            try {
                task.run();
            } finally {
                pendingCalls.decrementAndGet();
                publishActiveCalls();
            }
        });
        if (!queued) {
            pendingCalls.decrementAndGet();
        }
        publishActiveCalls();
    }

    private void publishActiveCalls() {
        try {
            Platform.runLater(() -> activeCalls.set(pendingCalls.get()));
        } catch (IllegalStateException noToolkit) {
            // no FX runtime (a plain unit test): nothing is bound to the property
        }
    }

    /**
     * Queues a read on the read lane and returns its handle. {@code work} gets the handle to pass to
     * {@link #gh}; its result is posted to {@code onResult} on the FX thread <em>unless the handle was
     * cancelled</em> — by the caller, or because a newer request took {@code slot} (a refreshing list's
     * "request in flight"; {@code null} for a one-shot read).
     */
    private <T> ProcessRunner.Cancellation read(
            AtomicReference<ProcessRunner.Cancellation> slot,
            Function<ProcessRunner.Cancellation, T> work,
            Consumer<T> onResult) {
        return call(reads, true, slot, work, onResult);
    }

    /**
     * {@link #read} on a given {@code lane}; {@code counted} says whether the call shows in
     * {@link #activeCallsProperty()} (a user's call does, a background poll does not).
     */
    private <T> ProcessRunner.Cancellation call(
            ThreadPoolExecutor lane,
            boolean counted,
            AtomicReference<ProcessRunner.Cancellation> slot,
            Function<ProcessRunner.Cancellation, T> work,
            Consumer<T> onResult) {
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        if (slot != null) {
            ProcessRunner.Cancellation superseded = slot.getAndSet(cancel);
            if (superseded != null) {
                superseded.cancel();
            }
        }
        Runnable task = () -> {
            T result = work.apply(cancel);
            Platform.runLater(() -> {
                if (!cancel.cancelled()) {
                    onResult.accept(result);
                }
            });
        };
        if (counted) {
            submitCall(lane, task);
        } else {
            submit(lane, task);
        }
        return cancel;
    }

    private void run(Path dir, Duration timeout, Consumer<ProcessRunner.Result> onResult, String... args) {
        submitCall(exec, () -> {
            ProcessRunner.Result r = gh(dir, timeout, null, args);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Installs the sink that receives every {@code gh} command the user's actions cause. Unlike git there is
     * no polling here, so everything is logged except the two availability probes ({@link #detect} and the
     * tool-window {@link #openActivity} gate), which run with no user intent behind them.
     */
    public void setCommandLog(CommandLog log) {
        this.commandLog = log == null ? CommandLog.none() : log;
    }

    /** Runs {@code gh} and reports it to the {@link CommandLog}; {@code cancel} (may be null) kills it. */
    private ProcessRunner.Result gh(Path dir, Duration timeout, ProcessRunner.Cancellation cancel, String... args) {
        long startNanos = System.nanoTime();
        ProcessRunner.Result r = ghSilent(dir, timeout, cancel, args);
        record(r, r.out(), startNanos, args);
        return r;
    }

    /**
     * {@link #gh} for output of any size: each line goes to {@code tail} as it is written, so what is kept
     * is the end of the output rather than the first megabytes of it. (Run through the live runner, which
     * leaves the locale alone — this output is shown, not parsed.)
     */
    private ProcessRunner.Result ghTail(
            Path dir, Duration timeout, ProcessRunner.Cancellation cancel, TailLines tail, String... args) {
        long startNanos = System.nanoTime();
        ProcessRunner.Result r;
        if (cancel.cancelled()) {
            r = new ProcessRunner.Result(-1, "", ProcessRunner.CANCELLED);
        } else {
            r = ProcessRunner.runLiveInUserLocale(
                    dir,
                    timeout,
                    argv(command, args),
                    GH_ENV,
                    (line, transientLine) -> {
                        if (!transientLine) {
                            tail.add(line);
                        }
                    },
                    cancel);
        }
        // The console shows the log itself; the command log gets the command and its outcome, not megabytes.
        record(r, "", startNanos, args);
        return r;
    }

    private void record(ProcessRunner.Result r, String out, long startNanos, String... args) {
        commandLog.record(new CommandLog.Entry(
                argv(command, args), r.exit(), out, r.err(), (System.nanoTime() - startNanos) / 1_000_000L));
    }

    /**
     * The raw invocation, used directly by the availability probes so they never reach the console. A call
     * whose handle was cancelled while it waited in the queue is not started at all.
     */
    private ProcessRunner.Result ghSilent(
            Path dir, Duration timeout, ProcessRunner.Cancellation cancel, String... args) {
        if (cancel != null && cancel.cancelled()) {
            return new ProcessRunner.Result(-1, "", ProcessRunner.CANCELLED);
        }
        return run(command, dir, timeout, cancel, args);
    }

    private static ProcessRunner.Result run(
            List<String> command, Path dir, Duration timeout, ProcessRunner.Cancellation cancel, String... args) {
        return ProcessRunner.run(dir, timeout, argv(command, args), GH_ENV, cancel);
    }

    private static List<String> argv(List<String> command, String... args) {
        List<String> cmd = new ArrayList<>(command);
        cmd.addAll(List.of(args));
        return cmd;
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
        reads.shutdownNow();
        probes.shutdownNow();
        lookups.shutdownNow();
        if (runningMutations.get() > 0) {
            exec.shutdown();
            exec.getQueue().clear();
        } else {
            exec.shutdownNow();
        }
    }
}
