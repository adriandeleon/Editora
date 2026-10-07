package com.editora.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.process.CommandLog;
import com.editora.process.ProcessRunner;

/**
 * The native-{@code git} facade. Every Git command shells out via {@link ProcessRunner} on a daemon
 * executor thread (the {@code highlightExecutor} idiom) and posts results back on the JavaFX thread, so the
 * UI thread is never blocked. Git's presence is detected once and cached (its absence only for a while); repo
 * roots are cached per directory and revalidated against the {@code .git} entries on the way up. When Git is absent or a path isn't in a work tree, callers get {@link RepoState#NONE} /
 * {@link GitStatus#NOT_A_REPO} and keep the Git UI hidden.
 *
 * <p>Two kinds of command are kept apart on purpose:
 *
 * <ul>
 *   <li><b>Background reads</b> — everything Editora runs on its own initiative (status and the gutter
 *       diff on each tab activation, log, blame, blob and ref lookups). They go through {@link #git} with
 *       the {@link GitSafety} overrides, so a repository's own configuration cannot make merely opening a
 *       file execute a program, and they keep a short kill timer.
 *   <li><b>User commands</b> — commit, checkout, reset, stash, fetch/pull/push and the like, through
 *       {@link #gitLogged}. They are the user's git exactly as configured (hooks included), are reported
 *       to the {@link CommandLog}, get long ceilings because killing one mid-update corrupts the working
 *       tree, and are left to finish if the window closes while one is running.
 * </ul>
 *
 * <p>Reads and local mutations share one serial lane; network commands have their own, so a slow remote
 * does not hold up status. Working-tree mutations are additionally serialised across both, and user commands
 * keep the order they were requested in: a fetch, pull or push is handed to the network lane only once the
 * local lane has reached it (so a push never overtakes the commit clicked just before it), and a local
 * mutation that finds a pull in the working tree waits behind it on the network lane instead of blocking
 * the reads.
 *
 * <p>Scope: status, gutter diff, staging, commit, branch switch/create, fetch/pull/push, the diff
 * viewer (blob {@link #showBlob}/{@link #log}), commit history ({@link #commitFiles}), inline blame
 * ({@link #blame}), and stash ({@link #stashList} + the mutation runners).
 */
public final class GitService {

    /** Combined refresh payload: the repo root, its status, the active file's gutter change map, a
     *  per-line hunk-text map (for the change-bar hover tooltip), git's reason when it refuses to work in the
     *  folder, and the multi-step operation (merge, rebase, …) the repository is in the middle of. */
    public record RepoState(
            Path root,
            Path diffFile,
            GitStatus status,
            Map<Integer, ChangeType> changes,
            Map<Integer, String> hunks,
            String refusal,
            GitOperation operation,
            List<DiffParser.Hunk> hunkList) {
        public static final RepoState NONE = new RepoState(null, null, GitStatus.NOT_A_REPO, Map.of(), Map.of());

        public RepoState {
            refusal = refusal == null ? "" : refusal;
            operation = operation == null ? GitOperation.NONE : operation;
            hunkList = hunkList == null ? List.of() : List.copyOf(hunkList);
        }

        public RepoState(
                Path root,
                Path diffFile,
                GitStatus status,
                Map<Integer, ChangeType> changes,
                Map<Integer, String> hunks,
                String refusal) {
            this(root, diffFile, status, changes, hunks, refusal, GitOperation.NONE, List.of());
        }

        public RepoState(
                Path root,
                Path diffFile,
                GitStatus status,
                Map<Integer, ChangeType> changes,
                Map<Integer, String> hunks,
                String refusal,
                GitOperation operation) {
            this(root, diffFile, status, changes, hunks, refusal, operation, List.of());
        }

        public RepoState(
                Path root,
                Path diffFile,
                GitStatus status,
                Map<Integer, ChangeType> changes,
                Map<Integer, String> hunks,
                String refusal,
                List<DiffParser.Hunk> hunkList) {
            this(root, diffFile, status, changes, hunks, refusal, GitOperation.NONE, hunkList);
        }

        public RepoState(
                Path root,
                Path diffFile,
                GitStatus status,
                Map<Integer, ChangeType> changes,
                Map<Integer, String> hunks) {
            this(root, diffFile, status, changes, hunks, "");
        }

        public RepoState(Path root, GitStatus status, Map<Integer, ChangeType> changes, Map<Integer, String> hunks) {
            this(root, null, status, changes, hunks, "");
        }

        /** This state with {@code operation} as the merge / rebase / cherry-pick / revert in progress. */
        public RepoState withOperation(GitOperation operation) {
            return new RepoState(root, diffFile, status, changes, hunks, refusal, operation);
        }

        /**
         * "There is a repository here, but git will not work in it": {@link #isRepo()} is false, as for
         * {@link #NONE}, and {@link #refusal()} carries git's own reason for the UI to show.
         */
        public static RepoState refused(String reason) {
            String text = reason == null || reason.isBlank() ? "git failed" : reason.strip();
            return new RepoState(null, null, GitStatus.NOT_A_REPO, Map.of(), Map.of(), text);
        }

        public boolean isRepo() {
            return root != null && status.isRepo();
        }

        /**
         * Whether git answered with an error other than "not a repository": dubious ownership (a WSL or
         * network mount, a docker volume, a checkout made with sudo), a bare repository, a path inside
         * {@code .git}, a corrupt index, a status too large to read. Never true together with {@link #isRepo()}.
         */
        public boolean refused() {
            return !refusal.isEmpty();
        }
    }

    /** A file's gutter diff vs HEAD: per-line {@link ChangeType} (bar color) + per-line hunk text (tooltip). */
    public record GitDiff(
            Map<Integer, ChangeType> changes, Map<Integer, String> hunks, List<DiffParser.Hunk> hunkList) {
        public static final GitDiff EMPTY = new GitDiff(Map.of(), Map.of(), List.of());

        public GitDiff(Map<Integer, ChangeType> changes, Map<Integer, String> hunks) {
            this(changes, hunks, List.of());
        }
    }

    /** Background reads (status, gutter diff, log, blame, blob lookups): a stuck probe must not wedge the lane. */
    static final Duration QUICK = Duration.ofSeconds(10);
    /**
     * Ceiling for a user-initiated commit / checkout / reset / stash / discard. These rewrite the working tree
     * or run the user's hooks (and possibly a GPG pinentry), so killing one after a few seconds leaves a
     * half-updated tree; the limit only exists so a truly hung child cannot hold the lane forever.
     */
    static final Duration MUTATION = Duration.ofMinutes(15);
    /** Clone / fetch / pull / push: a large repository over a slow link legitimately takes many minutes. */
    static final Duration NETWORK = Duration.ofMinutes(30);

    /** Reads and local mutations, strictly serial (a stale-diff check and its mutation share this lane). */
    private final ExecutorService exec = lane("git-service");
    /** Clone / fetch / pull / push, so a slow remote never queues status and gutter work behind it. */
    private final ExecutorService networkExec = lane("git-network");
    /** Serialises working-tree mutations across the two lanes ({@code pull} runs on the network lane). */
    private final ReentrantLock worktreeLock = new ReentrantLock();
    /** User commands currently running per lane; {@link #shutdown()} lets these finish instead of killing them. */
    private final AtomicInteger localCommands = new AtomicInteger();

    private final AtomicInteger networkCommands = new AtomicInteger();
    /** Set by {@link #shutdown()}: queued work is dropped, a running user command is left to complete. */
    private volatile boolean closing;

    private static ExecutorService lane(String name) {
        return Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * The outcome of {@code git --version} for one configured command: when it was learned, and how many
     * probes in a row have failed (0 once one succeeds).
     */
    record Availability(List<String> command, boolean available, long probedNanos, int failures) {}

    /** How long the first "git is unavailable" is believed; doubled per further failure. */
    public static final Duration UNAVAILABLE_RETRY = Duration.ofSeconds(5);
    /** The longest "git is unavailable" is believed before the command is tried again. */
    static final Duration UNAVAILABLE_RETRY_MAX = Duration.ofMinutes(1);

    /**
     * Whether {@code cached} still answers "is {@code command} available?" at {@code nowNanos}. A success is
     * good until the command changes. A failure is not: git may be installed while Editora runs, and one
     * {@code git --version} that was slow on a cold disk says nothing about the next. It is believed for
     * {@link #UNAVAILABLE_RETRY}, doubling with each consecutive failure up to {@link #UNAVAILABLE_RETRY_MAX},
     * so a machine without git pays for one failed process start now and then, not one per refresh. Pure.
     */
    static boolean availabilityCurrent(Availability cached, List<String> command, long nowNanos) {
        if (cached == null || !cached.command().equals(command)) {
            return false;
        }
        if (cached.available()) {
            return true;
        }
        long wait = UNAVAILABLE_RETRY.toNanos();
        for (int i = 1; i < cached.failures() && wait < UNAVAILABLE_RETRY_MAX.toNanos(); i++) {
            wait *= 2;
        }
        return nowNanos - cached.probedNanos() < Math.min(wait, UNAVAILABLE_RETRY_MAX.toNanos());
    }

    /**
     * null = not yet probed; cached after a {@code git --version}. It remembers the command it was probed
     * with: {@link #GIT_CMD} is shared by every window's service, so an answer for another command (the path
     * was changed in Settings, through a different window) is stale and is probed again. A negative answer
     * also expires ({@link #availabilityCurrent}).
     */
    private volatile Availability gitAvailable;

    /** {@link System#nanoTime()} outside tests; the expiry of every negative answer is measured on it. */
    private volatile java.util.function.LongSupplier nanoClock = System::nanoTime;

    /** Test seam: lets a test move past a retry or negative-cache interval without sleeping. */
    public void setNanoClockForTest(java.util.function.LongSupplier clock) {
        this.nanoClock = clock == null ? System::nanoTime : clock;
    }

    /** Local mutations waiting on the network lane behind a pull; see {@link #runWorktreeMutation}. */
    private final AtomicInteger parkedMutations = new AtomicInteger();

    /**
     * The configured git command tokens (default {@code ["git"]}; blank override resets). Static —
     * unlike {@code GitHubService} the low-level {@link #git} runner is static — which is also correct:
     * the setting is app-wide {@code SharedConfig} state, so every window's service must agree.
     */
    private static volatile List<String> GIT_CMD = List.of("git");

    /** Sets the git command/path (see {@link #commandTokens}); blank ⇒ resolve {@code git} on PATH. */
    public void setCommand(String command) {
        List<String> next = commandTokens(command);
        if (!next.equals(GIT_CMD)) {
            GIT_CMD = next; // each service re-probes: its cached availability names the command it was for
        }
    }

    /**
     * The argv prefix for a configured git command. A value that <em>is</em> an existing file is taken whole —
     * Settings has a Browse… button that writes the raw absolute path, and
     * {@code C:\Program Files\Git\cmd\git.exe} split at the space could never start. Anything else is
     * tokenized quote-aware, so a wrapper command with arguments (or a hand-quoted path) still works.
     * Public so the Doctor screen checks exactly the command this service runs.
     */
    public static List<String> commandTokens(String command) {
        if (command == null || command.isBlank()) {
            return List.of("git");
        }
        String raw = command.strip();
        try {
            if (Files.isRegularFile(Path.of(raw))) {
                return List.of(raw);
            }
        } catch (RuntimeException notAPath) {
            // Not a valid path on this platform: a command line.
        }
        List<String> tokens = com.editora.run.ProgramArgs.tokenize(raw);
        return tokens.isEmpty() ? List.of(raw) : List.copyOf(tokens);
    }

    /**
     * argv for a command the <em>user</em> asked for (commit, checkout, push, …): the configured git command
     * plus {@code args}, with the user's hooks and configuration fully in effect.
     */
    private static List<String> gitArgv(String... args) {
        List<String> cmd = new ArrayList<>(GIT_CMD.size() + args.length);
        cmd.addAll(GIT_CMD);
        cmd.addAll(List.of(args));
        return cmd;
    }

    /**
     * argv for a command Editora runs <em>on its own initiative</em> (status and gutter diff on every tab
     * activation, log, blame, blob lookups, ref listings). Merely opening a file must not execute a program
     * named by the folder's {@code .git/config}, so these carry {@link GitSafety#BACKGROUND_CONFIG} overrides
     * — see {@link GitSafety} for what is neutralised and what is deliberately left alone.
     */
    static List<String> backgroundArgv(String... args) {
        return GitSafety.backgroundArgv(GIT_CMD, args);
    }

    /**
     * Environment of every background read: lock-free, never prompting, never paging, and never fetching a
     * missing object on demand ({@link GitSafety#BACKGROUND_ENV}).
     */
    private static final Map<String, String> READ_ENV = GitSafety.BACKGROUND_ENV;

    /**
     * Environment of a user-initiated command. {@code GIT_TERMINAL_PROMPT=0} makes a missing credential fail
     * at once rather than wait on a terminal nobody can see (GUI askpass / credential helpers still work).
     * Git's diffstat output abbreviates the beginning of long paths to fit its assumed 80-column terminal, but
     * the output is captured for the Output tab where users can scroll sideways, so stat rows get enough room
     * to keep the paths intact and linkable. The command also runs in the user's own locale with only the
     * message language pinned — see {@link GitSafety#userEnv}.
     */
    private static final Map<String, String> USER_ENV = GitSafety.userEnv(System.getenv());

    /** Whether the probed git understands {@code --end-of-options} (2.24+); see {@link GitSafety}. */
    private static volatile boolean endOfOptions;

    /** The probed git's {@code --version} output ("" until probed); decides version-gated options. */
    private static volatile String gitVersion = "";

    /**
     * Where completed, <em>user-initiated</em> git commands are reported (the Output "Git" tab).
     * Volatile: installed from the FX thread, read on {@link #exec}.
     */
    private volatile CommandLog commandLog = CommandLog.none();
    /**
     * A resolved repository root and the {@code .git} entries seen on the way up to it
     * ({@link #gitMarkers}) when git answered. The same entries a moment later mean the answer still holds.
     */
    private record CachedRoot(Path root, List<Boolean> markers) {}

    /** Directory (absolute string) → repo root. Only successes live here; see {@link #negativeRoots}. */
    private final Map<String, CachedRoot> rootCache = new ConcurrentHashMap<>();

    /**
     * How long "not a repository" is believed for a directory. Long enough that a burst of refreshes in a
     * plain folder costs one {@code rev-parse}, short enough that a {@code git init} or clone made in a
     * terminal is seen by the refresh that runs when the window regains focus.
     */
    static final Duration NOT_A_REPO_TTL = Duration.ofSeconds(2);

    /**
     * How long "git refuses to work here" is believed. The reasons (dubious ownership, a bare repository, a
     * path inside {@code .git}) do not go away by themselves, and re-running the failing probe on every tab
     * switch bought nothing; the fix is typed in a terminal, so the limit stays within a focus change or two.
     */
    public static final Duration REFUSED_TTL = Duration.ofSeconds(5);

    /** A negative answer for a directory: when, and git's reason if it was a refusal ("" = not a repository). */
    private record NegativeRoot(long sinceNanos, String refusal) {}

    /** Directory (absolute string) → git's last "not a repository" or refusal, each believed only briefly. */
    private final Map<String, NegativeRoot> negativeRoots = new ConcurrentHashMap<>();
    /** Bumped per {@link #refresh}; a stale background result is dropped instead of posted to the UI. */
    private final AtomicLong refreshGen = new AtomicLong();

    // --- detection -------------------------------------------------------------------------------

    /**
     * Whether {@code git} can be run. Probed on first use and cached; "unavailable" is re-probed after a
     * while ({@link #availabilityCurrent}), though never from the JavaFX thread, which is answered from the
     * last probe.
     */
    public boolean gitAvailable() {
        List<String> command = GIT_CMD;
        Availability cached = gitAvailable;
        if (availabilityCurrent(cached, command, nanoClock.getAsLong())) {
            return cached.available();
        }
        if (cached != null && cached.command().equals(command) && onFxThread()) {
            return cached.available(); // expired, but a probe is a process: the next lane task renews it
        }
        probeVersion(command);
        Availability probed = gitAvailable;
        return probed != null && probed.available();
    }

    private static boolean onFxThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (RuntimeException noToolkit) {
            return false;
        }
    }

    /** Runs {@code <command> --version}, records the availability for that command, returns the output. */
    private String probeVersion(List<String> command) {
        String version = "";
        boolean ok = false;
        try {
            List<String> argv = new ArrayList<>(command);
            argv.add("--version");
            ProcessRunner.Result r = ProcessRunner.run(null, QUICK, argv, READ_ENV);
            ok = r.ok();
            if (ok) {
                version = r.out().strip();
                endOfOptions = GitSafety.supportsEndOfOptions(r.out());
                gitVersion = version;
            }
        } catch (RuntimeException e) {
            ok = false;
        }
        Availability previous = gitAvailable;
        int failures = ok ? 0 : (previous != null && previous.command().equals(command) ? previous.failures() : 0) + 1;
        gitAvailable = new Availability(command, ok, nanoClock.getAsLong(), failures);
        return version;
    }

    /**
     * Probes {@code git --version} off the FX thread and posts the version string (e.g.
     * {@code "git version 2.54.0"}) on the FX thread, or {@code ""} when git isn't on PATH. Also
     * primes the {@link #gitAvailable()} cache.
     */
    public void version(Consumer<String> onResult) {
        submit(exec, () -> {
            String posted = probeVersion(GIT_CMD);
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    // --- the main refresh ------------------------------------------------------------------------

    /**
     * Resolves the repo root for {@code contextPath}, then (if in a repo) gathers status and the
     * gutter change map for {@code diffFile}. Runs entirely off the FX thread; {@code onResult} is
     * invoked on the FX thread with the latest result only (older in-flight refreshes are dropped).
     *
     * @param contextPath a file or directory used to locate the repo (may be {@code null})
     * @param diffFile    the file whose HEAD diff feeds the gutter (may be {@code null} to skip)
     */
    public void refresh(Path contextPath, Path diffFile, Consumer<RepoState> onResult) {
        long gen = refreshGen.incrementAndGet();
        submit(exec, () -> {
            if (gen != refreshGen.get()) {
                return; // superseded while queued: do not run status + diff only to throw the result away
            }
            RepoState state = computeRefresh(contextPath, diffFile, true);
            if (state == UNANSWERED) {
                return; // status timed out: the window keeps showing the last state it was given
            }
            if (gen == refreshGen.get()) {
                Platform.runLater(() -> {
                    if (gen == refreshGen.get()) {
                        onResult.accept(state);
                    }
                });
            }
        });
    }

    /** Invalidates a computed/queued UI refresh when the owning window context is cleared. */
    public void invalidateRefreshes() {
        refreshGen.incrementAndGet();
    }

    /**
     * Like {@link #refresh} but with no gutter diff and <em>no generation guard</em> — the callback always
     * fires, so a caller blocking on the result (the MCP bridge) can't be starved by a concurrent UI
     * refresh. Posted on the FX thread like every other callback.
     */
    public void status(Path contextPath, Consumer<RepoState> onResult) {
        submit(exec, () -> {
            RepoState computed = computeRefresh(contextPath, null, false);
            RepoState state = computed == UNANSWERED ? RepoState.NONE : computed;
            Platform.runLater(() -> onResult.accept(state));
        });
    }

    /**
     * "{@code git status} did not answer" — as opposed to {@link RepoState#NONE}, "this is not a repository".
     * Reporting a timeout as NONE made the window drop its branch, change bars and tree colouring and
     * declare the folder not a repository, until the next trigger started another status that timed out too.
     */
    private static final RepoState UNANSWERED = new RepoState(null, null, GitStatus.NOT_A_REPO, Map.of(), Map.of());

    private final GitStatusBackoff statusBackoff = new GitStatusBackoff();

    /** The limit for one background {@code git status}; {@link #QUICK} outside tests. */
    private volatile Duration statusTimeout = QUICK;

    /** Test seam: a status that "takes too long" without the test waiting {@link #QUICK}. */
    public void setStatusTimeoutForTest(Duration timeout) {
        this.statusTimeout = timeout;
    }

    /**
     * @param backOff skip the status (answering {@link #UNANSWERED}) while the repository is in the wait that
     *     follows a timeout; false for a caller that needs an answer whatever it costs
     */
    private RepoState computeRefresh(Path contextPath, Path diffFile, boolean backOff) {
        if (!gitAvailable() || contextPath == null) {
            return RepoState.NONE;
        }
        RootLookup lookup = lookupRoot(contextPath);
        Path root = lookup.root();
        if (root == null) {
            return lookup.refusal().isEmpty() ? RepoState.NONE : RepoState.refused(lookup.refusal());
        }
        if (backOff && statusBackoff.waiting(root, System.nanoTime())) {
            return UNANSWERED;
        }
        ProcessRunner.Result st = git(root, statusTimeout, "status", "--porcelain=v2", "--branch");
        if (st.timedOut()) {
            statusBackoff.timedOut(root, System.nanoTime());
            return UNANSWERED;
        }
        if (!st.ok()) {
            // The root resolved, so this is a repository git cannot report on (a corrupt index, a status too
            // large to capture): say why instead of presenting the folder as not a repository — or, for a
            // cut-off capture, presenting the files that happened to fit as the whole change list.
            String reason = refusalReason(st);
            return reason.isEmpty() ? RepoState.NONE : RepoState.refused(reason);
        }
        statusBackoff.succeeded(root);
        GitStatus status = StatusParser.parse(st.out());
        GitDiff diff = diffFile != null ? diffHead(root, diffFile) : GitDiff.EMPTY;
        return new RepoState(
                root, diffFile, status, diff.changes(), diff.hunks(), "", operationIn(root), diff.hunkList());
    }

    // --- the operation in progress (merge / rebase / cherry-pick / revert) -------------------------

    /**
     * Repository root → the git directory of <em>that work tree</em>, where git keeps the state of a merge,
     * rebase, cherry-pick or revert. Learned from the same {@code rev-parse} that resolves the root
     * ({@link #lookupRoot}) — a linked work tree's is {@code <main>/.git/worktrees/<name>}, a submodule's is
     * under the superproject — and then kept: every status refresh reads the operation with a few
     * {@code stat} calls and no process.
     */
    private final Map<Path, Path> gitDirs = new ConcurrentHashMap<>();

    /**
     * Records {@code root}'s git directory from a {@code rev-parse --git-dir} answer given in {@code cwd}:
     * git prints it relative to the directory it ran in ({@code .git}) or absolute.
     */
    private void rememberGitDir(Path root, Path cwd, String answer) {
        if (answer.isEmpty()) {
            return;
        }
        try {
            gitDirs.put(root, cwd.resolve(answer).normalize());
        } catch (RuntimeException notAPath) {
            gitDirs.remove(root);
        }
    }

    /**
     * The operation {@code root} is in the middle of, read from its git directory's state files
     * ({@link GitOperation#detect}). {@link GitOperation#NONE} when the directory cannot be resolved.
     */
    private GitOperation operationIn(Path root) {
        Path gitDir = gitDirs.get(root);
        if (gitDir == null || !Files.isDirectory(gitDir)) {
            // Not learned with the root (it was cached before, or the directory has moved): ask once.
            gitDirs.remove(root);
            ProcessRunner.Result r = git(root, QUICK, "rev-parse", "--git-dir");
            rememberGitDir(root, root, r.ok() ? r.out().strip() : "");
            gitDir = gitDirs.get(root);
        }
        return GitOperation.detect(gitDir);
    }

    private GitDiff diffHead(Path root, Path file) {
        // -U0: no context lines, so hunk headers map cleanly to changed line ranges + tooltip bodies.
        ProcessRunner.Result r = git(
                root,
                QUICK,
                GitSafety.LITERAL_PATHSPECS,
                "diff",
                "--no-color",
                "-U0",
                "HEAD",
                "--",
                file.toAbsolutePath().toString());
        if (!r.ok()) {
            return GitDiff.EMPTY; // untracked / unmerged / new repo with no HEAD: no bars
        }
        return new GitDiff(
                DiffParser.parseToLineMap(r.out()),
                DiffParser.parseToHunkText(r.out()),
                DiffParser.parseHunks(r.out()));
    }

    /** Diffs a single file against {@code HEAD} for the gutter; posts the change + hunk maps on the FX thread. */
    public void diff(Path root, Path file, Consumer<GitDiff> onResult) {
        submit(exec, () -> {
            GitDiff diff = gitAvailable() && root != null && file != null ? diffHead(root, file) : GitDiff.EMPTY;
            Platform.runLater(() -> onResult.accept(diff));
        });
    }

    /**
     * The hunks of {@code file}'s <em>unstaged</em> changes (working tree vs index), for staging one hunk
     * from the editor; posts on the FX thread. {@code null} when git could not answer (an unmerged path
     * included), an empty list when nothing is unstaged.
     */
    public void unstagedHunks(Path root, Path file, Consumer<List<DiffParser.Hunk>> onResult) {
        submit(exec, () -> {
            List<DiffParser.Hunk> hunks = null;
            if (gitAvailable() && root != null && file != null) {
                ProcessRunner.Result r = git(
                        root,
                        QUICK,
                        GitSafety.LITERAL_PATHSPECS,
                        "diff",
                        "--no-color",
                        "-U0",
                        "--",
                        file.toAbsolutePath().toString());
                // An unmerged path answers with a combined diff ("@@@"), which is not a hunk to stage.
                hunks = r.ok() && !r.out().contains("\n@@@ ") ? DiffParser.parseHunks(r.out()) : null;
            }
            List<DiffParser.Hunk> result = hunks;
            Platform.runLater(() -> onResult.accept(result));
        });
    }

    // --- diff viewer: blob content + history -----------------------------------------------------

    /** Raw blob lookup result. {@code found} distinguishes a valid empty blob from a missing stage/spec. */
    public record BlobResult(boolean found, byte[] bytes, boolean truncated) {
        public BlobResult(boolean found, byte[] bytes) {
            this(found, bytes, false);
        }

        public BlobResult {
            bytes = bytes == null ? new byte[0] : bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    /**
     * Fetches a blob's raw bytes while preserving whether the spec existed. This matters for merge-index
     * stages: an empty file is a valid ancestor/side, whereas a missing {@code :1/:2/:3} stage means the
     * file is not available for an ancestor-aware merge.
     */
    public void showBlob(Path root, String spec, Consumer<BlobResult> onResult) {
        submit(exec, () -> {
            BlobResult result = gitAvailable() && root != null ? blobNow(root, spec) : new BlobResult(false, null);
            BlobResult posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * Fetches a blob's <em>raw bytes</em> via {@code git show <spec>} (e.g. {@code HEAD:rel/path} for the
     * committed version, {@code :rel/path} for the staged/index version, {@code <ref>:rel/path} for any
     * commit), so a caller can decode it with the file's real charset rather than force-decoding as UTF-8
     * (which mojibakes a Latin-1/UTF-16 tracked file). Posts on the FX thread; empty bytes when the spec
     * doesn't exist (a new/untracked file has no such blob) or on failure — callers treat empty as "empty side".
     */
    public void showBytes(Path root, String spec, Consumer<byte[]> onResult) {
        showBlob(root, spec, result -> onResult.accept(result.found() ? result.bytes() : new byte[0]));
    }

    /** Changed files below a folder when a Git tree is compared with the current working tree. */
    public record WorkingTreeDiff(List<CommitFile> files, boolean truncated, String error) {
        public WorkingTreeDiff {
            files = List.copyOf(files);
            error = error == null ? "" : error;
        }

        public boolean ok() {
            return error.isEmpty();
        }
    }

    /** Maximum changed paths admitted to one folder review. */
    public static final int MAX_WORKING_TREE_DIFF_FILES = 20_000;

    /**
     * Lists tracked and untracked changes below {@code folder} between {@code ref} and the current working
     * tree. Ignored files stay excluded. Rename detection is deliberately disabled so both old and new
     * locations remain independently reviewable in a folder-scoped result.
     */
    public void workingTreeDiff(Path root, Path folder, String ref, Consumer<WorkingTreeDiff> onResult) {
        submit(exec, () -> {
            WorkingTreeDiff result = new WorkingTreeDiff(List.of(), false, "Git is not available");
            String relative = repoRelative(root, folder);
            if (gitAvailable() && root != null && relative != null && !GitSafety.isSafeRevision(ref)) {
                // A ref name is repository data: one beginning with "-" would be read as an option.
                result = new WorkingTreeDiff(List.of(), false, "Unsafe revision name: " + ref);
            } else if (gitAvailable() && root != null && relative != null) {
                List<String> diffArgs = new ArrayList<>(
                        List.of(GitSafety.LITERAL_PATHSPECS, "diff", "--name-status", "-z", "--no-renames"));
                diffArgs.addAll(GitSafety.revisionArgs(endOfOptions, ref));
                diffArgs.add("--");
                List<String> untrackedArgs = new ArrayList<>(
                        List.of(GitSafety.LITERAL_PATHSPECS, "ls-files", "--others", "--exclude-standard", "-z", "--"));
                if (!relative.isEmpty()) {
                    diffArgs.add(relative);
                    untrackedArgs.add(relative);
                }
                ProcessRunner.Result changed = git(root, QUICK, diffArgs.toArray(String[]::new));
                if (!changed.ok()) {
                    result = new WorkingTreeDiff(List.of(), false, changed.message());
                } else {
                    ProcessRunner.Result untracked = git(root, QUICK, untrackedArgs.toArray(String[]::new));
                    if (!untracked.ok()) {
                        result = new WorkingTreeDiff(List.of(), false, untracked.message());
                    } else {
                        result = mergeWorkingTreeDiff(changed.out(), untracked.out(), MAX_WORKING_TREE_DIFF_FILES);
                    }
                }
            }
            WorkingTreeDiff posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** Pure merge of NUL-delimited {@code diff --name-status} and untracked-file output. */
    static WorkingTreeDiff mergeWorkingTreeDiff(String changed, String untracked, int maxFiles) {
        Map<String, CommitFile> byPath = new LinkedHashMap<>();
        for (CommitFile file : parseNameStatusZ(changed)) {
            byPath.put(file.path(), file);
        }
        for (String path : nulTokens(untracked)) {
            CommitFile existing = byPath.get(path);
            // A path deleted relative to the selected ref can simultaneously exist as an untracked working
            // file. It is a modification between the two snapshots, not two one-sided entries.
            byPath.put(path, new CommitFile(existing != null && existing.status() == 'D' ? 'M' : 'A', path, null));
        }
        List<CommitFile> sorted = byPath.values().stream()
                .sorted(Comparator.comparing(CommitFile::path))
                .toList();
        int limit = Math.max(1, maxFiles);
        boolean truncated = sorted.size() > limit;
        return new WorkingTreeDiff(truncated ? sorted.subList(0, limit) : sorted, truncated, "");
    }

    /** Parses the NUL-safe form emitted by {@code git diff --name-status -z}. */
    static List<CommitFile> parseNameStatusZ(String out) {
        List<String> fields = nulTokens(out);
        List<CommitFile> files = new ArrayList<>();
        for (int i = 0; i < fields.size(); ) {
            String statusText = fields.get(i++);
            if (statusText.isEmpty() || i >= fields.size()) {
                break;
            }
            char status = statusText.charAt(0);
            String firstPath = fields.get(i++);
            if ((status == 'R' || status == 'C') && i < fields.size()) {
                files.add(new CommitFile(status, fields.get(i++), firstPath));
            } else {
                files.add(new CommitFile(status, firstPath, null));
            }
        }
        return files;
    }

    private static List<String> nulTokens(String out) {
        if (out == null || out.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(out.split("\u0000", -1))
                .filter(token -> !token.isEmpty())
                .toList();
    }

    /** One commit from the log, for the "diff against commit" picker. */
    public record Commit(String hash, String shortHash, String subject, String author, String date) {}

    /**
     * Lists up to {@code max} commits touching {@code file} (most recent first) via one {@code git log},
     * tab-separated fields parsed by the pure {@link #parseLog}. Posts on the FX thread.
     */
    public void log(Path root, Path file, int max, Consumer<List<Commit>> onResult) {
        submit(exec, () -> {
            List<Commit> commits = List.of();
            if (gitAvailable() && root != null) {
                List<String> args = new ArrayList<>(List.of(
                        GitSafety.LITERAL_PATHSPECS,
                        "log",
                        "--no-color",
                        "--pretty=format:%H%x09%h%x09%an%x09%ad%x09%s",
                        "--date=short",
                        "-n",
                        String.valueOf(max)));
                if (file != null) {
                    args.add("--");
                    args.add(file.toAbsolutePath().toString());
                }
                ProcessRunner.Result r = git(root, QUICK, args.toArray(new String[0]));
                if (r.ok()) {
                    commits = parseLog(r.out());
                }
            }
            List<Commit> posted = commits;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * The Git Log's rows: up to {@code max} commits (of {@code file} when given) with author time and ref
     * decorations, parsed by the pure {@link GitLog#parse}. One extra commit is requested so the page can
     * say whether the history was cut off. Posts on the FX thread.
     */
    public void logPage(Path root, Path file, int max, Consumer<GitLog.Page> onResult) {
        submit(exec, () -> {
            GitLog.Page page = GitLog.Page.EMPTY;
            if (gitAvailable() && root != null) {
                List<String> args = new ArrayList<>(List.of(
                        GitSafety.LITERAL_PATHSPECS,
                        "log",
                        "--no-color",
                        "--decorate=full",
                        GitLog.FORMAT,
                        "--date=short",
                        "-n",
                        String.valueOf(max + 1)));
                if (file != null) {
                    args.add("--");
                    args.add(file.toAbsolutePath().toString());
                }
                ProcessRunner.Result r = git(root, QUICK, args.toArray(new String[0]));
                if (r.ok()) {
                    page = GitLog.parse(r.out(), max);
                }
            }
            GitLog.Page posted = page;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** Parses {@code %H\t%h\t%an\t%ad\t%s} log lines into {@link Commit}s. Pure — unit-tested. */
    static List<Commit> parseLog(String out) {
        List<Commit> commits = new ArrayList<>();
        for (String line : out.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            String[] f = line.split("\t", 5);
            if (f.length >= 5) {
                commits.add(new Commit(f[0].strip(), f[1].strip(), f[4], f[2], f[3].strip()));
            }
        }
        return commits;
    }

    // --- blame / commit files / stash (history & annotate) ---------------------------------------

    /**
     * Annotates every line of {@code file} via {@code git blame --porcelain}, parsed by the pure
     * {@link BlameParser}. ({@code --line-porcelain} repeats the whole commit description for every line —
     * some 300 bytes each — and overran the capture limit on a file of a few tens of thousands of lines.) Posts the per-line list (file order) on the FX thread, or an empty list when
     * git is absent / the file isn't tracked.
     */
    public void blame(Path root, Path file, Consumer<List<BlameParser.BlameLine>> onResult) {
        submit(exec, () -> {
            List<BlameParser.BlameLine> posted = computeBlame(root, file);
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * {@link #blame} for the inline annotations, which are re-requested after every status refresh: a request
     * still queued when a newer one arrives is dropped without running (its callback is never invoked), and
     * an unchanged file at an unchanged {@code HEAD} is answered from the cache.
     */
    public void blameLatest(Path root, Path file, Consumer<List<BlameParser.BlameLine>> onResult) {
        long gen = blameGen.incrementAndGet();
        submit(exec, () -> {
            if (gen != blameGen.get()) {
                return; // superseded while queued behind status/diff work
            }
            List<BlameParser.BlameLine> posted = computeBlame(root, file);
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * Blame of {@code path} (repo-relative) <em>as of</em> {@code revision} — the annotations of a read-only
     * "file at this commit" tab. Same supersession rule as {@link #blameLatest} (one annotated buffer is on
     * screen at a time); a commit never changes, so the result is cached for as long as the options hold.
     */
    public void blameRevisionLatest(
            Path root, String revision, String path, Consumer<List<BlameParser.BlameLine>> onResult) {
        long gen = blameGen.incrementAndGet();
        submit(exec, () -> {
            if (gen != blameGen.get()) {
                return;
            }
            List<BlameParser.BlameLine> posted = computeRevisionBlame(root, revision, path);
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    private final AtomicLong blameGen = new AtomicLong();

    /** How this window's blame attributes lines; read on the git lane, set from the FX thread. */
    private volatile BlameOptions blameOptions = BlameOptions.NONE;

    /** Sets {@code -w} / {@code -M -C} for every later blame of this service (a per-window toggle). */
    public void setBlameOptions(BlameOptions options) {
        blameOptions = options == null ? BlameOptions.NONE : options;
    }

    public BlameOptions blameOptions() {
        return blameOptions;
    }

    /**
     * What one blame run was computed from: its result is good for as long as none of these change.
     * {@code source} is the working file's path, or {@code <revision>:<path>} for a blame at a commit (then
     * {@code head}, {@code modifiedMillis} and {@code size} do not apply). {@code ignoreRevs} describes the
     * ignore-revs files in play — their paths, sizes and modification times.
     */
    record BlameKey(
            String head, String source, long modifiedMillis, long size, BlameOptions options, String ignoreRevs) {}

    private record CachedBlame(BlameKey key, List<BlameParser.BlameLine> lines) {}

    /** The last few files annotated, by {@link BlameKey#source}. Touched only on the git lane. */
    private final Map<String, CachedBlame> blameCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedBlame> eldest) {
            return size() > BLAME_CACHE_FILES;
        }
    };

    static final int BLAME_CACHE_FILES = 8;

    private final AtomicInteger blameRuns = new AtomicInteger();

    /** How many {@code git blame} processes were actually started; read by tests. */
    public int blameRunsForTest() {
        return blameRuns.get();
    }

    /**
     * Why the last blame produced nothing because of an ignore-revs file (git's own message), or {@code ""}.
     * A configured {@code blame.ignoreRevsFile} that is missing or malformed makes {@code git blame} fail
     * outright, in a terminal too; the annotations would otherwise just not appear, with nothing to say why.
     */
    public String blameIgnoreRevsProblem() {
        return blameIgnoreRevsProblem;
    }

    private volatile String blameIgnoreRevsProblem = "";

    private List<BlameParser.BlameLine> computeBlame(Path root, Path file) {
        if (!gitAvailable() || root == null || file == null) {
            return List.of();
        }
        BlameOptions options = blameOptions;
        BlameIgnoreRevs.Plan plan = ignoreRevsPlan(root);
        // Blame of the working file depends on the commit graph (HEAD) and on the file's bytes. Asking for
        // HEAD is one short process; the blame it saves walks the file's whole history.
        BlameKey key = blameKey(root, file, options, plan);
        Path abs = file.toAbsolutePath();
        List<BlameParser.BlameLine> cached = cachedBlame(abs.toString(), key);
        if (cached != null) {
            return cached;
        }
        ProcessRunner.Result r = runBlame(root, options, plan, List.of("--", abs.toString()));
        if (!r.ok()) {
            return List.of(); // includes a capture that was cut off: never parsed, never cached
        }
        List<BlameParser.BlameLine> lines = BlameParser.parse(r.out());
        // Cache only when the file is the same after the run as before it: a save in between would store
        // the old bytes' blame under a key that no longer describes them.
        if (key != null && key.equals(blameKey(root, file, options, plan))) {
            synchronized (blameCache) {
                blameCache.put(abs.toString(), new CachedBlame(key, lines));
            }
        }
        return lines;
    }

    private List<BlameParser.BlameLine> computeRevisionBlame(Path root, String revision, String path) {
        if (!gitAvailable() || root == null || path == null || !GitSafety.isSafeRevision(revision)) {
            return List.of();
        }
        BlameOptions options = blameOptions;
        BlameIgnoreRevs.Plan plan = ignoreRevsPlan(root);
        String source = revision + ":" + path;
        BlameKey key = new BlameKey("", root + "\u0000" + source, 0L, 0L, options, ignoreRevsSignature(plan));
        List<BlameParser.BlameLine> cached = cachedBlame(key.source(), key);
        if (cached != null) {
            return cached;
        }
        // The revision goes before "--": after it git would read it as a second path.
        ProcessRunner.Result r = runBlame(root, options, plan, List.of(revision, "--", path));
        if (!r.ok()) {
            return List.of();
        }
        List<BlameParser.BlameLine> lines = BlameParser.parse(r.out());
        synchronized (blameCache) {
            blameCache.put(key.source(), new CachedBlame(key, lines));
        }
        return lines;
    }

    private List<BlameParser.BlameLine> cachedBlame(String source, BlameKey key) {
        CachedBlame cached;
        synchronized (blameCache) {
            cached = blameCache.get(source);
        }
        return key != null && cached != null && cached.key().equals(key) ? cached.lines() : null;
    }

    /**
     * One {@code git blame --porcelain} with the options and the ignore-revs {@code plan}. A run that fails
     * <em>because of</em> an ignore-revs file — one we passed whose contents are not object names, or the
     * global one git opened by itself — is repeated without any that can be left out, so a broken list costs
     * the user the "ignore" and not the whole annotation column.
     */
    private ProcessRunner.Result runBlame(
            Path root, BlameOptions options, BlameIgnoreRevs.Plan plan, List<String> target) {
        ProcessRunner.Result r = blameOnce(root, options, plan, target);
        if (!r.ok() && BlameIgnoreRevs.isIgnoreRevsFailure(r.err())) {
            blameIgnoreRevsProblem = r.err().strip();
            BlameIgnoreRevs.Plan bare = new BlameIgnoreRevs.Plan(List.of(), true, null);
            // A file the repository's own config names cannot be left out: the retry would fail the same way.
            return plan.unreadable() != null || plan.equals(bare) ? r : blameOnce(root, options, bare, target);
        }
        blameIgnoreRevsProblem = "";
        return r;
    }

    private ProcessRunner.Result blameOnce(
            Path root, BlameOptions options, BlameIgnoreRevs.Plan plan, List<String> target) {
        List<String> args = new ArrayList<>(List.of(GitSafety.LITERAL_PATHSPECS, "blame", "--porcelain"));
        args.addAll(options.args());
        args.addAll(plan.args());
        args.addAll(target);
        blameRuns.incrementAndGet();
        Map<String, String> env = plan.withoutGlobalConfig() ? envWithoutGlobalIgnoreRevs(root) : READ_ENV;
        return completeOrFailed(ProcessRunner.run(root, QUICK, backgroundArgv(args.toArray(String[]::new)), env));
    }

    /** How long a repository's {@code blame.ignoreRevsFile} configuration is believed before it is re-read. */
    static final Duration IGNORE_REVS_CONFIG_TTL = Duration.ofSeconds(30);

    private record CachedIgnoreRevs(List<BlameIgnoreRevs.Configured> configured, long readNanos) {}

    /** Repository root → its configured ignore-revs files. Blame is re-requested after every status refresh. */
    private final Map<Path, CachedIgnoreRevs> ignoreRevsConfig = new ConcurrentHashMap<>();

    /** The ignore-revs plan for {@code root}: the configuration (briefly cached) against the files as they are now. */
    private BlameIgnoreRevs.Plan ignoreRevsPlan(Path root) {
        long now = System.nanoTime();
        CachedIgnoreRevs cached = ignoreRevsConfig.get(root);
        if (cached == null || now - cached.readNanos() > IGNORE_REVS_CONFIG_TTL.toNanos()) {
            // Exit code 1 is "not set". --show-scope needs Git 2.26; an older git fails, which reads as unset.
            ProcessRunner.Result r =
                    git(root, QUICK, "config", "--show-scope", "--get-all", "--path", "-z", "blame.ignoreRevsFile");
            cached = new CachedIgnoreRevs(r.ok() ? BlameIgnoreRevs.parseScoped(r.out()) : List.of(), now);
            ignoreRevsConfig.put(root, cached);
        }
        return BlameIgnoreRevs.plan(cached.configured(), root, p -> Files.isRegularFile(p) && Files.isReadable(p));
    }

    /** The part of a blame cache key that changes when an ignore-revs file in play is edited. */
    private static String ignoreRevsSignature(BlameIgnoreRevs.Plan plan) {
        StringBuilder sb = new StringBuilder(plan.withoutGlobalConfig() ? "g" : "");
        for (String file : plan.files()) {
            try {
                java.nio.file.attribute.BasicFileAttributes attrs =
                        Files.readAttributes(Path.of(file), java.nio.file.attribute.BasicFileAttributes.class);
                sb.append('|').append(file).append(':').append(attrs.size()).append(':');
                sb.append(attrs.lastModifiedTime().toMillis());
            } catch (IOException | RuntimeException gone) {
                sb.append('|').append(file).append(":?");
            }
        }
        return sb.toString();
    }

    /**
     * {@link #READ_ENV} for one blame that must not see the user's global {@code blame.ignoreRevsFile}:
     * {@code GIT_CONFIG_GLOBAL} points at a copy of the global configuration without that key
     * ({@link BlameIgnoreRevs#globalConfigWithoutIgnoreRevs}). Git older than 2.32 ignores the variable;
     * blame then fails as it does in a terminal.
     */
    private Map<String, String> envWithoutGlobalIgnoreRevs(Path root) {
        try {
            ProcessRunner.Result global = git(root, QUICK, "config", "--global", "--includes", "-z", "--list");
            Path config = Files.createTempFile("editora-git-global", ".config");
            config.toFile().deleteOnExit();
            Files.writeString(
                    config,
                    BlameIgnoreRevs.globalConfigWithoutIgnoreRevs(
                            global.ok() ? global.out() : "", root.toString().replace('\\', '/')));
            Map<String, String> env = new LinkedHashMap<>(READ_ENV);
            env.put("GIT_CONFIG_GLOBAL", config.toString());
            return env;
        } catch (IOException | RuntimeException e) {
            return READ_ENV; // blame then fails the way it does in a terminal
        }
    }

    private BlameKey blameKey(Path root, Path file, BlameOptions options, BlameIgnoreRevs.Plan plan) {
        try {
            ProcessRunner.Result head = git(root, QUICK, "rev-parse", "--verify", "HEAD");
            if (!head.ok()) {
                return null; // no commits yet, or git did not answer: do not cache
            }
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class);
            return new BlameKey(
                    head.out().strip(),
                    file.toAbsolutePath().toString(),
                    attrs.lastModifiedTime().toMillis(),
                    attrs.size(),
                    options,
                    ignoreRevsSignature(plan));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** One file changed by a commit: its name-status letter, path, and (for rename/copy) the original path. */
    public record CommitFile(char status, String path, String origPath) {}

    /**
     * Lists the files a commit changed relative to its <em>first parent</em> — the same pair the commit-file
     * diff then shows — parsed by the pure {@link #parseNameStatusZ}. {@code -z} keeps non-ASCII and unusual
     * names verbatim (the default output C-quotes them, and the quoted string is not a path); {@code --root}
     * lists a root commit's files as additions. A merge has no single-parent diff of its own, so when the
     * plain form lists nothing the commit is compared with {@code <hash>^1} explicitly, which works on every
     * Git version. Posts on the FX thread.
     */
    public void commitFiles(Path root, String hash, Consumer<List<CommitFile>> onResult) {
        submit(exec, () -> {
            List<CommitFile> files = List.of();
            if (gitAvailable() && root != null && GitSafety.isSafeRevision(hash)) {
                files = commitFilesNow(root, hash);
            }
            List<CommitFile> posted = files;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    private static List<CommitFile> commitFilesNow(Path root, String hash) {
        List<String> single =
                new ArrayList<>(List.of("diff-tree", "--no-commit-id", "--name-status", "-z", "-r", "-M", "--root"));
        single.addAll(GitSafety.revisionArgs(endOfOptions, hash));
        ProcessRunner.Result r = git(root, QUICK, single.toArray(String[]::new));
        if (!r.ok()) {
            return List.of();
        }
        if (!r.out().isEmpty()) {
            return parseNameStatusZ(r.out());
        }
        List<String> firstParent = new ArrayList<>(List.of("diff-tree", "--name-status", "-z", "-r", "-M"));
        firstParent.addAll(GitSafety.revisionArgs(endOfOptions, hash + "^1", hash));
        ProcessRunner.Result merge = git(root, QUICK, firstParent.toArray(String[]::new));
        return merge.ok() ? parseNameStatusZ(merge.out()) : List.of();
    }

    /**
     * Lists the stashes via {@code git stash list}, parsed by the pure {@link StashParser}: each with its
     * commit and time, so a stash can be read by a name that does not move ({@link StashParser.StashEntry}).
     */
    public void stashList(Path root, Consumer<List<StashParser.StashEntry>> onResult) {
        submit(exec, () -> {
            List<StashParser.StashEntry> list = List.of();
            if (gitAvailable() && root != null) {
                ProcessRunner.Result r = git(root, QUICK, "stash", "list", StashParser.DETAILED_FORMAT);
                if (r.ok()) {
                    list = StashParser.parseDetailed(r.out());
                }
            }
            List<StashParser.StashEntry> posted = list;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * The files a stash holds: its tracked changes against the commit it was made on (the stash commit's
     * first parent), then — status {@code '?'} — the untracked files of a stash made with
     * {@code --include-untracked}, which live in a third parent whose tree is nothing but those files. Built
     * from plumbing rather than {@code stash show --include-untracked}, which needs Git 2.32. {@code stash}
     * is the stash commit (or a {@code stash@{N}} ref). Posts on the FX thread; empty when it cannot be read.
     */
    public void stashFiles(Path root, String stash, Consumer<List<CommitFile>> onResult) {
        submit(exec, () -> {
            List<CommitFile> files = new ArrayList<>();
            if (gitAvailable() && root != null && GitSafety.isSafeRevision(stash)) {
                List<String> diff = new ArrayList<>(List.of("diff-tree", "--name-status", "-z", "-r", "-M"));
                diff.addAll(GitSafety.revisionArgs(endOfOptions, stash + "^1", stash));
                ProcessRunner.Result tracked = git(root, QUICK, diff.toArray(String[]::new));
                if (tracked.ok()) {
                    files.addAll(parseNameStatusZ(tracked.out()));
                }
                List<String> tree = new ArrayList<>(List.of("ls-tree", "-r", "-z", "--name-only"));
                tree.addAll(GitSafety.revisionArgs(endOfOptions, stash + "^3"));
                ProcessRunner.Result untracked = git(root, QUICK, tree.toArray(String[]::new));
                if (untracked.ok()) { // fails when there is no third parent: a stash without untracked files
                    for (String path : nulTokens(untracked.out())) {
                        files.add(new CommitFile('?', path, null));
                    }
                }
            }
            List<CommitFile> posted = List.copyOf(files);
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** The {@code err} of a stash command refused because {@code stash@{N}} no longer names the listed stash. */
    public static final String STASH_MOVED = "the stash list changed";

    /** The {@code err} of a staged-only stash refused because the installed Git is older than 2.35. */
    public static final String STASH_STAGED_UNSUPPORTED = "stash --staged needs Git 2.35 or later";

    /**
     * Runs a stash command ({@code apply}/{@code pop}/{@code drop}/{@code branch} …) that names {@code entry}
     * by its {@code stash@{N}} ref — the only name {@code pop} and {@code drop} accept. The ref is a position
     * in a list any other window or terminal can change, so the job first checks, holding the working tree,
     * that it still resolves to the commit that was listed; otherwise nothing runs and the result's
     * {@code err} is {@link #STASH_MOVED}. An entry without a hash (an older listing) is not checked.
     */
    public void runStashMutation(
            Path root, StashParser.StashEntry entry, Consumer<ProcessRunner.Result> onResult, String... args) {
        Supplier<String> refusal = () -> {
            if (entry == null || entry.hash().isBlank()) {
                return null;
            }
            ProcessRunner.Result now = git(root, QUICK, "rev-parse", "--verify", "--quiet", entry.ref());
            return now.ok() && now.out().strip().equals(entry.hash()) ? null : STASH_MOVED;
        };
        runWorktreeMutation(
                exec, localCommands, root, MUTATION, java.util.Collections.singletonList(args), refusal, onResult);
    }

    /**
     * {@code git stash push} with {@code options}. A staged-only stash on a Git without {@code --staged}
     * (before 2.35) is refused here with {@link #STASH_STAGED_UNSUPPORTED} rather than left to git's
     * "unknown option" usage text.
     */
    public void stashPush(Path root, StashOptions options, Consumer<ProcessRunner.Result> onResult) {
        Supplier<String> refusal = () ->
                options.stagedOnly() && !StashOptions.stagedSupported(gitVersion) ? STASH_STAGED_UNSUPPORTED : null;
        runWorktreeMutation(
                exec,
                localCommands,
                root,
                MUTATION,
                java.util.Collections.singletonList(options.args()),
                refusal,
                onResult);
    }

    /**
     * The repo-relative, forward-slash path of {@code file} under {@code root} (for {@code git show} specs);
     * {@code null} if {@code file} isn't under {@code root}.
     *
     * <p>Both sides are <b>symlink-resolved</b> before comparing. {@code root} comes from
     * {@code git rev-parse --show-toplevel}, which returns the <em>real</em> path, while the buffer's
     * {@code file} keeps its as-opened path — so when the repo is reached through a symlink
     * ({@code ~/work/app → /Volumes/data/app}, or macOS {@code /tmp → /private/tmp}), the two share no prefix
     * and every path-based Git op (diff, stage, blame, history) wrongly reports "not in repo". Resolving both
     * to their real paths fixes that. A path that doesn't exist on disk (a fake test path, a deleted file)
     * falls back to {@code toAbsolutePath().normalize()}, preserving the old behaviour where there's nothing
     * to resolve.
     */
    public static String repoRelative(Path root, Path file) {
        if (root == null || file == null || root.getFileSystem() != file.getFileSystem()) {
            return null; // a path on another file system (a remote tab) is never inside this repository
        }
        Path r = realPath(root);
        Path f = realPath(file);
        if (!f.startsWith(r)) {
            return null;
        }
        return r.relativize(f).toString().replace('\\', '/');
    }

    /**
     * {@code p}'s real, symlink-resolved path. When {@code p} doesn't exist on disk (a not-yet-saved or
     * deleted file, or a fake path in a test), resolves the nearest existing ancestor and re-appends the
     * missing tail — so a file under a symlinked directory still canonicalizes even before it hits disk —
     * and if nothing resolves, falls back to a plain absolute-normalized path.
     */
    static Path realPath(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException notThere) {
            Path abs = p.toAbsolutePath();
            Path existing = abs.getParent();
            while (existing != null && !Files.exists(existing)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return abs.normalize();
            }
            try {
                return existing.toRealPath().resolve(existing.relativize(abs)).normalize();
            } catch (IOException stillNot) {
                return abs.normalize();
            }
        }
    }

    /**
     * Stands for "the remote this branch pushes to" in an argv handed to {@link #runNetwork}, which replaces
     * it with the remote the repository's configuration names ({@link #pushRemote}) when the command's turn
     * comes. {@link #pushArgs(String, String)} is called on the JavaFX thread, where the configuration cannot
     * be read; the lookup is a process and belongs on the lane.
     */
    public static final String PUSH_REMOTE = "<push-remote>";

    /**
     * The {@code git push} argv for the current branch, the remote left to be resolved by
     * {@link #runNetwork} ({@link #PUSH_REMOTE}). See {@link #pushArgs(String, String, String)}.
     */
    public static String[] pushArgs(String branch, String upstream) {
        return pushArgs(branch, upstream, PUSH_REMOTE);
    }

    /**
     * The {@code git push} argv for the current branch. A brand-new branch has no upstream, so a bare
     * {@code git push} fails; in that case we push with {@code --set-upstream <remote> refs/heads/<branch>}
     * so the first push "just works" (matching {@code push.autoSetupRemote}). Subsequent pushes (an upstream
     * is already tracked) use a plain {@code push}. A blank/unknown branch name also falls back to a plain
     * {@code push} — we never emit {@code --set-upstream} with an empty branch.
     *
     * <p>The branch is named by its full ref: a branch name is repository data, and a bare one that starts
     * with {@code -} would be read as an option (and one that is also a tag name would be ambiguous). A ref
     * that still fails {@link GitSafety#isSafeRevision} (a control character), or a remote that does, gets
     * the plain {@code push} too.
     * Pure — unit-tested.
     */
    public static String[] pushArgs(String branch, String upstream, String remote) {
        boolean noUpstream = upstream == null || upstream.isBlank();
        // A detached HEAD is reported as "(detached)" (non-blank), and no real branch name can start with
        // "(" — so guard against it, else we'd emit `push --set-upstream origin (detached)`, which git
        // rejects as a bad refname. A plain `push` lets git give its own clearer "detached HEAD" message.
        boolean haveBranch = branch != null && !branch.isBlank() && !branch.startsWith("(");
        String ref = "refs/heads/" + branch;
        if (noUpstream && haveBranch && GitSafety.isSafeRevision(ref) && GitSafety.isSafeRevision(remote)) {
            return new String[] {"push", "--set-upstream", remote, ref};
        }
        return new String[] {"push"};
    }

    /** Whether {@code branch} is no branch at all: blank, or the {@code (detached)} marker of a detached HEAD. */
    public static boolean isDetached(String branch) {
        return branch == null || branch.isBlank() || branch.startsWith("(");
    }

    /**
     * The argv of a forced push of the current branch — always {@code --force-with-lease}, never a bare
     * {@code --force}: the remote branch is replaced only if it is still where this repository last saw it,
     * so commits someone else pushed in the meantime are not silently destroyed. A tracked branch pushes to
     * its upstream; an untracked one is pushed, and tracked, like a first {@link #pushArgs push}. Empty for a
     * detached HEAD or an unusable name: there is no branch to force. Pure — unit-tested.
     */
    public static String[] forcePushArgs(String branch, String upstream, String remote) {
        String ref = "refs/heads/" + branch;
        if (isDetached(branch) || !GitSafety.isSafeRevision(ref)) {
            return new String[0];
        }
        if (upstream != null && !upstream.isBlank()) {
            return new String[] {"push", "--force-with-lease"};
        }
        if (!GitSafety.isSafeRevision(remote)) {
            return new String[0];
        }
        return new String[] {"push", "--force-with-lease", "--set-upstream", remote, ref};
    }

    /** {@link #forcePushArgs(String, String, String)} with the remote left to {@link #runNetwork}. */
    public static String[] forcePushArgs(String branch, String upstream) {
        return forcePushArgs(branch, upstream, PUSH_REMOTE);
    }

    /**
     * The argv that pushes local {@code branch} to {@code remoteBranch} on {@code remote} — a remote other
     * than the tracked one, or another name there. {@code setUpstream} also makes it the branch's upstream.
     * Both sides are full refs, so neither can be read as an option or mistaken for a tag. Empty when a name
     * is unusable ({@link GitRefNames}). Pure — unit-tested.
     */
    static String[] pushToArgs(
            boolean endOfOptions, String remote, String branch, String remoteBranch, boolean setUpstream) {
        if (isDetached(branch)
                || !GitRefNames.isValidBranch(branch)
                || !GitRefNames.isValidBranch(remoteBranch)
                || !GitSafety.isSafeRevision(remote)) {
            return new String[0];
        }
        List<String> argv = new ArrayList<>(List.of("push"));
        if (setUpstream) {
            argv.add("--set-upstream");
        }
        argv.addAll(
                GitSafety.revisionArgs(endOfOptions, remote, "refs/heads/" + branch + ":refs/heads/" + remoteBranch));
        return argv.toArray(String[]::new);
    }

    public static String[] pushToArgs(String remote, String branch, String remoteBranch, boolean setUpstream) {
        return pushToArgs(endOfOptions, remote, branch, remoteBranch, setUpstream);
    }

    /** {@code push --tags <remote>}: every local tag the remote lacks. Empty for an unusable remote. Pure. */
    static String[] pushTagsArgs(boolean endOfOptions, String remote) {
        if (!GitSafety.isSafeRevision(remote)) {
            return new String[0];
        }
        List<String> argv = new ArrayList<>(List.of("push", "--tags"));
        argv.addAll(GitSafety.revisionArgs(endOfOptions, remote));
        return argv.toArray(String[]::new);
    }

    public static String[] pushTagsArgs(String remote) {
        return pushTagsArgs(endOfOptions, remote);
    }

    /**
     * {@code push --delete <remote> refs/heads/<branch>}: removes the branch on the remote. The full ref keeps
     * a tag of the same name out of it. Empty when a name is unusable. Pure — unit-tested.
     */
    static String[] deleteRemoteBranchArgs(boolean endOfOptions, String remote, String branch) {
        String ref = "refs/heads/" + branch;
        if (branch == null || branch.isBlank() || !GitSafety.isSafeRevision(ref) || !GitSafety.isSafeRevision(remote)) {
            return new String[0];
        }
        List<String> argv = new ArrayList<>(List.of("push", "--delete"));
        argv.addAll(GitSafety.revisionArgs(endOfOptions, remote, ref));
        return argv.toArray(String[]::new);
    }

    public static String[] deleteRemoteBranchArgs(String remote, String branch) {
        return deleteRemoteBranchArgs(endOfOptions, remote, branch);
    }

    /**
     * An argv for a command built outside this class: {@code options}, then {@code --end-of-options} when the
     * installed git has it, then {@code positional} — the refs, remotes and paths, which are repository data
     * or typed text and must never be read as options.
     */
    public static String[] guarded(List<String> options, String... positional) {
        List<String> argv = new ArrayList<>(options);
        argv.addAll(GitSafety.revisionArgs(endOfOptions, positional));
        return argv.toArray(String[]::new);
    }

    /**
     * The remote a first push of {@code branch} goes to, from the repository's configuration
     * ({@code git config --list -z}: {@code key\nvalue} records separated by NUL) and its remote names. Git's
     * own order — {@code branch.<name>.pushRemote}, {@code remote.pushDefault}, {@code branch.<name>.remote} —
     * then {@code origin} if there is such a remote, then the only remote if there is exactly one. With
     * nothing to go on the answer is {@code origin}, so git reports the missing remote in its own words.
     * A configured name that is not a usable argument is skipped. Pure — unit-tested.
     */
    static String pushRemote(String configListZ, String branch, List<String> remotes) {
        String pushRemote = null;
        String pushDefault = null;
        String branchRemote = null;
        String section = "branch." + (branch == null ? "" : branch) + ".";
        for (String record : configListZ == null ? new String[0] : configListZ.split("\u0000", -1)) {
            int newline = record.indexOf('\n');
            if (newline < 0) {
                continue; // a key with no value
            }
            String key = record.substring(0, newline);
            String value = record.substring(newline + 1).strip();
            // Section and variable names are lower-cased by git; the subsection (the branch name) is not.
            // Later records override earlier ones, as they do for git.
            if (key.equals("remote.pushdefault")) {
                pushDefault = value;
            } else if (key.startsWith(section) && key.length() > section.length()) {
                String variable = key.substring(section.length());
                if (variable.equals("pushremote")) {
                    pushRemote = value;
                } else if (variable.equals("remote")) {
                    branchRemote = value;
                }
            }
        }
        for (String configured : Arrays.asList(pushRemote, pushDefault, branchRemote)) {
            // "." is git's name for "this repository" (a branch tracking a local one): not a push target.
            if (configured != null && !configured.equals(".") && GitSafety.isSafeRevision(configured)) {
                return configured;
            }
        }
        List<String> usable = remotes == null
                ? List.of()
                : remotes.stream().filter(GitSafety::isSafeRevision).toList();
        if (usable.contains("origin") || usable.size() != 1) {
            return "origin";
        }
        return usable.get(0);
    }

    /**
     * {@code args} with {@link #PUSH_REMOTE} replaced by the remote the configuration names for the branch
     * being pushed (the {@code refs/heads/<branch>} that follows it). Runs on the network lane.
     */
    private String[] withPushRemote(Path root, String... args) {
        int at = Arrays.asList(args).indexOf(PUSH_REMOTE);
        if (at < 0) {
            return args;
        }
        String branch = "";
        if (at + 1 < args.length && args[at + 1].startsWith("refs/heads/")) {
            branch = args[at + 1].substring("refs/heads/".length());
        }
        ProcessRunner.Result config = git(root, QUICK, "config", "--list", "-z");
        ProcessRunner.Result remotes = git(root, QUICK, "remote");
        List<String> names = remotes.ok()
                ? remotes.out()
                        .lines()
                        .map(String::strip)
                        .filter(n -> !n.isEmpty())
                        .toList()
                : List.of();
        String[] resolved = args.clone();
        resolved[at] = pushRemote(config.ok() ? config.out() : "", branch, names);
        return resolved;
    }

    // --- branches --------------------------------------------------------------------------------

    /**
     * One local branch with its tracking info: {@code upstream} (e.g. {@code origin/main}, empty if
     * none), {@code ahead}/{@code behind} commit counts vs the upstream, and {@code gone} when the
     * upstream ref no longer exists.
     */
    public record BranchInfo(String name, String upstream, int ahead, int behind, boolean gone) {}

    /** Local branches (with tracking info), remote branch short-names, and the remote URL (origin's, or
     *  the first remote's; empty when there's no remote) — for the branch popup. */
    public record Branches(List<BranchInfo> local, List<String> remote, String remoteUrl, List<String> remoteNames) {
        public static final Branches EMPTY = new Branches(List.of(), List.of(), "");

        /** Without the remotes' names — {@link GitRemotes#split} then splits at the first slash. */
        public Branches(List<BranchInfo> local, List<String> remote, String remoteUrl) {
            this(local, remote, remoteUrl, List.of());
        }
    }

    /** Lists local ({@code refs/heads}) + remote ({@code refs/remotes}) branches, posted on the FX thread. */
    public void branches(Path root, Consumer<Branches> onResult) {
        submit(exec, () -> {
            Branches result = Branches.EMPTY;
            if (gitAvailable() && root != null) {
                result = new Branches(
                        localBranches(root), remoteBranchNames(root), remoteUrl(root), remoteNamesNow(root));
            }
            Branches posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** Lists tag short-names, sorted as returned by Git, and posts them on the FX thread. */
    public void tags(Path root, Consumer<List<String>> onResult) {
        submit(exec, () -> {
            List<String> tags = List.of();
            if (gitAvailable() && root != null) {
                ProcessRunner.Result r = git(
                        root, QUICK, "for-each-ref", "--format=%(refname:short)", "--sort=-creatordate", "refs/tags");
                if (r.ok()) {
                    tags = r.out()
                            .lines()
                            .map(String::strip)
                            .filter(s -> !s.isEmpty())
                            .toList();
                }
            }
            List<String> posted = tags;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** The remote URL to show in the branch popup: {@code origin}'s if present, else the first remote's. */
    private String remoteUrl(Path root) {
        ProcessRunner.Result origin = git(root, QUICK, "remote", "get-url", "origin");
        if (origin.ok() && !origin.out().strip().isEmpty()) {
            return origin.out().strip();
        }
        ProcessRunner.Result remotes = git(root, QUICK, "remote");
        if (remotes.ok()) {
            for (String line : remotes.out().split("\n")) {
                String name = line.strip();
                if (!name.isEmpty()) {
                    ProcessRunner.Result url = git(root, QUICK, "remote", "get-url", name);
                    if (url.ok() && !url.out().strip().isEmpty()) {
                        return url.out().strip();
                    }
                }
            }
        }
        return "";
    }

    /** Local branches with upstream + ahead/behind, from one {@code for-each-ref} (tab-separated fields). */
    private List<BranchInfo> localBranches(Path root) {
        List<BranchInfo> out = new ArrayList<>();
        ProcessRunner.Result r = git(
                root,
                QUICK,
                "for-each-ref",
                "--format=%(refname:short)\t%(upstream:short)\t%(upstream:track)",
                "refs/heads");
        if (r.ok()) {
            for (String line : r.out().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                String name = f[0].strip();
                if (name.isEmpty()) {
                    continue;
                }
                String upstream = f.length > 1 ? f[1].strip() : "";
                String track = f.length > 2 ? f[2] : "";
                int[] ab = parseTrack(track);
                out.add(new BranchInfo(name, upstream, ab[0], ab[1], track.contains("gone")));
            }
        }
        return out;
    }

    /** Remote branch short-names (e.g. {@code origin/feature/x}); the {@code <remote>/HEAD} pointer is
     *  dropped (its {@code %(refname:short)} collapses to just {@code origin}, a bogus branch). */
    private List<String> remoteBranchNames(Path root) {
        List<String> names = new ArrayList<>();
        ProcessRunner.Result r = git(root, QUICK, "for-each-ref", "--format=%(refname)", "refs/remotes");
        if (r.ok()) {
            for (String line : r.out().split("\n")) {
                String ref = line.strip();
                if (ref.isEmpty() || ref.endsWith("/HEAD") || !ref.startsWith("refs/remotes/")) {
                    continue;
                }
                names.add(ref.substring("refs/remotes/".length()));
            }
        }
        return names;
    }

    private static final Pattern TRACK_AHEAD = Pattern.compile("ahead (\\d+)");
    private static final Pattern TRACK_BEHIND = Pattern.compile("behind (\\d+)");

    /** Parses git's {@code %(upstream:track)} (e.g. {@code "[ahead 2, behind 153]"}) → {@code {ahead, behind}}. */
    static int[] parseTrack(String track) {
        int ahead = 0;
        int behind = 0;
        if (track != null) {
            Matcher a = TRACK_AHEAD.matcher(track);
            if (a.find()) {
                ahead = Integer.parseInt(a.group(1));
            }
            Matcher b = TRACK_BEHIND.matcher(track);
            if (b.find()) {
                behind = Integer.parseInt(b.group(1));
            }
        }
        return new int[] {ahead, behind};
    }

    // --- remotes, work trees, ref comparison -----------------------------------------------------

    /** The names of the repository's remotes, in git's order. Runs on the calling (service) thread. */
    private static List<String> remoteNamesNow(Path root) {
        ProcessRunner.Result r = git(root, QUICK, "remote");
        return r.ok()
                ? r.out().lines().map(String::strip).filter(n -> !n.isEmpty()).toList()
                : List.of();
    }

    /** Lists the remotes with their URLs ({@code git remote -v}), posted on the FX thread. */
    public void remotes(Path root, Consumer<List<GitRemotes.Remote>> onResult) {
        submit(exec, () -> {
            List<GitRemotes.Remote> remotes = List.of();
            if (gitAvailable() && root != null) {
                ProcessRunner.Result r = git(root, QUICK, "remote", "-v");
                if (r.ok()) {
                    remotes = GitRemotes.parse(r.out());
                }
            }
            List<GitRemotes.Remote> posted = remotes;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** The remote a push of {@code branch} goes to ({@link #pushRemote}), posted on the FX thread. */
    public void pushRemoteOf(Path root, String branch, Consumer<String> onResult) {
        submit(exec, () -> {
            String remote = "origin";
            if (gitAvailable() && root != null) {
                ProcessRunner.Result config = git(root, QUICK, "config", "--list", "-z");
                remote = pushRemote(config.ok() ? config.out() : "", branch, remoteNamesNow(root));
            }
            String posted = remote;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** Lists the repository's work trees ({@code git worktree list --porcelain}), posted on the FX thread. */
    public void worktrees(Path root, Consumer<List<GitWorktrees.Worktree>> onResult) {
        submit(exec, () -> {
            List<GitWorktrees.Worktree> trees = List.of();
            if (gitAvailable() && root != null) {
                ProcessRunner.Result r = git(root, QUICK, "worktree", "list", "--porcelain");
                if (r.ok()) {
                    trees = GitWorktrees.parse(r.out());
                }
            }
            List<GitWorktrees.Worktree> posted = trees;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * How many commits of local {@code branch} are not reachable from {@code HEAD} — what deleting it would
     * leave without a branch, as far as the checked-out one is concerned. {@code -1} when it cannot be told.
     */
    public void unmergedCount(Path root, String branch, Consumer<Integer> onResult) {
        submit(exec, () -> {
            int count = -1;
            String range = "HEAD..refs/heads/" + branch;
            if (gitAvailable() && root != null && GitSafety.isSafeRevision(range)) {
                List<String> args = new ArrayList<>(List.of("rev-list", "--count"));
                args.addAll(GitSafety.revisionArgs(endOfOptions, range));
                ProcessRunner.Result r = git(root, QUICK, args.toArray(String[]::new));
                if (r.ok()) {
                    try {
                        count = Integer.parseInt(r.out().strip());
                    } catch (NumberFormatException notACount) {
                        count = -1;
                    }
                }
            }
            int posted = count;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * Lists the files that differ between two revisions ({@code git diff --name-status <left> <right>}) — the
     * ref↔ref form of {@link #workingTreeDiff}, with the same result type, limit and disabled rename
     * detection, so one review surface serves both.
     */
    public void refDiff(Path root, String left, String right, Consumer<WorkingTreeDiff> onResult) {
        submit(exec, () -> {
            WorkingTreeDiff result = new WorkingTreeDiff(List.of(), false, "Git is not available");
            if (gitAvailable() && root != null) {
                if (!GitSafety.isSafeRevision(left) || !GitSafety.isSafeRevision(right)) {
                    result = new WorkingTreeDiff(
                            List.of(),
                            false,
                            "Unsafe revision name: " + (GitSafety.isSafeRevision(left) ? right : left));
                } else {
                    List<String> args = new ArrayList<>(
                            List.of(GitSafety.LITERAL_PATHSPECS, "diff", "--name-status", "-z", "--no-renames"));
                    args.addAll(GitSafety.revisionArgs(endOfOptions, left, right));
                    args.add("--");
                    ProcessRunner.Result changed = git(root, QUICK, args.toArray(String[]::new));
                    result = changed.ok()
                            ? mergeWorkingTreeDiff(changed.out(), "", MAX_WORKING_TREE_DIFF_FILES)
                            : new WorkingTreeDiff(List.of(), false, changed.message());
                }
            }
            WorkingTreeDiff posted = result;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    // --- clone -----------------------------------------------------------------------------------

    /**
     * {@code clone --progress -- <url> <destination>}. The URL is pasted text: without the {@code --} a value
     * beginning with {@code -} is an option, and {@code --upload-pack=<program>} runs that program.
     * {@code --progress} because git only reports progress to a terminal unless told to, and the clone is
     * streamed into the Git console ({@link #gitStreamed}) — as are fetch, pull and push
     * ({@link #withProgress}).
     */
    static String[] cloneArgs(String url, String destination) {
        return new String[] {"clone", "--progress", "--", url == null ? "" : url, destination};
    }

    /**
     * Clones {@code url} into {@code destination} (an absolute target path that must not yet exist; its
     * parent must). Runs off the FX thread with the network timeout — the user's git handles
     * credentials/SSH. Posts the {@link ProcessRunner.Result} on the FX thread.
     */
    public void clone(String url, Path destination, Consumer<ProcessRunner.Result> onResult) {
        submit(networkExec, () -> {
            ProcessRunner.Result r;
            if (!gitAvailable()) {
                r = NOT_INSTALLED;
            } else {
                Path parent = destination.toAbsolutePath().getParent();
                r = userCommand(
                        networkCommands,
                        () -> gitStreamed(
                                parent,
                                NETWORK,
                                cloneArgs(url, destination.toAbsolutePath().toString())));
            }
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /**
     * Creates a repository in {@code dir} ({@code git init}).
     *
     * <p>Runs <em>in</em> {@code dir} rather than a repo root, like {@link #clone}: this is one of the two
     * commands whose whole point is that there is no repository yet.
     *
     * <p>The already-a-repo check happens here, on the service thread, precisely because answering it means
     * running {@code git rev-parse} — doing that from the caller would block the FX thread. {@code existing}
     * is non-null when {@code dir} already sits inside a repository, in which case nothing was run: nesting
     * a repo inside another is nearly always a mistake and awkward to undo.
     *
     * @param onResult receives the {@code git init} result and, when refused, the enclosing repo's root
     */
    public void init(Path dir, java.util.function.BiConsumer<ProcessRunner.Result, Path> onResult) {
        submit(exec, () -> {
            Path abs = dir.toAbsolutePath();
            if (!gitAvailable()) {
                Platform.runLater(() -> onResult.accept(NOT_INSTALLED, null));
                return;
            }
            Path existing = resolveRoot(abs);
            if (existing != null) {
                Platform.runLater(() -> onResult.accept(new ProcessRunner.Result(-1, "", ""), existing));
                return;
            }
            ProcessRunner.Result r = userCommand(localCommands, () -> gitLogged(abs, MUTATION, "init"));
            if (r.ok()) {
                invalidateCaches(); // the cached "not a repo" for this folder is now wrong
            }
            Platform.runLater(() -> onResult.accept(r, null));
        });
    }

    // --- mutations (run a command, post the raw result for status/error reporting) ---------------

    /**
     * Installs the sink that receives every command run through this section — the ones the user actually
     * asked for. The read paths ({@code status}/{@code diff}/{@code log}/{@code blame}/…) deliberately stay
     * off it: they re-run on every tab switch, focus-regain and save, and would bury the commit or push the
     * user opened the console to read.
     */
    public void setCommandLog(CommandLog log) {
        this.commandLog = log == null ? CommandLog.none() : log;
    }

    /**
     * Network operations that leave the working tree alone (fetch/push). They run on their own lane, so a
     * slow or unreachable remote never holds up status and gutter refreshes — but they start only after the
     * local commands requested before them ({@link #submitNetworkInRequestOrder}). A {@link #PUSH_REMOTE}
     * among {@code args} is resolved here, when the command starts.
     */
    public void runNetwork(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        submitNetworkInRequestOrder(() -> {
            ProcessRunner.Result r = gitAvailable() && root != null
                    ? userCommand(
                            networkCommands, () -> gitStreamed(root, NETWORK, withProgress(withPushRemote(root, args))))
                    : NOT_INSTALLED;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Runs a working-tree mutation on the service's serial command executor. */
    public void runWorktreeMutation(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        runWorktreeMutation(
                exec, localCommands, root, MUTATION, java.util.Collections.singletonList(args), null, onResult);
    }

    /** Runs several related working-tree commands as one serial executor job. */
    public void runWorktreeMutation(Path root, List<String[]> commands, Consumer<ProcessRunner.Result> onResult) {
        runWorktreeMutation(exec, localCommands, root, MUTATION, commands, null, onResult);
    }

    /** Network form used by pull; fetch and push do not need the working-tree boundary. */
    public void runNetworkWorktreeMutation(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        runWorktreeMutation(
                networkExec, networkCommands, root, NETWORK, java.util.Collections.singletonList(args), null, onResult);
    }

    /**
     * The staged changes as one unified diff ({@code git diff --cached}), or {@code null} when it cannot be
     * read. A read made on the user's behalf but with no need for the repository's diff drivers, so it takes
     * the hardened background path.
     */
    public void stagedDiff(Path root, Consumer<String> onResult) {
        submit(exec, () -> {
            ProcessRunner.Result r = gitAvailable() && root != null ? git(root, QUICK, "diff", "--cached") : null;
            String posted = r != null && r.ok() ? r.out() : null;
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * Applies a generated patch after first checking it against the current worktree/index. The check and
     * mutation share the service's serial executor, so a stale diff fails cleanly instead of applying to a
     * different file state. {@code cached} targets the index; otherwise the working tree is targeted.
     */
    public void applyPatch(Path root, String patch, boolean cached, Consumer<ProcessRunner.Result> onResult) {
        byte[] bytes = (patch == null ? "" : patch).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        applyPatch(root, bytes, cached, false, onResult);
    }

    /**
     * Applies {@code patch} — the patch file's own bytes, so a patch of Latin-1 or CRLF text reaches git as
     * it was written — to the working tree, or to the index only ({@code cached}).
     *
     * <p>Plain: {@code git apply --check} first, then the apply, as one job; a patch that does not fit
     * applies nothing and the result carries git's reason. {@code threeWay} ({@code --3way}) is the fallback
     * for such a patch: git merges it against the blobs it names, which can leave conflict markers and then
     * exits 1 having written the files ({@link PatchOutcome#CONFLICTS}). There is no pre-flight for it —
     * {@code --check --3way} succeeds for a patch that will conflict. Without {@code cached} a three-way
     * apply also updates the index, as git defines it.
     */
    public void applyPatch(
            Path root, byte[] patch, boolean cached, boolean threeWay, Consumer<ProcessRunner.Result> onResult) {
        submit(exec, () -> {
            List<String> base = new ArrayList<>();
            base.add("apply");
            if (threeWay) {
                base.add("--3way");
            }
            if (cached) {
                base.add("--cached");
            }
            List<String> check = new ArrayList<>(base);
            check.add("--check");
            byte[] input = patch == null ? new byte[0] : patch;
            ProcessRunner.Result posted = !gitAvailable() || root == null
                    ? NOT_INSTALLED
                    : userCommand(localCommands, () -> {
                        if (threeWay) {
                            return gitWithInput(root, input, base, USER_ENV);
                        }
                        ProcessRunner.Result checked = gitWithInput(root, input, check, USER_ENV);
                        return checked.ok() ? gitWithInput(root, input, base, USER_ENV) : checked;
                    });
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /** A patch read from git: its bytes, or why there are none. */
    public record PatchText(byte[] bytes, String error) {
        public boolean ok() {
            return error == null;
        }

        /** The patch as text (patches are UTF-8 unless the files in them are not). */
        public String text() {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * The staged ({@code git diff --cached}) or unstaged ({@code git diff}) changes as a patch that
     * {@code git apply} takes back: {@code --binary} so a changed image is carried rather than replaced by
     * "Binary files differ". A background read — no external diff driver, no textconv.
     */
    public void diffPatch(Path root, boolean staged, Consumer<PatchText> onResult) {
        List<String> args = new ArrayList<>(List.of("diff", "--binary"));
        if (staged) {
            args.add("--cached");
        }
        readPatch(root, args, onResult);
    }

    /** One commit as a mailbox patch with its message and author ({@code git format-patch -1 --stdout}). */
    public void commitPatch(Path root, String hash, Consumer<PatchText> onResult) {
        if (!GitSafety.isSafeRevision(hash)) {
            Platform.runLater(() -> onResult.accept(new PatchText(new byte[0], "invalid revision")));
            return;
        }
        List<String> args = new ArrayList<>(List.of("format-patch", "-1", "--stdout"));
        args.addAll(GitSafety.revisionArgs(endOfOptions, hash));
        readPatch(root, args, onResult);
    }

    private void readPatch(Path root, List<String> args, Consumer<PatchText> onResult) {
        submit(exec, () -> {
            PatchText posted;
            if (!gitAvailable() || root == null) {
                posted = new PatchText(new byte[0], NOT_INSTALLED.err());
            } else {
                ProcessRunner.BytesResult r =
                        ProcessRunner.runBytes(root, QUICK, backgroundArgv(args.toArray(String[]::new)), READ_ENV);
                posted = !r.ok()
                        ? new PatchText(
                                new byte[0], r.err() == null ? "" : r.err().strip())
                        : r.outTruncated()
                                ? new PatchText(new byte[0], OUTPUT_TOO_LARGE)
                                : new PatchText(r.out(), null);
            }
            Platform.runLater(() -> onResult.accept(posted));
        });
    }

    /**
     * Replaces {@code path}'s index entry with exactly {@code newBlob} and publishes the result only if the
     * real index bytes and the path's blob still match the displayed snapshot. Hunk staging uses this instead
     * of a text patch: the caller builds the desired blob <em>bytes</em> with the original line terminators
     * and charset intact, so a CRLF blob stages cleanly (an LF-only patch never applied to it) and a Latin-1
     * blob cannot have UTF-8 bytes spliced into it. {@code hash-object --no-filters} stores the bytes verbatim
     * and {@code update-index --cacheinfo} points the entry at them in a private index, keeping the entry's
     * existing file mode; a path new to the index gets the mode of the working file ({@link #newEntryMode}). The standard index.lock makes publication a compare-and-swap with external Git
     * processes rather than a check followed by a race window.
     */
    public void stageBlob(
            Path root, String path, BlobResult expectedBlob, byte[] newBlob, Consumer<ProcessRunner.Result> onResult) {
        byte[] body = newBlob == null ? new byte[0] : newBlob.clone();
        submit(exec, () -> {
            ProcessRunner.Result result =
                    userCommand(localCommands, () -> stageBlobNow(root, path, expectedBlob, body));
            Platform.runLater(() -> onResult.accept(result));
        });
    }

    /**
     * Removes {@code path}'s index entry under the same compare-and-swap as {@link #stageBlob}. A hunk action
     * whose result is "the path is not in the index" — unstaging a newly added file, staging the deletion of
     * a file that is gone from the working tree — cannot be expressed as a blob: writing an empty one left
     * the path staged as an empty (or one-newline) file.
     */
    public void removeIndexEntry(
            Path root, String path, BlobResult expectedBlob, Consumer<ProcessRunner.Result> onResult) {
        submit(exec, () -> {
            ProcessRunner.Result result =
                    userCommand(localCommands, () -> stageBlobNow(root, path, expectedBlob, null));
            Platform.runLater(() -> onResult.accept(result));
        });
    }

    private static final Pattern OBJECT_ID = Pattern.compile("[0-9a-f]{40,64}");

    /** A path's index entry: {@code id} is a blob id, {@link #MISSING} or {@link #CONFLICT}. */
    private record IndexEntry(String mode, String id) {
        static final String MISSING = "MISSING";
        static final String CONFLICT = "CONFLICT";

        boolean found() {
            return !MISSING.equals(id) && !CONFLICT.equals(id);
        }
    }

    /** Writes {@code body} as a blob and points {@code path} at it in the index named by {@code env}. */
    private ProcessRunner.Result writeIndexEntry(
            Path root, String path, IndexEntry entry, byte[] body, Map<String, String> env) {
        // An existing entry's bytes were rebuilt from its own blob and are stored verbatim. A path that is new
        // to the index gets the working file's bytes, which Git must clean exactly as `git add` would
        // (core.autocrlf, a clean filter): stored verbatim, a new CRLF file would be committed with CRLF in a
        // repository that normalises line endings.
        //
        // A new symbolic link is not text to pick hunks from: its blob is the link's target, verbatim, which
        // is what `git add` records. Staging the bytes read through it would commit a copy of the target.
        byte[] linkTarget = entry.found() ? null : symlinkTarget(root, path);
        ProcessRunner.Result hashed = gitWithInput(
                root,
                linkTarget != null ? linkTarget : body,
                entry.found() || linkTarget != null
                        ? List.of("hash-object", "-w", "--no-filters", "--stdin")
                        : List.of("hash-object", "-w", "--stdin", "--path=" + path),
                USER_ENV);
        String id = hashed.out().strip();
        if (!hashed.ok() || !OBJECT_ID.matcher(id).matches()) {
            return hashed.ok() ? new ProcessRunner.Result(1, "", "Git returned no blob id") : hashed;
        }
        String mode = entry.found() ? entry.mode() : newEntryMode(root, path);
        return gitWithInput(
                root, new byte[0], List.of("update-index", "--add", "--cacheinfo", mode + "," + id + "," + path), env);
    }

    /** Git's three modes for a blob entry. */
    static final String MODE_FILE = "100644";

    static final String MODE_EXECUTABLE = "100755";
    static final String MODE_SYMLINK = "120000";

    /**
     * The mode {@code git add} would give a path that is new to the index. Hunk staging used to record every
     * new file as {@link #MODE_FILE}, so a new script staged through the diff viewer was committed without its
     * executable bit. {@code ownerExecutable} is the owner's execute permission where the file system has one
     * (never on Windows, where every file "is executable"); {@code fileModeTrusted} is {@code core.fileMode}.
     * Pure — unit-tested.
     */
    static String newEntryMode(boolean symlink, boolean ownerExecutable, boolean fileModeTrusted) {
        if (symlink) {
            return MODE_SYMLINK;
        }
        return ownerExecutable && fileModeTrusted ? MODE_EXECUTABLE : MODE_FILE;
    }

    private static String newEntryMode(Path root, String path) {
        Path file;
        try {
            file = root.resolve(path);
        } catch (RuntimeException notAPath) {
            return MODE_FILE;
        }
        if (symlinkTarget(root, path) != null) {
            return MODE_SYMLINK;
        }
        boolean executable = false;
        try {
            java.nio.file.attribute.PosixFileAttributeView posix =
                    Files.getFileAttributeView(file, java.nio.file.attribute.PosixFileAttributeView.class);
            executable = posix != null
                    && posix.readAttributes()
                            .permissions()
                            .contains(java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
        } catch (IOException | RuntimeException unreadable) {
            // gone since the diff was shown, or no permissions to read: an ordinary file
        }
        if (!executable) {
            return MODE_FILE;
        }
        // Only now is core.fileMode worth a process: false on file systems whose execute bits mean nothing.
        ProcessRunner.Result trusted = git(root, QUICK, "config", "--type=bool", "--get", "core.fileMode");
        boolean fileModeTrusted = !(trusted.ok() && trusted.out().strip().equals("false"));
        return newEntryMode(false, true, fileModeTrusted);
    }

    /**
     * The target of {@code path} as git stores it (forward slashes, UTF-8) when it is a symbolic link in the
     * working tree, else {@code null}. Where links are checked out as plain files ({@code core.symlinks=false},
     * the Windows default) the path is not a link to Java either, and is staged as the file it is.
     */
    private static byte[] symlinkTarget(Path root, String path) {
        try {
            Path file = root.resolve(path);
            if (!Files.isSymbolicLink(file)) {
                return null;
            }
            return Files.readSymbolicLink(file)
                    .toString()
                    .replace('\\', '/')
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private ProcessRunner.Result stageBlobNow(Path root, String path, BlobResult expectedBlob, byte[] body) {
        if (root == null || path == null || expectedBlob == null) {
            return new ProcessRunner.Result(1, "", "The index changed after this diff was created");
        }
        ProcessRunner.Result indexPathResult = git(root, QUICK, "rev-parse", "--git-path", "index");
        if (!indexPathResult.ok() || indexPathResult.out().isBlank()) {
            return indexPathResult;
        }
        Path index = Path.of(indexPathResult.out().strip());
        if (!index.isAbsolute()) {
            index = root.resolve(index).normalize();
        }
        Path parent = index.getParent();
        if (parent == null) {
            return new ProcessRunner.Result(1, "", "Git index has no parent directory");
        }
        Path temporary = null;
        Path lock = index.resolveSibling(index.getFileName() + ".lock");
        // Only a lock this call created may be removed: an index.lock that already exists belongs to another
        // Git process (a commit in its hooks, say), and deleting it lets that process and the next writer
        // both believe they own the index.
        boolean lockOwned = false;
        try {
            // Hold Git's conventional lock before reading either the preimage or the path identity. Git
            // writers cannot change the index between our comparison and publication while this exists.
            Files.write(lock, new byte[0], java.nio.file.StandardOpenOption.CREATE_NEW);
            lockOwned = true;
            boolean originalExists = Files.exists(index);
            byte[] original = originalExists ? Files.readAllBytes(index) : new byte[0];
            BlobResult currentBlob = indexBlobNow(root, path);
            IndexEntry entry = indexEntryNow(root, path);
            if (expectedBlob.found() != currentBlob.found()
                    || !java.util.Arrays.equals(expectedBlob.bytes(), currentBlob.bytes())
                    || !expectedBlob.found() && !IndexEntry.MISSING.equals(entry.id())) {
                return new ProcessRunner.Result(1, "", "The index changed after this diff was created");
            }
            temporary = parent.resolve(".editora-index-" + java.util.UUID.randomUUID() + ".tmp");
            Map<String, String> env = new java.util.HashMap<>(USER_ENV);
            env.put("GIT_INDEX_FILE", temporary.toAbsolutePath().toString());
            if (originalExists) {
                Files.write(temporary, original, java.nio.file.StandardOpenOption.CREATE_NEW);
            } else {
                ProcessRunner.Result emptyIndex =
                        ProcessRunner.run(root, MUTATION, gitArgv("read-tree", "--empty"), env);
                if (!emptyIndex.ok()) {
                    return emptyIndex;
                }
            }
            ProcessRunner.Result result = body != null
                    ? writeIndexEntry(root, path, entry, body, env)
                    : gitWithInput(root, new byte[0], List.of("update-index", "--force-remove", "--", path), env);
            if (!result.ok()) {
                return result;
            }
            byte[] updated = Files.readAllBytes(temporary);
            Files.write(
                    lock,
                    updated,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
            if (originalExists != Files.exists(index)
                    || originalExists && !java.util.Arrays.equals(original, Files.readAllBytes(index))) {
                return new ProcessRunner.Result(1, "", "The index changed while applying this hunk");
            }
            try {
                Files.move(
                        lock,
                        index,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(lock, index, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return result;
        } catch (java.nio.file.FileAlreadyExistsException busy) {
            return new ProcessRunner.Result(1, "", "The Git index is busy");
        } catch (java.io.IOException failure) {
            return new ProcessRunner.Result(1, "", failure.getMessage());
        } finally {
            try {
                if (lockOwned) {
                    Files.deleteIfExists(lock);
                }
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                    Files.deleteIfExists(temporary.resolveSibling(temporary.getFileName() + ".lock"));
                }
            } catch (java.io.IOException ignored) {
                // Best-effort cleanup; a retained lock causes Git to refuse rather than corrupt the index.
            }
        }
    }

    private static BlobResult indexBlobNow(Path root, String path) {
        return blobNow(root, ":" + (path == null ? "" : path));
    }

    /** {@code git show <spec>} as raw bytes; a spec that could be read as an option is "not found". */
    private static BlobResult blobNow(Path root, String spec) {
        if (!GitSafety.isSafeBlobSpec(spec)) {
            return new BlobResult(false, new byte[0]);
        }
        List<String> args = new ArrayList<>(List.of("show"));
        args.addAll(GitSafety.revisionArgs(endOfOptions, spec));
        ProcessRunner.BytesResult result =
                ProcessRunner.runBytes(root, QUICK, backgroundArgv(args.toArray(String[]::new)), READ_ENV);
        return result.ok() && !result.outTruncated()
                ? new BlobResult(true, result.out())
                : new BlobResult(false, new byte[0], result.ok() && result.outTruncated());
    }

    /** The stage-0 index entry of {@code path} (its mode and blob id), or why there is none. */
    private static IndexEntry indexEntryNow(Path root, String path) {
        ProcessRunner.Result result =
                git(root, QUICK, GitSafety.LITERAL_PATHSPECS, "ls-files", "-s", "--", path == null ? "" : path);
        if (!result.ok() || result.out().isBlank()) {
            return new IndexEntry("", IndexEntry.MISSING);
        }
        for (String line : result.out().split("\\R")) {
            int first = line.indexOf(' ');
            int second = first < 0 ? -1 : line.indexOf(' ', first + 1);
            int tab = second < 0 ? -1 : line.indexOf('\t', second + 1);
            if (first > 0
                    && second > first
                    && tab > second
                    && line.substring(second + 1, tab).equals("0")) {
                return new IndexEntry(line.substring(0, first), line.substring(first + 1, second));
            }
        }
        return new IndexEntry("", IndexEntry.CONFLICT);
    }

    /**
     * Queues a working-tree mutation. One runs at a time, whichever lane it arrived on, and in the order the
     * user asked for them.
     *
     * <p>A pull ({@code lane == networkExec}) waits for the working tree on its own lane. A local mutation
     * must not: the local lane also carries every read, so blocking there while a pull sits on a dead
     * connection froze status, gutters, blame and every diff until the pull's ceiling. When the working tree
     * is taken (or an earlier mutation is already parked) the mutation is handed to the network lane, behind
     * the pull, and the local lane moves on.
     */
    private void runWorktreeMutation(
            ExecutorService lane,
            AtomicInteger running,
            Path root,
            Duration timeout,
            List<String[]> commands,
            Supplier<String> refusal,
            Consumer<ProcessRunner.Result> onResult) {
        if (lane == networkExec) {
            submitNetworkInRequestOrder(
                    () -> mutateWorktree(running, root, timeout, commands, refusal, onResult, false));
            return;
        }
        submit(lane, () -> {
            if (parkedMutations.get() == 0 && worktreeLock.tryLock()) {
                mutateWorktree(running, root, timeout, commands, refusal, onResult, true);
                return;
            }
            parkedMutations.incrementAndGet();
            boolean queued = submit(networkExec, () -> {
                try {
                    mutateWorktree(networkCommands, root, timeout, commands, refusal, onResult, false);
                } finally {
                    parkedMutations.decrementAndGet();
                }
            });
            if (!queued) {
                parkedMutations.decrementAndGet();
            }
        });
    }

    /**
     * Runs {@code commands} holding {@link #worktreeLock} ({@code locked}: the caller already took it).
     * {@code refusal} (may be null) is asked first, with the working tree held: a non-null answer is the
     * reason nothing is run, and becomes the failed result's {@code err}.
     */
    private void mutateWorktree(
            AtomicInteger running,
            Path root,
            Duration timeout,
            List<String[]> commands,
            Supplier<String> refusal,
            Consumer<ProcessRunner.Result> onResult,
            boolean locked) {
        ProcessRunner.Result result;
        boolean held = locked;
        try {
            if (!held) {
                try {
                    worktreeLock.lockInterruptibly();
                    held = true;
                } catch (InterruptedException closed) {
                    Thread.currentThread().interrupt();
                }
            }
            if (!held) {
                result = new ProcessRunner.Result(-1, "", "interrupted");
            } else if (!gitAvailable() || root == null) {
                result = NOT_INSTALLED;
            } else {
                result = userCommand(running, () -> {
                    String refused = refusal == null ? null : refusal.get();
                    if (refused != null) {
                        return new ProcessRunner.Result(1, "", refused);
                    }
                    ProcessRunner.Result combined = new ProcessRunner.Result(0, "", "");
                    for (String[] command : commands) {
                        // The network lane's commands (pull) are the slow ones: watched as they run.
                        ProcessRunner.Result current = running == networkCommands
                                ? gitStreamed(root, timeout, withProgress(command))
                                : gitLogged(root, timeout, command);
                        if (combined.ok()) {
                            combined = current;
                        }
                        if (current.cancelled()) {
                            return current; // the user stopped the job: its remaining commands do not run
                        }
                    }
                    return combined;
                });
            }
        } finally {
            if (held) {
                worktreeLock.unlock();
            }
        }
        ProcessRunner.Result posted = result;
        Platform.runLater(() -> onResult.accept(posted));
    }

    // --- internals -------------------------------------------------------------------------------

    private static final ProcessRunner.Result NOT_INSTALLED = new ProcessRunner.Result(-1, "", "Git is not installed");

    /** Queues {@code task} on {@code lane}; a task still queued when the window closes is dropped. */
    private boolean submit(ExecutorService lane, Runnable task) {
        try {
            lane.submit(() -> {
                if (!closing) {
                    task.run();
                }
            });
            return true;
        } catch (RejectedExecutionException closed) {
            // The window is closing: there is no longer anyone to report the result to.
            return false;
        }
    }

    /**
     * Queues a fetch / pull / push so that it starts after the local commands requested before it. The two
     * lanes are independent, so handed straight to the network lane a push clicked right after Commit ran
     * while the commit was still in its hooks, pushed the old HEAD and reported success. A marker on the
     * local lane passes the task on when its turn comes; the local lane itself never waits for the network.
     */
    private void submitNetworkInRequestOrder(Runnable task) {
        submit(exec, () -> submit(networkExec, task));
    }

    /**
     * Runs a user-initiated command and counts it as in flight for its lane, so {@link #shutdown()} can tell
     * "nothing important is running" (interrupt the lane) from "a commit or checkout is mid-update" (let it
     * finish). The counter is raised before {@link #closing} is read and {@code shutdown} sets the flag before
     * reading the counter, so a command either sees the close and never starts or is seen and left alone.
     */
    private ProcessRunner.Result userCommand(AtomicInteger running, Supplier<ProcessRunner.Result> command) {
        running.incrementAndGet();
        try {
            return closing ? new ProcessRunner.Result(-1, "", "interrupted") : command.get();
        } finally {
            running.decrementAndGet();
        }
    }

    /** The answer to "which repository is this path in": a root, or nothing with git's reason if it refused. */
    private record RootLookup(Path root, String refusal) {
        static final RootLookup NOT_A_REPO = new RootLookup(null, "");
    }

    private Path resolveRoot(Path contextPath) {
        return lookupRoot(contextPath).root();
    }

    private RootLookup lookupRoot(Path contextPath) {
        Path dir = Files.isDirectory(contextPath) ? contextPath : contextPath.getParent();
        if (dir == null) {
            return RootLookup.NOT_A_REPO;
        }
        dir = dir.toAbsolutePath();
        String key = dir.toString();
        CachedRoot cached = rootCache.get(key);
        if (cached != null) {
            // A few stat calls instead of a process: a `git init` or clone below the cached root, or the
            // repository going away, changes which .git entries exist between this folder and that root.
            if (cached.markers().equals(gitMarkers(dir, cached.root()))) {
                return new RootLookup(cached.root(), "");
            }
            rootCache.remove(key);
        }
        long now = nanoClock.getAsLong();
        NegativeRoot negative = negativeRoots.get(key);
        if (negative != null
                && now - negative.sinceNanos()
                        < (negative.refusal().isEmpty() ? NOT_A_REPO_TTL : REFUSED_TTL).toNanos()) {
            return new RootLookup(null, negative.refusal());
        }
        // One process answers both questions: the work tree's root, and (second line) its git directory,
        // where the state of a merge or rebase in progress is read from on every later refresh.
        ProcessRunner.Result r = git(dir, QUICK, "rev-parse", "--show-toplevel", "--git-dir");
        Path root = null;
        if (r.ok()) {
            String[] lines = r.out().strip().split("\\R");
            String top = lines[0].strip();
            if (!top.isEmpty()) {
                root = Path.of(top);
                rememberGitDir(root, dir, lines.length > 1 ? lines[1].strip() : "");
            }
        }
        if (root != null) {
            rootCache.put(key, new CachedRoot(root, gitMarkers(dir, root)));
            negativeRoots.remove(key);
            return new RootLookup(root, "");
        }
        if (isNotARepository(r)) {
            // Only git's own "not a repository", and only briefly: the folder can become one at any time
            // (git init / clone in a terminal). A timeout or a directory that does not exist yet says
            // nothing about the folder and is asked again next time.
            negativeRoots.put(key, new NegativeRoot(now, ""));
            return RootLookup.NOT_A_REPO;
        }
        String refusal = refusalReason(r);
        if (refusal.isEmpty()) {
            negativeRoots.remove(key);
            return RootLookup.NOT_A_REPO;
        }
        negativeRoots.put(key, new NegativeRoot(now, refusal));
        return new RootLookup(null, refusal);
    }

    /** No repository nests deeper than this below its root in practice; bounds the walk in {@link #gitMarkers}. */
    private static final int MAX_MARKER_DEPTH = 64;

    /**
     * Whether a {@code .git} entry exists in {@code dir} and in each directory above it, up to and including
     * {@code root} — the cheap fingerprint a cached root is revalidated with. {@code root} is git's real
     * path while {@code dir} is as opened, so the walk ends at the directory that <em>is</em> {@code root}
     * (symlinks resolved); a folder that is not below it at all (a separate work tree) is fingerprinted by
     * its own entry and the root's.
     */
    static List<Boolean> gitMarkers(Path dir, Path root) {
        List<Boolean> markers = new ArrayList<>();
        int depth = -1;
        try {
            Path real = dir.toRealPath();
            if (real.startsWith(root)) {
                depth = real.getNameCount() - root.getNameCount();
            }
        } catch (IOException | RuntimeException gone) {
            // the folder itself is gone or unreadable: fall through to the two-entry fingerprint
        }
        if (depth < 0 || depth > MAX_MARKER_DEPTH) {
            markers.add(Files.exists(dir.resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            markers.add(Files.exists(root.resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            return markers;
        }
        Path at = dir;
        for (int i = 0; i <= depth && at != null; i++) {
            markers.add(Files.exists(at.resolve(".git"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
            at = at.getParent();
        }
        return markers;
    }

    /** Whether a failed {@code rev-parse} is git's definite "not a repository" (read in the C locale). */
    static boolean isNotARepository(ProcessRunner.Result r) {
        return r.exit() == 128 && r.err() != null && r.err().contains("not a git repository");
    }

    /**
     * Git's own reason for a command that ran and failed — the first line of its stderr, without the
     * {@code fatal:} prefix — or {@code ""} when git gave none: it was killed at the timeout, could not be
     * started, or was interrupted, none of which says anything about the folder. Pure.
     */
    static String refusalReason(ProcessRunner.Result r) {
        if (r == null || r.exit() <= 0 || r.err() == null) {
            return "";
        }
        for (String line : r.err().split("\\R")) {
            String text = line.strip();
            if (text.isEmpty()) {
                continue;
            }
            for (String prefix : List.of("fatal: ", "error: ")) {
                if (text.startsWith(prefix)) {
                    text = text.substring(prefix.length()).strip();
                    break;
                }
            }
            return text.length() > 300 ? text.substring(0, 300) + "…" : text;
        }
        return "";
    }

    /**
     * A user-initiated command: the user's own git — hooks and repository configuration included — plus a
     * {@link CommandLog} report. Background reads go through {@link #git} instead.
     */
    private ProcessRunner.Result gitLogged(Path dir, Duration timeout, String... args) {
        long startNanos = System.nanoTime();
        List<String> argv = gitArgv(args);
        // The user's locale, not LC_ALL=C: the hooks this runs are the user's programs (see GitSafety.userEnv).
        // Run with a cancellation handle (and no live listener): a commit stuck in a hook, or a checkout of a
        // huge tree, otherwise holds this lane — and every status and gutter read queued on it — for up to
        // the mutation ceiling with no way to stop it.
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        localCommand = cancel;
        ProcessRunner.Result r;
        try {
            r = ProcessRunner.runLiveInUserLocale(dir, timeout, argv, USER_ENV, null, cancel);
        } finally {
            localCommand = null;
        }
        commandLog.record(
                new CommandLog.Entry(argv, r.exit(), r.out(), r.err(), (System.nanoTime() - startNanos) / 1_000_000L));
        return r;
    }

    /**
     * {@code args} with {@code --progress} after a {@code fetch}/{@code pull}/{@code push} subcommand: like
     * clone, they only report progress to a terminal unless told to, and they are streamed
     * ({@link #gitStreamed}). Anything else is returned as it came.
     */
    static String[] withProgress(String... args) {
        if (args.length == 0
                || !Set.of("fetch", "pull", "push").contains(args[0])
                || List.of(args).contains("--progress")) {
            return args;
        }
        String[] out = new String[args.length + 1];
        out[0] = args[0];
        out[1] = "--progress";
        System.arraycopy(args, 1, out, 2, args.length - 1);
        return out;
    }

    /**
     * {@link #gitLogged} for a command long enough to watch: the {@link CommandLog} hears that it started and
     * each line as git writes it, not only the finished transcript.
     */
    private ProcessRunner.Result gitStreamed(Path dir, Duration timeout, String... args) {
        long startNanos = System.nanoTime();
        List<String> argv = gitArgv(args);
        CommandLog log = commandLog;
        ProcessRunner.Cancellation cancel = new ProcessRunner.Cancellation();
        streamedCommand = cancel;
        ProcessRunner.Result r;
        try {
            log.started(argv);
            r = ProcessRunner.runLiveInUserLocale(dir, timeout, argv, USER_ENV, log::progress, cancel);
        } finally {
            streamedCommand = null;
        }
        log.record(new CommandLog.Entry(
                argv, r.exit(), r.out(), r.err(), (System.nanoTime() - startNanos) / 1_000_000L, true));
        return r;
    }

    /** The streamed network command now running — the lane runs one at a time — or null. */
    private volatile ProcessRunner.Cancellation streamedCommand;

    /**
     * Stops the clone, fetch, pull or push that is running; its caller gets a
     * {@link ProcessRunner.Result#cancelled() cancelled} result. False when none is. Git is sent SIGTERM
     * first, on which it removes its lock files and a partial clone. Commands still queued behind it run.
     */
    public boolean cancelNetworkCommand() {
        ProcessRunner.Cancellation cancel = streamedCommand;
        if (cancel == null) {
            return false;
        }
        cancel.cancel();
        return true;
    }

    /**
     * A background read: hardened argv ({@link #backgroundArgv}), never logged, never prompting. Output that
     * did not fit the capture is a failure ({@link #completeOrFailed}).
     */
    private static ProcessRunner.Result git(Path dir, Duration timeout, String... args) {
        // GIT_OPTIONAL_LOCKS=0 so status never blocks on the index lock (git-specific).
        return completeOrFailed(ProcessRunner.run(dir, timeout, backgroundArgv(args), READ_ENV));
    }

    /** The local user command (commit, checkout, reset, stash, …) now running, or null. */
    private volatile ProcessRunner.Cancellation localCommand;

    /**
     * Stops the user command that is running: the clone, fetch, pull or push if there is one
     * ({@link #cancelNetworkCommand}), otherwise the local command — a commit waiting in a hook, a long
     * checkout, reset or stash. Its caller gets a {@link ProcessRunner.Result#cancelled() cancelled} result,
     * and the rest of a multi-command job is not started. False when nothing is running. Git is sent SIGTERM
     * first, on which it removes its lock files; a checkout stopped half-way leaves the files it had already
     * written, exactly as Ctrl-C in a terminal does. Background reads are not user commands and are never
     * the target.
     */
    public boolean cancelRunningCommand() {
        if (cancelNetworkCommand()) {
            return true;
        }
        ProcessRunner.Cancellation cancel = localCommand;
        if (cancel == null) {
            return false;
        }
        cancel.cancel();
        return true;
    }

    /** The {@code err} of a read whose output was larger than the process runner captures. */
    static final String OUTPUT_TOO_LARGE = "git output is too large to read";

    /**
     * {@code r}, unless it succeeded with its stdout cut off at the capture limit: every read here is parsed
     * as a whole (a status, a diff, a blame, a file list), so the part that fit is not a smaller answer but a
     * wrong one — blame for the first three quarters of a file, a change list missing its tail. That becomes
     * a failed result, which every caller already handles and none of them caches. Pure.
     */
    static ProcessRunner.Result completeOrFailed(ProcessRunner.Result r) {
        if (!r.ok() || !r.outTruncated()) {
            return r;
        }
        return new ProcessRunner.Result(1, "", OUTPUT_TOO_LARGE, true, r.errTruncated());
    }

    /** Runs a logged user command with raw {@code stdin} bytes (a blob body must reach Git unre-encoded). */
    private ProcessRunner.Result gitWithInput(
            Path dir, byte[] stdin, List<String> args, Map<String, String> environment) {
        long startNanos = System.nanoTime();
        List<String> argv = new ArrayList<>(GIT_CMD);
        argv.addAll(args);
        ProcessRunner.Result result = ProcessRunner.runWithInputInUserLocale(dir, MUTATION, argv, environment, stdin);
        commandLog.record(new CommandLog.Entry(
                argv, result.exit(), result.out(), result.err(), (System.nanoTime() - startNanos) / 1_000_000L));
        return result;
    }

    /** Clears the cached repo roots (e.g. after switching projects or an external repo change). */
    public void invalidateCaches() {
        rootCache.clear();
        negativeRoots.clear();
        gitDirs.clear();
        statusBackoff.clear();
        Availability availability = gitAvailable;
        if (availability != null && !availability.available()) {
            gitAvailable = null; // a manual refresh asks again at once instead of waiting out the retry
        }
        synchronized (blameCache) {
            blameCache.clear();
        }
        ignoreRevsConfig.clear();
    }

    /**
     * Stops the service on window close. Queued work is dropped and a background read is interrupted, but a
     * user command already running — a commit inside its hooks, a checkout or {@code reset --hard} halfway
     * through the tree, a pull — is left to finish: interrupting it kills the process tree mid-update.
     */
    public void shutdown() {
        closing = true;
        stop(exec, localCommands);
        stop(networkExec, networkCommands);
    }

    private static void stop(ExecutorService lane, AtomicInteger running) {
        if (running.get() > 0) {
            lane.shutdown();
        } else {
            lane.shutdownNow();
        }
    }
}
