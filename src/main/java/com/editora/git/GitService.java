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
 * UI thread is never blocked. Git's presence is detected once and cached; repo roots are cached per
 * directory. When Git is absent or a path isn't in a work tree, callers get {@link RepoState#NONE} /
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

    /** Combined refresh payload: the repo root, its status, the active file's gutter change map, and a
     *  per-line hunk-text map (for the change-bar hover tooltip). */
    public record RepoState(
            Path root, Path diffFile, GitStatus status, Map<Integer, ChangeType> changes, Map<Integer, String> hunks) {
        public static final RepoState NONE = new RepoState(null, null, GitStatus.NOT_A_REPO, Map.of(), Map.of());

        public RepoState(Path root, GitStatus status, Map<Integer, ChangeType> changes, Map<Integer, String> hunks) {
            this(root, null, status, changes, hunks);
        }

        public boolean isRepo() {
            return root != null && status.isRepo();
        }
    }

    /** A file's gutter diff vs HEAD: per-line {@link ChangeType} (bar color) + per-line hunk text (tooltip). */
    public record GitDiff(Map<Integer, ChangeType> changes, Map<Integer, String> hunks) {
        public static final GitDiff EMPTY = new GitDiff(Map.of(), Map.of());
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

    /** The outcome of {@code git --version} for one configured command. */
    private record Availability(List<String> command, boolean available) {}

    /**
     * null = not yet probed; cached after the first {@code git --version}. It remembers the command it was
     * probed with: {@link #GIT_CMD} is shared by every window's service, so an answer for another command
     * (the path was changed in Settings, through a different window) is stale and is probed again.
     */
    private volatile Availability gitAvailable;

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
     */
    static List<String> commandTokens(String command) {
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

    /**
     * Where completed, <em>user-initiated</em> git commands are reported (the Output "Git" tab).
     * Volatile: installed from the FX thread, read on {@link #exec}.
     */
    private volatile CommandLog commandLog = CommandLog.none();
    /** Directory (absolute string) → repo root. Only successes live here; see {@link #notARepoSince}. */
    private final Map<String, Path> rootCache = new ConcurrentHashMap<>();

    /**
     * How long "not a repository" is believed for a directory. Long enough that a burst of refreshes in a
     * plain folder costs one {@code rev-parse}, short enough that a {@code git init} or clone made in a
     * terminal is seen by the refresh that runs when the window regains focus.
     */
    static final Duration NOT_A_REPO_TTL = Duration.ofSeconds(2);

    /** Directory (absolute string) → {@link System#nanoTime()} at which git last said "not a repository". */
    private final Map<String, Long> notARepoSince = new ConcurrentHashMap<>();
    /** Bumped per {@link #refresh}; a stale background result is dropped instead of posted to the UI. */
    private final AtomicLong refreshGen = new AtomicLong();

    // --- detection -------------------------------------------------------------------------------

    /** Whether {@code git} is on PATH (probed once on the executor thread, then cached). */
    public boolean gitAvailable() {
        List<String> command = GIT_CMD;
        Availability cached = gitAvailable;
        if (cached != null && cached.command().equals(command)) {
            return cached.available();
        }
        probeVersion(command);
        Availability probed = gitAvailable;
        return probed != null && probed.available();
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
            }
        } catch (RuntimeException e) {
            ok = false;
        }
        gitAvailable = new Availability(command, ok);
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
            RepoState state = computeRefresh(contextPath, diffFile);
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
            RepoState state = computeRefresh(contextPath, null);
            Platform.runLater(() -> onResult.accept(state));
        });
    }

    private RepoState computeRefresh(Path contextPath, Path diffFile) {
        if (!gitAvailable() || contextPath == null) {
            return RepoState.NONE;
        }
        Path root = resolveRoot(contextPath);
        if (root == null) {
            return RepoState.NONE;
        }
        ProcessRunner.Result st = git(root, QUICK, "status", "--porcelain=v2", "--branch");
        if (!st.ok()) {
            return RepoState.NONE;
        }
        GitStatus status = StatusParser.parse(st.out());
        GitDiff diff = diffFile != null ? diffHead(root, diffFile) : GitDiff.EMPTY;
        return new RepoState(root, diffFile, status, diff.changes(), diff.hunks());
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
        return new GitDiff(DiffParser.parseToLineMap(r.out()), DiffParser.parseToHunkText(r.out()));
    }

    /** Diffs a single file against {@code HEAD} for the gutter; posts the change + hunk maps on the FX thread. */
    public void diff(Path root, Path file, Consumer<GitDiff> onResult) {
        submit(exec, () -> {
            GitDiff diff = gitAvailable() && root != null && file != null ? diffHead(root, file) : GitDiff.EMPTY;
            Platform.runLater(() -> onResult.accept(diff));
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
     * Annotates every line of {@code file} via {@code git blame --line-porcelain}, parsed by the pure
     * {@link BlameParser}. Posts the per-line list (file order) on the FX thread, or an empty list when
     * git is absent / the file isn't tracked.
     */
    public void blame(Path root, Path file, Consumer<List<BlameParser.BlameLine>> onResult) {
        submit(exec, () -> {
            List<BlameParser.BlameLine> lines = List.of();
            if (gitAvailable() && root != null && file != null) {
                ProcessRunner.Result r = git(
                        root,
                        QUICK,
                        GitSafety.LITERAL_PATHSPECS,
                        "blame",
                        "--line-porcelain",
                        "--",
                        file.toAbsolutePath().toString());
                if (r.ok()) {
                    lines = BlameParser.parse(r.out());
                }
            }
            List<BlameParser.BlameLine> posted = lines;
            Platform.runLater(() -> onResult.accept(posted));
        });
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

    /** Lists the working-tree stashes via {@code git stash list}, parsed by the pure {@link StashParser}. */
    public void stashList(Path root, Consumer<List<StashParser.StashEntry>> onResult) {
        submit(exec, () -> {
            List<StashParser.StashEntry> list = List.of();
            if (gitAvailable() && root != null) {
                ProcessRunner.Result r = git(root, QUICK, "stash", "list");
                if (r.ok()) {
                    list = StashParser.parse(r.out());
                }
            }
            List<StashParser.StashEntry> posted = list;
            Platform.runLater(() -> onResult.accept(posted));
        });
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
        if (root == null || file == null) {
            return null;
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
     * The {@code git push} argv for the current branch. A brand-new branch has no upstream, so a bare
     * {@code git push} fails; in that case we push with {@code --set-upstream origin <branch>} so the
     * first push "just works" (matching {@code push.autoSetupRemote}). Subsequent pushes (an upstream is
     * already tracked) use a plain {@code push}. A blank/unknown branch name also falls back to a plain
     * {@code push} — we never emit {@code --set-upstream origin} with an empty branch. Pure — unit-tested.
     */
    public static String[] pushArgs(String branch, String upstream) {
        boolean noUpstream = upstream == null || upstream.isBlank();
        // A detached HEAD is reported as "(detached)" (non-blank), and no real branch name can start with
        // "(" — so guard against it, else we'd emit `push --set-upstream origin (detached)`, which git
        // rejects as a bad refname. A plain `push` lets git give its own clearer "detached HEAD" message.
        boolean haveBranch = branch != null && !branch.isBlank() && !branch.startsWith("(");
        if (noUpstream && haveBranch) {
            return new String[] {"push", "--set-upstream", "origin", branch};
        }
        return new String[] {"push"};
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
    public record Branches(List<BranchInfo> local, List<String> remote, String remoteUrl) {
        public static final Branches EMPTY = new Branches(List.of(), List.of(), "");
    }

    /** Lists local ({@code refs/heads}) + remote ({@code refs/remotes}) branches, posted on the FX thread. */
    public void branches(Path root, Consumer<Branches> onResult) {
        submit(exec, () -> {
            Branches result = Branches.EMPTY;
            if (gitAvailable() && root != null) {
                result = new Branches(localBranches(root), remoteBranchNames(root), remoteUrl(root));
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

    // --- clone -----------------------------------------------------------------------------------

    /**
     * {@code clone -- <url> <destination>}. The URL is pasted text: without the {@code --} a value beginning
     * with {@code -} is an option, and {@code --upload-pack=<program>} runs that program.
     */
    static String[] cloneArgs(String url, String destination) {
        return new String[] {"clone", "--", url == null ? "" : url, destination};
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
                        () -> gitLogged(
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
     * local commands requested before them ({@link #submitNetworkInRequestOrder}).
     */
    public void runNetwork(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        submitNetworkInRequestOrder(() -> {
            ProcessRunner.Result r = gitAvailable() && root != null
                    ? userCommand(networkCommands, () -> gitLogged(root, NETWORK, args))
                    : NOT_INSTALLED;
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Runs a working-tree mutation on the service's serial command executor. */
    public void runWorktreeMutation(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        runWorktreeMutation(exec, localCommands, root, MUTATION, java.util.Collections.singletonList(args), onResult);
    }

    /** Runs several related working-tree commands as one serial executor job. */
    public void runWorktreeMutation(Path root, List<String[]> commands, Consumer<ProcessRunner.Result> onResult) {
        runWorktreeMutation(exec, localCommands, root, MUTATION, commands, onResult);
    }

    /** Network form used by pull; fetch and push do not need the working-tree boundary. */
    public void runNetworkWorktreeMutation(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        runWorktreeMutation(
                networkExec, networkCommands, root, NETWORK, java.util.Collections.singletonList(args), onResult);
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
        submit(exec, () -> {
            List<String> base = new ArrayList<>();
            base.add("apply");
            if (cached) {
                base.add("--cached");
            }
            List<String> check = new ArrayList<>(base);
            check.add("--check");
            ProcessRunner.Result posted = userCommand(localCommands, () -> {
                ProcessRunner.Result checked = gitWithInput(root, patch, check);
                return checked.ok() ? gitWithInput(root, patch, base) : checked;
            });
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
     * existing file mode. The standard index.lock makes publication a compare-and-swap with external Git
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
        ProcessRunner.Result hashed = gitWithInput(
                root,
                body,
                entry.found()
                        ? List.of("hash-object", "-w", "--no-filters", "--stdin")
                        : List.of("hash-object", "-w", "--stdin", "--path=" + path),
                USER_ENV);
        String id = hashed.out().strip();
        if (!hashed.ok() || !OBJECT_ID.matcher(id).matches()) {
            return hashed.ok() ? new ProcessRunner.Result(1, "", "Git returned no blob id") : hashed;
        }
        String mode = entry.found() ? entry.mode() : "100644";
        return gitWithInput(
                root, new byte[0], List.of("update-index", "--add", "--cacheinfo", mode + "," + id + "," + path), env);
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
            Consumer<ProcessRunner.Result> onResult) {
        if (lane == networkExec) {
            submitNetworkInRequestOrder(() -> mutateWorktree(running, root, timeout, commands, onResult, false));
            return;
        }
        submit(lane, () -> {
            if (parkedMutations.get() == 0 && worktreeLock.tryLock()) {
                mutateWorktree(running, root, timeout, commands, onResult, true);
                return;
            }
            parkedMutations.incrementAndGet();
            boolean queued = submit(networkExec, () -> {
                try {
                    mutateWorktree(networkCommands, root, timeout, commands, onResult, false);
                } finally {
                    parkedMutations.decrementAndGet();
                }
            });
            if (!queued) {
                parkedMutations.decrementAndGet();
            }
        });
    }

    /** Runs {@code commands} holding {@link #worktreeLock} ({@code locked}: the caller already took it). */
    private void mutateWorktree(
            AtomicInteger running,
            Path root,
            Duration timeout,
            List<String[]> commands,
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
                    ProcessRunner.Result combined = new ProcessRunner.Result(0, "", "");
                    for (String[] command : commands) {
                        ProcessRunner.Result current = gitLogged(root, timeout, command);
                        if (combined.ok()) {
                            combined = current;
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

    private Path resolveRoot(Path contextPath) {
        Path dir = Files.isDirectory(contextPath) ? contextPath : contextPath.getParent();
        if (dir == null) {
            return null;
        }
        String key = dir.toAbsolutePath().toString();
        Path cached = rootCache.get(key);
        if (cached != null) {
            return cached;
        }
        Long since = notARepoSince.get(key);
        if (since != null && System.nanoTime() - since < NOT_A_REPO_TTL.toNanos()) {
            return null;
        }
        ProcessRunner.Result r = git(dir, QUICK, "rev-parse", "--show-toplevel");
        Path root = null;
        if (r.ok()) {
            String top = r.out().strip();
            if (!top.isEmpty()) {
                root = Path.of(top);
            }
        }
        if (root != null) {
            rootCache.put(key, root);
            notARepoSince.remove(key);
        } else if (isNotARepository(r)) {
            // Only git's own "not a repository", and only briefly: the folder can become one at any time
            // (git init / clone in a terminal). A timeout or a directory that does not exist yet says
            // nothing about the folder and is asked again next time.
            notARepoSince.put(key, System.nanoTime());
        }
        return root;
    }

    /** Whether a failed {@code rev-parse} is git's definite "not a repository" (read in the C locale). */
    static boolean isNotARepository(ProcessRunner.Result r) {
        return r.exit() == 128 && r.err() != null && r.err().contains("not a git repository");
    }

    /**
     * A user-initiated command: the user's own git — hooks and repository configuration included — plus a
     * {@link CommandLog} report. Background reads go through {@link #git} instead.
     */
    private ProcessRunner.Result gitLogged(Path dir, Duration timeout, String... args) {
        long startNanos = System.nanoTime();
        List<String> argv = gitArgv(args);
        // The user's locale, not LC_ALL=C: the hooks this runs are the user's programs (see GitSafety.userEnv).
        ProcessRunner.Result r = ProcessRunner.runInUserLocale(dir, timeout, argv, USER_ENV);
        commandLog.record(
                new CommandLog.Entry(argv, r.exit(), r.out(), r.err(), (System.nanoTime() - startNanos) / 1_000_000L));
        return r;
    }

    /** A background read: hardened argv ({@link #backgroundArgv}), never logged, never prompting. */
    private static ProcessRunner.Result git(Path dir, Duration timeout, String... args) {
        // GIT_OPTIONAL_LOCKS=0 so status never blocks on the index lock (git-specific).
        return ProcessRunner.run(dir, timeout, backgroundArgv(args), READ_ENV);
    }

    private ProcessRunner.Result gitWithInput(Path dir, String stdin, List<String> args) {
        byte[] bytes = (stdin == null ? "" : stdin).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return gitWithInput(dir, bytes, args, USER_ENV);
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
        notARepoSince.clear();
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
