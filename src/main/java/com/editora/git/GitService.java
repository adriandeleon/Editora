package com.editora.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;

/**
 * The native-{@code git} facade. Every Git command shells out via {@link ProcessRunner} on a single
 * daemon executor thread (the {@code highlightExecutor} idiom) and posts results back on the JavaFX
 * thread, so the UI thread is never blocked. Git's presence is detected once and cached; repo roots
 * are cached per directory. When Git is absent or a path isn't in a work tree, callers get
 * {@link RepoState#NONE} / {@link GitStatus#NOT_A_REPO} and keep the Git UI hidden.
 *
 * <p>v1 scope: status, gutter diff, staging, commit, branch switch/create, and fetch/pull/push.
 * History/blame/diff-viewer are a later phase.
 */
public final class GitService {

    /** Combined refresh payload: the repo root, its status, and the active file's gutter change map. */
    public record RepoState(Path root, GitStatus status, Map<Integer, ChangeType> changes) {
        public static final RepoState NONE = new RepoState(null, GitStatus.NOT_A_REPO, Map.of());

        public boolean isRepo() {
            return root != null && status.isRepo();
        }
    }

    private static final Duration QUICK = Duration.ofSeconds(10);
    private static final Duration NETWORK = Duration.ofSeconds(120);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "git-service");
        t.setDaemon(true);
        return t;
    });

    /** null = not yet probed; cached after the first {@code git --version}. */
    private volatile Boolean gitAvailable;
    /** Directory (absolute string) → repo root, or {@code null} sentinel cached as the NO_ROOT marker. */
    private final Map<String, Path> rootCache = new ConcurrentHashMap<>();
    private static final Path NO_ROOT = Path.of("");
    /** Bumped per {@link #refresh}; a stale background result is dropped instead of posted to the UI. */
    private final AtomicLong refreshGen = new AtomicLong();

    // --- detection -------------------------------------------------------------------------------

    /** Whether {@code git} is on PATH (probed once on the executor thread, then cached). */
    public boolean gitAvailable() {
        Boolean cached = gitAvailable;
        if (cached != null) {
            return cached;
        }
        boolean ok;
        try {
            ok = ProcessRunner.run(null, QUICK, List.of("git", "--version")).ok();
        } catch (RuntimeException e) {
            ok = false;
        }
        gitAvailable = ok;
        return ok;
    }

    /**
     * Probes {@code git --version} off the FX thread and posts the version string (e.g.
     * {@code "git version 2.54.0"}) on the FX thread, or {@code ""} when git isn't on PATH. Also
     * primes the {@link #gitAvailable()} cache.
     */
    public void version(Consumer<String> onResult) {
        exec.submit(() -> {
            String v = "";
            try {
                ProcessRunner.Result r = ProcessRunner.run(null, QUICK, List.of("git", "--version"));
                gitAvailable = r.ok();
                if (r.ok()) {
                    v = r.out().strip();
                }
            } catch (RuntimeException e) {
                gitAvailable = false;
            }
            String posted = v;
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
        exec.submit(() -> {
            RepoState state = computeRefresh(contextPath, diffFile);
            if (gen == refreshGen.get()) {
                Platform.runLater(() -> onResult.accept(state));
            }
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
        Map<Integer, ChangeType> changes = Map.of();
        if (diffFile != null) {
            changes = diffHead(root, diffFile);
        }
        return new RepoState(root, status, changes);
    }

    private Map<Integer, ChangeType> diffHead(Path root, Path file) {
        // -U0: no context lines, so hunk headers map cleanly to changed line ranges.
        ProcessRunner.Result r = git(root, QUICK, "diff", "--no-color", "-U0", "HEAD", "--",
                file.toAbsolutePath().toString());
        if (!r.ok()) {
            return Map.of(); // untracked / unmerged / new repo with no HEAD: no bars
        }
        return DiffParser.parseToLineMap(r.out());
    }

    /** Diffs a single file against {@code HEAD} for the gutter; posts the change map on the FX thread. */
    public void diff(Path root, Path file, Consumer<Map<Integer, ChangeType>> onResult) {
        exec.submit(() -> {
            Map<Integer, ChangeType> changes =
                    gitAvailable() && root != null && file != null ? diffHead(root, file) : Map.of();
            Platform.runLater(() -> onResult.accept(changes));
        });
    }

    // --- branches --------------------------------------------------------------------------------

    /**
     * One local branch with its tracking info: {@code upstream} (e.g. {@code origin/main}, empty if
     * none), {@code ahead}/{@code behind} commit counts vs the upstream, and {@code gone} when the
     * upstream ref no longer exists.
     */
    public record BranchInfo(String name, String upstream, int ahead, int behind, boolean gone) { }

    /** Local branches (with tracking info), remote branch short-names, and the remote URL (origin's, or
     *  the first remote's; empty when there's no remote) — for the branch popup. */
    public record Branches(List<BranchInfo> local, List<String> remote, String remoteUrl) {
        public static final Branches EMPTY = new Branches(List.of(), List.of(), "");
    }

    /** Lists local ({@code refs/heads}) + remote ({@code refs/remotes}) branches, posted on the FX thread. */
    public void branches(Path root, Consumer<Branches> onResult) {
        exec.submit(() -> {
            Branches result = Branches.EMPTY;
            if (gitAvailable() && root != null) {
                result = new Branches(localBranches(root), remoteBranchNames(root), remoteUrl(root));
            }
            Branches posted = result;
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
        ProcessRunner.Result r = git(root, QUICK, "for-each-ref",
                "--format=%(refname:short)\t%(upstream:short)\t%(upstream:track)", "refs/heads");
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
        return new int[]{ahead, behind};
    }

    // --- clone -----------------------------------------------------------------------------------

    /**
     * Clones {@code url} into {@code destination} (an absolute target path that must not yet exist; its
     * parent must). Runs off the FX thread with the network timeout — the user's git handles
     * credentials/SSH. Posts the {@link ProcessRunner.Result} on the FX thread.
     */
    public void clone(String url, Path destination, Consumer<ProcessRunner.Result> onResult) {
        exec.submit(() -> {
            ProcessRunner.Result r;
            if (!gitAvailable()) {
                r = new ProcessRunner.Result(-1, "", "Git is not installed");
            } else {
                Path parent = destination.toAbsolutePath().getParent();
                r = git(parent, NETWORK, "clone", url, destination.toAbsolutePath().toString());
            }
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    // --- mutations (run a command, post the raw result for status/error reporting) ---------------

    /** Runs an arbitrary {@code git} subcommand in {@code root}, posting the {@link ProcessRunner.Result}. */
    public void run(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        run(root, QUICK, onResult, args);
    }

    /** Network operations (fetch/pull/push) get a longer timeout. */
    public void runNetwork(Path root, Consumer<ProcessRunner.Result> onResult, String... args) {
        run(root, NETWORK, onResult, args);
    }

    private void run(Path root, Duration timeout, Consumer<ProcessRunner.Result> onResult, String... args) {
        exec.submit(() -> {
            ProcessRunner.Result r = gitAvailable() && root != null
                    ? git(root, timeout, args)
                    : new ProcessRunner.Result(-1, "", "Git is not installed");
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    // --- internals -------------------------------------------------------------------------------

    private Path resolveRoot(Path contextPath) {
        Path dir = Files.isDirectory(contextPath) ? contextPath : contextPath.getParent();
        if (dir == null) {
            return null;
        }
        String key = dir.toAbsolutePath().toString();
        Path cached = rootCache.get(key);
        if (cached != null) {
            return cached == NO_ROOT ? null : cached;
        }
        ProcessRunner.Result r = git(dir, QUICK, "rev-parse", "--show-toplevel");
        Path root = null;
        if (r.ok()) {
            String top = r.out().strip();
            if (!top.isEmpty()) {
                root = Path.of(top);
            }
        }
        rootCache.put(key, root == null ? NO_ROOT : root);
        return root;
    }

    private static ProcessRunner.Result git(Path dir, Duration timeout, String... args) {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        for (String a : args) {
            cmd.add(a);
        }
        // GIT_OPTIONAL_LOCKS=0 so status never blocks on the index lock (git-specific).
        return ProcessRunner.run(dir, timeout, cmd, Map.of("GIT_OPTIONAL_LOCKS", "0"));
    }

    /** Clears the cached repo roots (e.g. after switching projects or an external repo change). */
    public void invalidateCaches() {
        rootCache.clear();
    }

    public void shutdown() {
        exec.shutdownNow();
    }
}
