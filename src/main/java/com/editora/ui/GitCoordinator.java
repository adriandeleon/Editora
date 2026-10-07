package com.editora.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.DirectoryChooser;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editor.BlameInfo;
import com.editora.editor.EditorBuffer;
import com.editora.git.BlameHeatmap;
import com.editora.git.BlameParser;
import com.editora.git.GitChangeBars;
import com.editora.git.GitConflicts;
import com.editora.git.GitFormat;
import com.editora.git.GitOperation;
import com.editora.git.GitPullMode;
import com.editora.git.GitSafety;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.RelativeTime;
import com.editora.process.ProcessRunner;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The stateful core of the Git integration: it owns the {@link GitService} (the off-thread CLI facade) plus
 * the current repo state (root / branch / upstream) and the state machine that keeps the status bar, the
 * Commit / Log tool windows, the active buffer's gutter change bars, and its inline blame annotations in
 * sync with what's on disk.
 *
 * <p>It also owns the user-facing Git <em>mutation operations</em> — {@code gitOp}/{@code gitCommit}/
 * {@code discardChanges}, branch checkout/create, fetch/pull/push ({@code gitSync}), the stash commands, the
 * active-file stage/unstage/discard, the {@code gitError} dialog, and {@code git.clone} (the URL+destination
 * form → {@code git clone} → open a file from the clone via {@link #cloneRepo()}) — which run their commands
 * through the same {@link #service()}/{@link #repoRoot()}/{@code afterMutation()} engine. {@code MainController}
 * keeps the Git tool windows + panels ({@code GitPanel}/{@code GitLogPanel}) and the {@code BranchPopup}, plus
 * the Git Log / blame-click→commit-diff flows (whose callbacks delegate to {@code git.*}), and the
 * {@code git.*} command registrations. Everything the engine needs from the window goes through the shared
 * {@link CoordinatorHost} (settings, simple-mode gate, active buffer, theme brightness, per-buffer iteration,
 * status, prompts, the overlay host) plus a small git-specific {@link WindowOps} for the surfaces it drives.
 *
 * <p>The {@code applyState}/{@code applySupport} state machine is pinned by {@code GitStateFxTest}.
 */
final class GitCoordinator {

    /** Git-specific window surfaces the engine drives, beyond the shared {@link CoordinatorHost}. */
    interface WindowOps {
        void setStatusBarGitEnabled(boolean enabled);

        void setStatusBarBranch(String branch, int ahead, int behind);

        void setCommitWindowAvailable(boolean available);

        void setGitLogWindowAvailable(boolean available);

        void setGitPanelStatus(GitStatus status);

        /**
         * The merge / rebase / cherry-pick / revert the repository is in the middle of
         * ({@link GitOperation#NONE} for none) and how many files are still unmerged: the status-bar segment
         * and the Commit window's banner. Sent before {@link #setGitPanelStatus} on every refresh.
         */
        default void setGitOperation(GitOperation operation, int conflicts) {}

        /**
         * Git's reason for refusing to work in the folder ({@code ""} when it does not): shown in the
         * status-bar segment and as the Commit window's placeholder instead of "not a repository".
         */
        default void setGitRefusal(String reason) {}

        /**
         * Commits with the message typed in the Commit window, as its button does; false when the box is
         * empty or a commit is already running (the caller then lets git use its prepared message).
         */
        default boolean commitFromPanel() {
            return false;
        }

        /** Pushes the per-file Git working-tree status to the Project tree for IntelliJ-style file coloring. */
        void setProjectGitStatus(java.util.Map<java.nio.file.Path, com.editora.git.GitFileStatus> byPath);

        /** Re-diffs any open diff tabs (a mutation moved HEAD/index/working). */
        void refreshOpenDiffs();

        /** The active project's root folder, or {@code null} when no project is open. */
        Path projectRoot();

        /** Invalidates queued or staged editor writes before Git mutates the same working-tree path. */
        void invalidatePendingWrite(Path file);

        /** Extends write invalidation to buffers owned by other application windows. */
        default void invalidatePendingWritesInOtherWindows(Path root, List<String> pathspecs) {}

        /** After a branch switch / pull, silently reload any open buffer whose file changed on disk. */
        void reloadAllFromDiskSilently();

        /**
         * Saves {@code buffer} through the window's ordinary save path (encoding, line endings, format on
         * save) and waits for the write; false when it is not on disk afterwards.
         */
        boolean saveBeforeGit(EditorBuffer buffer);

        /** Opens the Commit tool window. */
        void openCommitWindow();

        /** Focuses the Commit tool window's message box (deferred onto the FX thread). */
        void focusCommitMessage();

        /** This window's active keymap (for installing caret navigation on the clone form's text fields). */
        com.editora.command.KeymapManager keymap();

        /** Opens (or focuses) the tab for {@code file} (used to open a file from a fresh clone). */
        void openPath(Path file);

        /** Re-syncs the Settings window's "inline blame" checkbox after the blame toggle. */
        void syncBlameCheck();

        /** Opens a read-only diff of {@code repoRel} at {@code hash} vs its parent (a blame-annotation click).
         *  Routed through the window to reach {@code DiffCoordinator} without a circular dependency. */
        void openCommitFileDiff(String hash, String repoRel, String origRepoRel);
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final GitService service = new GitService();

    /** Per buffer: commit hash → {the file's path in that commit, its path in the parent} (from blame). */
    private final Map<EditorBuffer, Map<String, String[]>> blamePaths = new java.util.WeakHashMap<>();

    private Path repoRoot;
    /** Complete status snapshot paired with {@link #repoRoot}; consumed by repository-wide diff review. */
    private GitStatus lastStatus = GitStatus.NOT_A_REPO;
    /** The last computed per-file status (absolute normalized path → status); used by the Project-tree
     *  actions to tell whether a file is untracked (so Revert = clean vs. checkout). */
    private java.util.Map<Path, com.editora.git.GitFileStatus> lastStatusByPath = java.util.Map.of();

    /** Puts a file's diff against HEAD on its buffer's gutter. */
    interface GutterSink {
        void show(EditorBuffer buffer, GitService.GitDiff diff);
    }

    private GutterSink gutterSink =
            (b, diff) -> b.setChangeBars(GitChangeBars.cssClassesByLine(diff.changes()), diff.hunks());

    /** Replaces how a diff reaches a buffer; the editor's hunk commands also take the hunks from it. */
    void setGutterSink(GutterSink sink) {
        gutterSink = sink;
    }

    private String branchName = "";
    private java.util.function.BiConsumer<Path, String> repositoryListener = (root, branch) -> {};
    private Runnable mutationListener = () -> {};
    private String upstream = "";
    /** The multi-step operation the active repository is in the middle of; pairs with {@link #repoRoot}. */
    private GitOperation operation = GitOperation.NONE;

    private boolean supportApplied;
    /** Replaces the body of {@link #gitPush} once the branch coordinator exists; null in a bare engine. */
    Runnable pushHandler;

    GitCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
    }

    /** The off-thread Git CLI facade (operations in {@code MainController} run their commands through it). */
    GitService service() {
        return service;
    }

    /** The window surfaces shared with {@link GitBranchCoordinator}, which is built from this engine. */
    CoordinatorHost host() {
        return host;
    }

    /** The active repo root, or {@code null} when the current context isn't inside a repo. */
    Path repoRoot() {
        return repoRoot;
    }

    /** The most recently applied repository status, or {@link GitStatus#NOT_A_REPO}. */
    GitStatus status() {
        return lastStatus;
    }

    String branchName() {
        return branchName;
    }

    String upstream() {
        return upstream;
    }

    /** The merge, rebase, cherry-pick or revert in progress in the active repository, as of the last refresh. */
    GitOperation operation() {
        return operation;
    }

    /** Whether the abort / continue / skip commands have an operation to act on (palette and menu gating). */
    boolean operationInProgress() {
        return repoRoot != null && operation.canContinue();
    }

    /** Whether the Git integration is enabled in Settings (default off). Off in Simple UI mode. */
    boolean isEnabled() {
        // Simple UI mode disables Git (status-bar VCS segment, gutter change bars, Commit window); saved setting
        // unchanged.
        return host.settings().isGitSupport() && !host.simpleModeActive();
    }

    /** True when Git actions can actually run: the feature is on AND the active context is inside a repo
     *  (the "No VCS" state has no repo). Drives whether repo-only menu items are shown/enabled. */
    boolean isAvailable() {
        return isEnabled() && repoRoot != null;
    }

    /** Whether the Commit / Log tool windows have a Git context on {@code selected} (see {@link GitWindowGate}). */
    boolean windowsAllowed(javafx.scene.control.Tab selected) {
        return GitWindowGate.allows(selected, ops.projectRoot() != null);
    }

    /** Runs {@code action} only when Git is enabled; otherwise reports it (disables the keybinding/command). */
    void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.gitDisabled"));
        }
    }

    /**
     * Guard shared by every repo-only operation: when there's no active repo, echoes the standard
     * "not a repo" / "git not installed" status and returns {@code true} so the caller early-returns.
     * Returns {@code false} (no echo) when a repo is present.
     */
    /**
     * Told (on the FX thread) whenever a refresh has applied the active repository root and branch — the
     * Git Log uses it to stop listing the commits of a repository that is no longer the active one.
     */
    void onRepositoryChanged(java.util.function.BiConsumer<Path, String> listener) {
        repositoryListener = listener;
    }

    /** Told (on the FX thread) at every {@link #afterMutation()} — the Git Log reloads an open history. */
    void onMutation(Runnable listener) {
        mutationListener = listener;
    }

    boolean reportIfNoRepo() {
        if (repoRoot != null) {
            return false;
        }
        host.setStatus(tr(service.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
        return true;
    }

    /** The path whose repo drives the Git UI: the active file, else the active project root, else null. */
    Path contextPath() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null) {
            return file;
        }
        return ops.projectRoot();
    }

    /**
     * Reconciles all Git UI with the "Enable Git" setting. When off: the status-bar VCS segment is
     * disabled, the Commit tool window is hidden, every open buffer's gutter change bars are cleared,
     * and commands/keybindings no-op. When on, repopulates on the off→on transition (other triggers
     * keep it fresh thereafter). Runs at startup and on every settings apply.
     */
    void applySupport() {
        // Push the configured git command first (blank = PATH git) — changing it invalidates the
        // service's availability cache, so the re-probe below sees the new command.
        service.setCommand(host.settings().getGitPath());
        boolean on = isEnabled();
        ops.setStatusBarGitEnabled(on);
        if (!on) {
            service.invalidateRefreshes();
            ops.setCommitWindowAvailable(false);
            ops.setGitLogWindowAvailable(false);
            repoRoot = null;
            lastStatus = GitStatus.NOT_A_REPO;
            branchName = "";
            upstream = "";
            operation = GitOperation.NONE;
            repositoryListener.accept(null, "");
            ops.setGitRefusal("");
            ops.setGitOperation(GitOperation.NONE, 0);
            ops.setGitPanelStatus(null);
            lastStatusByPath = java.util.Map.of();
            ops.setProjectGitStatus(java.util.Map.of()); // Git turned off → clear the Project tree coloring
            host.forEachBuffer(b -> {
                b.setChangeBars(null);
                b.setBlame(null);
            });
        } else if (!supportApplied) {
            refresh(); // off→on: populate status bar + Commit window + active gutter
        }
        supportApplied = on;
    }

    /**
     * Recomputes Git status (status bar + tool window) and the active file's gutter change bars, all off
     * the FX thread via {@link GitService}. Cheap to over-call: stale results are dropped by the service's
     * generation guard, and nothing runs when Git is absent / not a repo.
     */
    void refresh() {
        if (!isEnabled()) {
            return;
        }
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        Path context = contextPath();
        // No active file and no project context (e.g. the Welcome tab in a No-Project window): show no Git
        // rather than falling back to the process working directory's repo.
        if (context == null) {
            service.invalidateRefreshes();
            applyState(GitService.RepoState.NONE);
            return;
        }
        // Git shells out to a local process — a remote (SFTP) context has no local repo.
        if (!Vfs.isLocal(context)) {
            service.invalidateRefreshes();
            applyState(GitService.RepoState.NONE);
            return;
        }
        // Only diff a real, non-huge file (huge files disable the gutter anyway). A narrowed buffer shows
        // region-relative lines, so whole-file change bars would land on the wrong ones: it gets none.
        Path diffFile = (file != null && !b.isLargeFile() && !b.isNarrowed()) ? file : null;
        service.refresh(context, diffFile, this::applyState);
    }

    /** Applies a completed Git refresh to the status bar, tool window, and active buffer's gutter. */
    void applyState(GitService.RepoState state) {
        repoRoot = state.root();
        lastStatus = state.isRepo() ? state.status() : GitStatus.NOT_A_REPO;
        // Before the window surfaces below are told: they re-evaluate menu and palette enablement, which asks
        // whether an operation is in progress.
        operation = state.isRepo() ? state.operation() : GitOperation.NONE;
        EditorBuffer b = host.activeBuffer();
        // The Log needs a repository. The Commit window does not: outside one it shows "Not a Git repository"
        // with a Clone button, which is the way in. (Transient; the user's show/hide preference is untouched.)
        ops.setCommitWindowAvailable(isEnabled());
        ops.setGitLogWindowAvailable(state.isRepo());
        if (!state.isRepo()) {
            branchName = "";
            upstream = "";
            repositoryListener.accept(null, "");
            ops.setStatusBarBranch(null, 0, 0);
            // A folder git refuses to work in (dubious ownership, a bare repository, a corrupt index) is not
            // "no repository": the segment and the Commit window say what git said.
            ops.setGitRefusal(state.refusal());
            ops.setGitOperation(GitOperation.NONE, 0);
            ops.setGitPanelStatus(null);
            lastStatusByPath = java.util.Map.of();
            ops.setProjectGitStatus(java.util.Map.of()); // clear the tree's file coloring (outside a repo / Git off)
            if (b != null) {
                b.setChangeBars(null);
                b.setBlame(null);
            }
            return;
        }
        var status = state.status();
        branchName = status.branch();
        upstream = status.upstream();
        repositoryListener.accept(repoRoot, branchName);
        ops.setStatusBarBranch(status.branch(), status.ahead(), status.behind());
        ops.setGitRefusal("");
        ops.setGitOperation(operation, GitConflicts.unmerged(status).size());
        ops.setGitPanelStatus(status);
        lastStatusByPath = com.editora.git.GitFileStatus.byPath(status, state.root());
        ops.setProjectGitStatus(lastStatusByPath); // color the tree
        if (b != null && b.isNarrowed()) {
            b.setChangeBars(null, null); // line numbers refer to the whole file, the view to the region
        } else if (b != null
                && b.getPath() != null
                && (state.diffFile() == null
                        || com.editora.config.PathKeys.sameNormalized(b.getPath(), state.diffFile()))) {
            // An empty map still marks the buffer as tracked (reserves the slot); hunk text feeds the
            // change-bar hover tooltip.
            gutterSink.show(b, new GitService.GitDiff(state.changes(), state.hunks(), state.hunkList()));
        }
        refreshBlame(b); // inline blame for the active file (no-op + clears when blame is off)
    }

    /**
     * Refreshes the whole Git UI after a mutation (commit/stage/discard/checkout/pull/push): the status
     * bar, the Commit tool window, and the active gutter (via {@link #refresh()}), plus the gutter of
     * <em>every other open buffer in the same repo</em> (so committing clears bars on background tabs
     * too, not just the visible one). Off the UI thread; bounded by the number of open tabs and only
     * runs on user-initiated git actions, so it's off the hot paths.
     */
    void afterMutation() {
        refresh(); // status bar + tool window + active buffer's gutter
        mutationListener.run();
        Path root = repoRoot;
        if (root == null) {
            return;
        }
        EditorBuffer active = host.activeBuffer();
        host.forEachBuffer(buf -> {
            // The active buffer is handled by refresh(); skip non-file/huge buffers + files outside this repo.
            if (buf.isNarrowed()
                    || !GitChangeBars.shouldRediff(buf.getPath(), root, buf == active, buf.isLargeFile())) {
                return;
            }
            Path path = buf.getPath().toAbsolutePath();
            service.diff(root, path, diff -> {
                if (!buf.isDisposed()
                        && !buf.isNarrowed()
                        && buf.getPath() != null
                        && com.editora.config.PathKeys.sameNormalized(buf.getPath(), path)) {
                    gutterSink.show(buf, diff);
                }
            });
        });
        ops.refreshOpenDiffs(); // a commit/stage/checkout changes HEAD/index/working → re-diff open diff tabs
    }

    void invalidateCaches() {
        service.invalidateCaches();
    }

    void shutdown() {
        service.shutdown();
    }

    // --- Inline blame annotations (IntelliJ-style gutter column) ---------------------------------

    /** Whether blame annotations are effectively on (Git enabled + the setting + not Simple mode). */
    boolean isBlameEnabled() {
        return isEnabled() && host.settings().isGitBlameInline();
    }

    /** Pushes blame to the active buffer (and clears it everywhere else); runs on init / settings apply /
     *  tab switch / git mutation. Only the focused buffer is annotated (blame is one git call per file). */
    void applyBlame() {
        EditorBuffer active = host.activeBuffer();
        host.forEachBuffer(b -> {
            if (b != active) {
                b.setBlame(null);
            }
        });
        refreshBlame(active);
    }

    /** Toggles inline blame annotations (palette + {@code M-g a}); persists the setting and re-applies. */
    void toggleBlame() {
        ifEnabled(() -> {
            var s = host.settings();
            s.setGitBlameInline(!s.isGitBlameInline());
            host.requestSave();
            applyBlame();
            ops.syncBlameCheck();
            host.setStatus(tr("status.toggle.gitBlame", tr(s.isGitBlameInline() ? "common.on" : "common.off")));
        });
    }

    /** Annotates the active buffer — enables inline blame if it's off (the project-tree "Annotate" action). */
    void annotateActive() {
        ifEnabled(() -> {
            var s = host.settings();
            if (!s.isGitBlameInline()) {
                s.setGitBlameInline(true);
                host.requestSave();
                ops.syncBlameCheck();
            }
            applyBlame();
        });
    }

    /** Opens the read-only diff of the active file at the caret line's commit vs its parent. */
    void blameShowCommit() {
        EditorBuffer b = host.activeBuffer();
        showBlameCommit(b, b == null ? null : b.blameHashAtCaret());
    }

    /** Clicking a line's blame annotation opens that line's commit (IntelliJ-style). */
    void onGutterBlameClick(EditorBuffer buffer, int line) {
        showBlameCommit(buffer, buffer == null ? null : buffer.blameHashAt(line));
    }

    /** Opens the read-only diff of {@code b}'s file at {@code hash} vs its parent (shared by the caret
     *  command and the gutter-annotation click). */
    private void showBlameCommit(EditorBuffer b, String hash) {
        if (b == null || b.getPath() == null || repoRoot == null) {
            return;
        }
        if (hash == null || hash.isBlank()) {
            host.setStatus(tr("status.git.noBlameLine"));
            return;
        }
        String rel = GitService.repoRelative(repoRoot, b.getPath());
        if (rel != null) {
            // Blame follows renames: a line older than a move belongs to the file's OLD path in its commit.
            String[] blamed = blamePaths.getOrDefault(b, Map.of()).get(hash);
            String at = blamed == null || blamed[0] == null ? rel : blamed[0];
            ops.openCommitFileDiff(hash, at, blamed == null ? null : blamed[1]);
        }
    }

    /** Fetches blame for {@code b} off-thread and pushes formatted annotations (or clears when ineligible). */
    void refreshBlame(EditorBuffer b) {
        if (b == null) {
            return;
        }
        if (!isBlameEnabled() || b.getPath() == null || b.isLargeFile() || !host.isLocalBuffer(b) || repoRoot == null) {
            b.setBlame(null);
            return;
        }
        Path file = b.getPath();
        service.blameLatest(repoRoot, file, lines -> {
            if (host.activeBuffer() != b) {
                return; // the user switched tabs while blame ran
            }
            Map<String, String[]> paths = new HashMap<>();
            for (BlameParser.BlameLine line : lines) {
                paths.putIfAbsent(line.hash(), new String[] {line.path(), line.previousPath()});
            }
            blamePaths.put(b, paths);
            b.setBlame(toBlameInfos(lines));
        });
    }

    /** Maps git blame into the per-line annotation column (author + date, full-commit tooltip, age-heatmap
     *  background). The heatmap is scaled across this file's oldest→newest committed lines and tinted for
     *  the current theme. Uncommitted lines get a label only (no heatmap, no commit to open). */
    private List<BlameInfo> toBlameInfos(List<BlameParser.BlameLine> lines) {
        long now = System.currentTimeMillis() / 1000L;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (BlameParser.BlameLine bl : lines) {
            if (!bl.uncommitted()) {
                min = Math.min(min, bl.epochSeconds());
                max = Math.max(max, bl.epochSeconds());
            }
        }
        boolean dark = host.appThemeDark();
        List<BlameInfo> out = new ArrayList<>(lines.size());
        for (BlameParser.BlameLine bl : lines) {
            if (bl.uncommitted()) {
                String label = tr("blame.uncommitted");
                out.add(new BlameInfo(label, "", label, "", ""));
                continue;
            }
            String date = blameDate(bl.epochSeconds());
            String shortHash = bl.hash().substring(0, Math.min(8, bl.hash().length()));
            String tooltip = tr(
                    "blame.tooltip",
                    bl.author(),
                    date,
                    relativeTimeLabel(bl.epochSeconds(), now),
                    bl.summary(),
                    shortHash);
            double intensity = BlameHeatmap.intensity(bl.epochSeconds(), min, max);
            out.add(new BlameInfo(
                    GitFormat.shortAuthor(bl.author()),
                    date,
                    tooltip,
                    BlameHeatmap.heatmapColor(intensity, dark),
                    bl.hash()));
        }
        return out;
    }

    /** ISO {@code yyyy-MM-dd} commit date for the annotation column (technical, not localized). */
    private static String blameDate(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .toString();
    }

    /** Localized "N days ago"-style label from the pure {@link RelativeTime} bucketing. */
    private static String relativeTimeLabel(long epochSeconds, long nowSeconds) {
        RelativeTime.Span span = RelativeTime.of(epochSeconds, nowSeconds);
        long v = span.value();
        return switch (span.unit()) {
            case NOW -> tr("blame.now");
            case MINUTES -> tr("blame.minutesAgo", v);
            case HOURS -> tr("blame.hoursAgo", v);
            case DAYS -> tr("blame.daysAgo", v);
            case WEEKS -> tr("blame.weeksAgo", v);
            case MONTHS -> tr("blame.monthsAgo", v);
            case YEARS -> tr("blame.yearsAgo", v);
        };
    }

    // --- user-facing operations (commit / branch / stash / discard) --------------------------------

    /** Runs a git command, reporting success/failure + refreshing state afterward. */
    void gitOp(String successMessage, String... args) {
        if (reportIfNoRepo()) {
            return;
        }
        gitOpIn(repoRoot, successMessage, args);
    }

    /** {@link #gitOp} in the repository the caller resolved its arguments against. */
    private void gitOpIn(Path root, String successMessage, String... args) {
        service.runWorktreeMutation(
                root,
                running(args, r -> {
                    if (r.ok()) {
                        host.setStatus(successMessage);
                    } else {
                        gitError(tr("status.git.opFailed"), r.message());
                    }
                    afterMutation();
                }),
                args);
    }

    /**
     * Writes the unsaved buffers of {@code root} that {@code pathspecs} select (all of them when empty) to
     * disk before git reads those files. git only ever sees the saved copy, so staging or committing a file
     * that is still being edited silently recorded its <em>previous</em> text. Each buffer goes through the
     * window's normal save; one that cannot be saved stops the operation, with the reason in the status bar.
     *
     * @return false when a buffer could not be saved — the caller must not run its command
     */
    private boolean saveUnsaved(Path root, List<String> pathspecs) {
        List<EditorBuffer> unsaved = new ArrayList<>();
        host.forEachBuffer(buffer -> {
            Path file = buffer.getPath();
            if (file == null || !buffer.isDirty() || !Vfs.isLocal(file)) {
                return;
            }
            String relative = GitService.repoRelative(root, file);
            if (relative != null && (pathspecs.isEmpty() || pathspecs.stream().anyMatch(p -> selects(p, relative)))) {
                unsaved.add(buffer);
            }
        });
        for (EditorBuffer buffer : unsaved) {
            if (!ops.saveBeforeGit(buffer)) {
                host.setError(tr("status.git.unsavedFailed", buffer.getTitle()));
                return false;
            }
        }
        return true;
    }

    /**
     * Shows {@code git <subcommand>…} in the status bar's background-task segment until the command reports
     * back. A commit inside slow hooks, a checkout of a large tree or a push to a slow remote can run for
     * minutes; without this the editor looked idle (or hung) for the whole of it.
     */
    private Consumer<ProcessRunner.Result> running(String[] args, Consumer<ProcessRunner.Result> onResult) {
        AutoCloseable task = host.startBackgroundTask(tr("status.gitRunning", "git " + subcommand(args)));
        return result -> {
            try {
                task.close();
            } catch (Exception ignored) {
                // the indicator handle has nothing to fail with; never let it swallow the result
            }
            onResult.accept(result);
        };
    }

    /** The git subcommand in {@code args}: the first argument that is not a global option. */
    static String subcommand(String... args) {
        for (String arg : args) {
            if (!arg.startsWith("-")) {
                return arg;
            }
        }
        return "";
    }

    /**
     * The danger-styled confirmation every destructive Git action shows before it runs: the message names
     * what will be lost, the confirming button is labelled with the action and styled {@code danger}, and
     * Cancel is the safe default.
     */
    boolean confirmDestructive(String title, String message, String actionLabel) {
        ButtonType action = new ButtonType(actionLabel, ButtonBar.ButtonData.OK_DONE);
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, message, action, ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(title);
        confirm.setHeaderText(null);
        confirm.getDialogPane().lookupButton(action).getStyleClass().add("danger");
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == action;
    }

    /**
     * {@code git add} over one or more repo-relative paths (the Commit window's Stage action, which is
     * multi-select). One invocation — and therefore one refresh — for the whole set.
     */
    void gitStagePaths(List<String> paths) {
        if (!paths.isEmpty() && !reportIfNoRepo()) {
            stageIn(repoRoot, paths);
        }
    }

    private void stageIn(Path root, List<String> paths) {
        stageIn(root, root.equals(repoRoot) ? lastStatus : null, paths);
    }

    private void stageIn(Path root, GitStatus status, List<String> paths) {
        if (!saveUnsaved(root, paths) || !confirmStagingMarkers(root, status, paths)) {
            return;
        }
        gitOpIn(
                root,
                paths.size() == 1 ? tr("status.git.staged", paths.get(0)) : tr("status.git.stagedMany", paths.size()),
                argv(paths, "add", "--"));
    }

    /** {@code git add -A}: the Commit window's Stage All. */
    void gitStageAll() {
        Path root = repoRoot; // the confirmation below runs a nested event loop: the root is captured first
        if (!reportIfNoRepo() && saveUnsaved(root, List.of()) && confirmStagingMarkers(root, lastStatus, List.of())) {
            gitOpIn(root, tr("status.git.stagedAll"), "add", "-A");
        }
    }

    /** The largest conflicted file read to look for leftover markers before it is staged. */
    static final long MARKER_SCAN_MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Staging an unmerged path tells git "this conflict is resolved". Doing that to a file that still holds
     * {@code <<<<<<<} markers commits the markers, so it is confirmed first, naming the file. Called after
     * the unsaved buffers were written, so the disk copy is what git is about to stage.
     *
     * @param pathspecs the paths being staged; empty means the whole repository (Stage All)
     * @return false when the user declined
     */
    private boolean confirmStagingMarkers(Path root, GitStatus status, List<String> pathspecs) {
        List<String> marked = unresolvedAmong(root, status, pathspecs);
        if (marked.isEmpty()) {
            return true;
        }
        String message = marked.size() == 1
                ? tr("dialog.stageConflict.message", marked.get(0))
                : tr("dialog.stageConflict.messageMany", marked.size(), marked.get(0));
        return confirmDestructive(tr("dialog.stageConflict.title"), message, tr("dialog.stageConflict.action"));
    }

    /** The unmerged paths {@code pathspecs} select whose file on disk still contains conflict markers. */
    static List<String> unresolvedAmong(Path root, GitStatus status, List<String> pathspecs) {
        List<String> marked = new ArrayList<>();
        for (GitStatus.FileEntry entry : GitConflicts.unmerged(status)) {
            if (!pathspecs.isEmpty() && pathspecs.stream().noneMatch(spec -> selects(spec, entry.path()))) {
                continue;
            }
            try {
                Path file = root.resolve(entry.path());
                if (Files.isRegularFile(file)
                        && Files.size(file) <= MARKER_SCAN_MAX_BYTES
                        && com.editora.diff.ConflictParser.hasConflictMarkers(
                                new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1))) {
                    marked.add(entry.path());
                }
            } catch (java.io.IOException | RuntimeException unreadable) {
                // Deleted on one side, or not readable: nothing to warn about, git decides.
            }
        }
        return marked;
    }

    /** {@code git reset -q HEAD} over one or more repo-relative paths; mirrors {@link #gitStagePaths}. */
    void gitUnstagePaths(List<String> paths) {
        if (!paths.isEmpty() && !reportIfNoRepo()) {
            unstageIn(repoRoot, lastStatus, paths);
        }
    }

    private void unstageIn(Path root, GitStatus status, List<String> paths) {
        // "Unstaging" an unmerged path would drop its merge stages 1/2/3: Git would no longer know the file
        // is conflicted and would let the merge be committed with one side's change silently missing.
        String conflicted = firstUnmergedUnder(status, paths);
        if (conflicted != null) {
            host.setStatus(tr("status.git.unstageConflict", conflicted));
            return;
        }
        gitOpIn(
                root,
                paths.size() == 1
                        ? tr("status.git.unstaged", paths.get(0))
                        : tr("status.git.unstagedMany", paths.size()),
                argv(withRenameSources(status, paths), "reset", "-q", "HEAD", "--"));
    }

    /**
     * {@code pathspecs} plus the original path of every staged rename or copy they select. A staged rename is
     * two index changes — the new path added, the old one deleted — and resetting only the new path left the
     * deletion staged: the next commit then recorded the file as deleted and nothing else.
     */
    static List<String> withRenameSources(GitStatus status, List<String> pathspecs) {
        if (status == null) {
            return pathspecs;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(pathspecs);
        for (GitStatus.FileEntry entry : status.files()) {
            if (entry.origPath() != null
                    && entry.staged()
                    && pathspecs.stream().anyMatch(spec -> selects(spec, entry.path()))) {
                out.add(entry.origPath());
            }
        }
        return List.copyOf(out);
    }

    /** The first unmerged path one of {@code pathspecs} (a file or a folder) selects, or {@code null}. */
    static String firstUnmergedUnder(GitStatus status, List<String> pathspecs) {
        if (status == null) {
            return null;
        }
        for (GitStatus.FileEntry entry : status.files()) {
            if (entry.unmerged() && pathspecs.stream().anyMatch(spec -> selects(spec, entry.path()))) {
                return entry.path();
            }
        }
        return null;
    }

    /** {@code prefix} followed by {@code paths} — the argv for a git command over a pathspec list. */
    private static String[] argv(List<String> paths, String... prefix) {
        String[] out = new String[1 + prefix.length + paths.size()];
        out[0] = "--literal-pathspecs";
        System.arraycopy(prefix, 0, out, 1, prefix.length);
        for (int i = 0; i < paths.size(); i++) {
            out[1 + prefix.length + i] = paths.get(i);
        }
        return out;
    }

    /**
     * Confirms <em>once</em> then reverts the given repo-relative paths — destructive. {@code tracked}
     * paths are checked out from the index/HEAD, {@code untracked} ones are deleted; a mixed set says so
     * in the prompt and then runs both commands (each over its whole list, so at most two invocations).
     */
    void discardChanges(List<String> tracked, List<String> untracked) {
        discard(repoRoot, lastStatus, new Discard(tracked, List.of(), untracked));
    }

    /**
     * The paths of one discard, by the command each needs: {@code worktree} paths are restored from the
     * index ({@code checkout --}), {@code head} paths have staged changes and go back to the last commit
     * ({@code restore --staged --worktree --source=HEAD}), {@code untracked} ones are deleted ({@code clean}).
     */
    private record Discard(List<String> worktree, List<String> head, List<String> untracked) {
        boolean isEmpty() {
            return worktree.isEmpty() && head.isEmpty() && untracked.isEmpty();
        }

        List<String> affected() {
            List<String> all = new ArrayList<>(worktree);
            all.addAll(head);
            all.addAll(untracked);
            return all;
        }
    }

    private void discard(Path root, GitStatus status, Discard discard) {
        // The paths are relative to the repository shown when the user chose them. The modal dialog below
        // runs a nested event loop in which a tab switch or a refresh can move repoRoot to another repository
        // (one worktree per task makes that ordinary), so the root is captured first and passed through.
        if (root == null || discard.isEmpty()) {
            return;
        }
        // git refuses to check out an unmerged path, and forcing it would silently take one side of the merge.
        String conflicted = firstUnmergedUnder(status, discard.worktree());
        if (conflicted != null) {
            host.setStatus(tr("status.git.discardConflict", conflicted));
            return;
        }
        if (!confirmDestructive(tr("dialog.discard.title"), discardPrompt(discard), tr("dialog.discard"))) {
            return;
        }
        List<String> affected = discard.affected();
        invalidatePendingWrites(root, affected);
        List<String[]> commands = new ArrayList<>(3);
        if (!discard.worktree().isEmpty()) {
            commands.add(argv(discard.worktree(), "checkout", "--"));
        }
        if (!discard.head().isEmpty()) {
            commands.add(argv(discard.head(), "restore", "--staged", "--worktree", "--source=HEAD", "--"));
        }
        if (!discard.untracked().isEmpty()) {
            commands.add(argv(discard.untracked(), "clean", "-f", "--"));
        }
        // Mixed sets run in order; one refresh/reload after every requested path was attempted.
        service.runWorktreeMutation(
                root, commands, running(commands.get(0), result -> finishDiscard(root, result, discard, affected)));
    }

    private void finishDiscard(Path root, ProcessRunner.Result result, Discard discard, List<String> affected) {
        invalidatePendingWrites(root, affected);
        if (result != null && result.ok()) {
            host.setStatus(discardSuccessMessage(discard));
        } else {
            gitError(tr("status.git.opFailed"), result == null ? tr("status.git.opFailed") : result.message());
        }
        afterMutation();
        ops.reloadAllFromDiskSilently();
    }

    private static String discardSuccessMessage(Discard discard) {
        List<String> untracked = discard.untracked();
        if (!untracked.isEmpty()) {
            return untracked.size() == 1
                    ? tr("status.git.deleted", untracked.get(0))
                    : tr("status.git.deletedMany", untracked.size());
        }
        List<String> tracked = discard.head().isEmpty() ? discard.worktree() : discard.head();
        return tracked.size() == 1 || !discard.head().isEmpty()
                ? tr("status.git.discarded", tracked.get(0))
                : tr("status.git.discardedMany", tracked.size());
    }

    /** The confirmation body: names the single file, else counts, and spells out a mixed set exactly. */
    private static String discardPrompt(Discard discard) {
        if (!discard.head().isEmpty()) {
            return tr("dialog.discard.toHead", discard.head().get(0)); // staged changes go too: say so
        }
        List<String> tracked = discard.worktree();
        List<String> untracked = discard.untracked();
        if (untracked.isEmpty()) {
            return tracked.size() == 1
                    ? tr("dialog.discard.tracked", tracked.get(0))
                    : tr("dialog.discard.trackedMany", tracked.size());
        }
        if (tracked.isEmpty()) {
            return untracked.size() == 1
                    ? tr("dialog.discard.untracked", untracked.get(0))
                    : tr("dialog.discard.untrackedMany", untracked.size());
        }
        return tr("dialog.discard.mixed", tracked.size(), untracked.size());
    }

    void gitCommit(String message) {
        gitCommit(message, committed -> {});
    }

    /**
     * Commits the index. {@code onDone} is told — on the FX thread, exactly once — whether a commit was made,
     * so the Commit window can re-enable itself and clear the message it submitted (and only that).
     */
    void gitCommit(String message, Consumer<Boolean> onDone) {
        Path root = repoRoot;
        if (root == null || !saveUnsaved(root, List.of())) {
            onDone.accept(false);
            return;
        }
        String[] args = {"commit", "-m", message};
        service.runWorktreeMutation(
                root,
                running(args, r -> {
                    onDone.accept(r.ok());
                    if (r.ok()) {
                        host.setStatus(tr("status.committed"));
                    } else {
                        gitError(tr("status.git.commitFailed"), r.message());
                    }
                    afterMutation();
                }),
                args);
    }

    void checkoutBranch(String name) {
        if (repoRoot == null || name == null || name.isBlank()) {
            return;
        }
        if (rejectUnsafeRevision(name)) {
            return;
        }
        Path root = repoRoot;
        String[] args = {"checkout", name};
        aroundWorkingTreeMutation(done -> service.runWorktreeMutation(root, running(args, done), args), r -> {
            if (r.ok()) {
                host.setStatus(tr("status.switchedBranch", name));
            } else {
                gitError(tr("status.git.switchFailed", name), r.message());
            }
        });
    }

    /**
     * Refuses a branch/tag name that Git would parse as an option. Names come from repository data, and a
     * ref called {@code -f} turns {@code git checkout <name>} into a forced checkout that discards local
     * changes. Returns {@code true} (after reporting) when the name was refused.
     */
    private boolean rejectUnsafeRevision(String name) {
        if (GitSafety.isSafeRevision(name)) {
            return false;
        }
        host.setError(tr("status.git.unsafeRef", name));
        return true;
    }

    /**
     * The completion boundary for <em>any</em> operation that may rewrite working-tree files — a git command
     * or another tool that drives git ({@code gh pr checkout}). Pending editor saves for the repository are
     * superseded before the operation starts and again when it ends (a save queued in between must not
     * overwrite what the operation wrote), then the Git UI is refreshed and clean buffers reload from disk.
     * {@code operation} receives the callback it must invoke, on the FX thread, with the result;
     * {@code report} then tells the user the outcome.
     */
    void aroundWorkingTreeMutation(
            Consumer<Consumer<ProcessRunner.Result>> operation, Consumer<ProcessRunner.Result> report) {
        aroundWorkingTreeMutation(operation, report, null);
    }

    private void aroundWorkingTreeMutation(
            Consumer<Consumer<ProcessRunner.Result>> operation,
            Consumer<ProcessRunner.Result> report,
            Runnable afterReload) {
        aroundWorkingTreeMutation(repoRoot, operation, report, afterReload);
    }

    void aroundWorkingTreeMutation(
            Path root,
            Consumer<Consumer<ProcessRunner.Result>> operation,
            Consumer<ProcessRunner.Result> report,
            Runnable afterReload) {
        invalidatePendingWrites(root, List.of());
        operation.accept(result -> {
            invalidatePendingWrites(root, List.of());
            report.accept(result);
            afterMutation();
            ops.reloadAllFromDiskSilently();
            if (afterReload != null) {
                afterReload.run();
            }
        });
    }

    /** Shared completion boundary for every Git operation that may rewrite working-tree files. */
    void mutateWorkingTree(String successMessage, Runnable afterCompletion, String... args) {
        if (reportIfNoRepo()) {
            return;
        }
        mutateWorkingTree(repoRoot, successMessage, afterCompletion, args);
    }

    /**
     * {@link #mutateWorkingTree(String, Runnable, String...)} in the repository the caller chose its
     * arguments from. A commit hash listed for one repository must not run in whichever repository is active
     * by the time a confirmation has been answered, so the Git Log passes the root it listed.
     */
    void mutateWorkingTree(Path root, String successMessage, Runnable afterCompletion, String... args) {
        if (root == null) {
            return;
        }
        aroundWorkingTreeMutation(
                root,
                done -> service.runWorktreeMutation(root, running(args, done), args),
                result -> {
                    if (result.ok()) {
                        host.setStatus(successMessage);
                    } else if (!reportConflictStop(result, "status.git.stoppedOnConflicts")) {
                        gitError(tr("status.git.opFailed"), result.message());
                    }
                },
                afterCompletion);
    }

    /**
     * A command that stopped <em>on conflicts</em> (a revert, cherry-pick, pull, rebase step or stash pop)
     * did not fail: the repository now waits for them to be resolved, and the Commit window shows that — its
     * Conflicts group and the operation banner — once the refresh that follows every mutation lands. So
     * instead of an error dialog quoting git's transcript, the window is opened and the status bar says what
     * to do. Returns false, having done nothing, for any other failure.
     */
    private boolean reportConflictStop(ProcessRunner.Result result, String messageKey) {
        if (!GitConflicts.stoppedOnConflict(result)) {
            return false;
        }
        host.setStatus(tr(messageKey));
        ops.openCommitWindow();
        return true;
    }

    /** As {@link #reportConflictStop}, for a caller that classified the stop itself and has its own message. */
    void conflictsNeedAttention(String status) {
        host.setStatus(status);
        ops.openCommitWindow();
    }

    // --- the operation in progress: continue / skip / abort ----------------------------------------

    /** The display name of an operation ("Merge", "Rebase", …); {@code ""} for none. */
    static String operationName(GitOperation.Kind kind) {
        return switch (kind) {
            case NONE -> "";
            case MERGE -> tr("git.operation.merge");
            case REBASE -> tr("git.operation.rebase");
            case CHERRY_PICK -> tr("git.operation.cherryPick");
            case REVERT -> tr("git.operation.revert");
            case BISECT -> tr("git.operation.bisect");
        };
    }

    /**
     * The operation the three commands act on, or null after saying why there is none: no repository,
     * nothing in progress, or a bisect — which is shown but has no continue, skip or abort here.
     */
    private GitOperation drivenOperation() {
        if (reportIfNoRepo()) {
            return null;
        }
        if (!operation.canContinue()) {
            host.setStatus(tr("status.git.noOperation"));
            return null;
        }
        return operation;
    }

    /**
     * Continues the operation in progress ({@code git.continueOperation}, the banner's Continue). Refused
     * while files are still unmerged — git would refuse too, with a transcript instead of a sentence. A merge
     * is concluded by committing: with the message in the Commit window's box when there is one (it is
     * prefilled with git's prepared message), else with git's.
     */
    void continueOperation() {
        GitOperation op = drivenOperation();
        if (op == null) {
            return;
        }
        Path root = repoRoot;
        int conflicts = GitConflicts.unmerged(lastStatus).size();
        if (conflicts > 0) {
            host.setStatus(tr("status.git.cannotContinueConflicts", operationName(op.kind()), conflicts));
            ops.openCommitWindow();
            return;
        }
        if (!saveUnsaved(root, List.of()) || (op.kind() == GitOperation.Kind.MERGE && ops.commitFromPanel())) {
            return;
        }
        runOperationStep(root, op, op.continueArgs(), tr("status.git.operationContinued", operationName(op.kind())));
    }

    /**
     * Skips the commit a rebase, cherry-pick or revert stopped at ({@code git <op> --skip}). That commit's
     * changes — and whatever was resolved or edited since the stop — are dropped, so it is confirmed like
     * every other action that throws work away. A merge has nothing to skip.
     */
    void skipOperation() {
        GitOperation op = drivenOperation();
        if (op == null) {
            return;
        }
        if (!op.canSkip()) {
            host.setStatus(tr("status.git.cannotSkip", operationName(op.kind())));
            return;
        }
        Path root = repoRoot;
        String name = operationName(op.kind());
        if (confirmDestructive(
                tr("dialog.skipOperation.title", name),
                tr("dialog.skipOperation.message", name),
                tr("dialog.skipOperation.action"))) {
            runOperationStep(root, op, op.skipArgs(), tr("status.git.operationSkipped", name));
        }
    }

    /**
     * Aborts the operation in progress ({@code git <op> --abort}): the branch, index and working tree go back
     * to where they were before it started. Confirmed first — danger-styled, Cancel the default — with a
     * message that says what is lost for this kind of operation.
     */
    void abortOperation() {
        GitOperation op = drivenOperation();
        if (op == null) {
            return;
        }
        Path root = repoRoot;
        String name = operationName(op.kind());
        String lost =
                switch (op.kind()) {
                    case MERGE -> tr("dialog.abortOperation.merge");
                    case REBASE -> tr("dialog.abortOperation.rebase");
                    case CHERRY_PICK -> tr("dialog.abortOperation.cherryPick");
                    default -> tr("dialog.abortOperation.revert");
                };
        if (confirmDestructive(
                tr("dialog.abortOperation.title", name), lost, tr("dialog.abortOperation.action", name))) {
            runOperationStep(root, op, op.abortArgs(), tr("status.git.operationAborted", name));
        }
    }

    /** One continue / skip / abort in {@code root}: pending saves superseded, buffers reloaded afterwards. */
    private void runOperationStep(Path root, GitOperation op, String[] args, String successMessage) {
        aroundWorkingTreeMutation(
                root,
                done -> service.runWorktreeMutation(root, running(args, done), args),
                result -> {
                    if (result.ok()) {
                        host.setStatus(successMessage);
                    } else if (!reportConflictStop(result, "status.git.stoppedOnConflicts")) {
                        gitError(tr("status.git.operationFailed", operationName(op.kind())), result.message());
                    }
                },
                null);
    }

    // --- conflicts -----------------------------------------------------------------------------------

    /**
     * Resolves unmerged paths by taking one whole side ({@code checkout --ours/--theirs} then {@code add}, or
     * {@code rm} when that side deleted the file — {@link GitConflicts#acceptSide}). The other side's changes
     * to those files are dropped, so it is confirmed.
     */
    void acceptConflictSide(List<String> paths, boolean ours) {
        if (paths.isEmpty() || reportIfNoRepo()) {
            return;
        }
        Path root = repoRoot;
        List<GitStatus.FileEntry> entries = GitConflicts.unmerged(lastStatus).stream()
                .filter(entry -> paths.contains(entry.path()))
                .toList();
        List<String[]> commands = GitConflicts.acceptSide(entries, ours);
        if (commands.isEmpty()) {
            return;
        }
        String side = tr(ours ? "gitpanel.menu.acceptOurs" : "gitpanel.menu.acceptTheirs");
        String message = entries.size() == 1
                ? tr("dialog.acceptSide.message", side, entries.get(0).path())
                : tr("dialog.acceptSide.messageMany", side, entries.size());
        if (!confirmDestructive(tr("dialog.acceptSide.title"), message, side)) {
            return;
        }
        List<String> affected = entries.stream().map(GitStatus.FileEntry::path).toList();
        invalidatePendingWrites(root, affected);
        service.runWorktreeMutation(root, commands, running(commands.get(0), result -> {
            invalidatePendingWrites(root, affected);
            if (result.ok()) {
                host.setStatus(
                        affected.size() == 1
                                ? tr("status.git.conflictResolved", affected.get(0))
                                : tr("status.git.conflictsResolved", affected.size()));
            } else {
                gitError(tr("status.git.opFailed"), result.message());
            }
            afterMutation();
            ops.reloadAllFromDiskSilently();
        }));
    }

    /**
     * The three-way resolver applied a resolution to {@code buffer}. When its file is an unmerged path of
     * the active repository and no conflict is left in the text, the resolution is finished the way IntelliJ
     * and VS Code finish it: the file is saved and staged, which is what marks the conflict resolved for
     * git. A partial resolution (markers remain) is only written into the buffer, as before.
     */
    void resolutionApplied(EditorBuffer buffer) {
        Path root = repoRoot;
        Path file = buffer == null ? null : buffer.getPath();
        if (root == null || file == null || !isEnabled()) {
            return;
        }
        String relative = GitService.repoRelative(root, file);
        if (relative == null
                || GitConflicts.unmerged(lastStatus).stream()
                        .noneMatch(e -> e.path().equals(relative))
                || com.editora.diff.ConflictParser.hasConflictMarkers(buffer.getContent())) {
            return;
        }
        if (!ops.saveBeforeGit(buffer)) {
            host.setError(tr("status.git.unsavedFailed", buffer.getTitle()));
            return;
        }
        gitOpIn(root, tr("status.git.conflictResolved", relative), argv(List.of(relative), "add", "--"));
    }

    /** Checks out a remote branch (e.g. {@code origin/foo}), creating a local tracking branch. */
    void checkoutRemoteBranch(String remote) {
        if (repoRoot == null || remote == null || remote.isBlank()) {
            return;
        }
        if (rejectUnsafeRevision(remote)) {
            return;
        }
        Path root = repoRoot;
        String[] args = {"checkout", "--track", remote};
        aroundWorkingTreeMutation(done -> service.runWorktreeMutation(root, running(args, done), args), r -> {
            if (r.ok()) {
                host.setStatus(tr("status.checkedOut", remote));
            } else {
                gitError(tr("status.git.checkoutFailed", remote), r.message());
            }
        });
    }

    void newBranch() {
        if (reportIfNoRepo()) {
            return;
        }
        Path root = repoRoot; // the repository the prompt was opened for, not whichever is active on accept
        host.promptText(tr("dialog.newBranch.title"), tr("dialog.newBranch.content"), "", input -> {
            String name = input.strip();
            if (name.isEmpty() || rejectUnsafeRevision(name)) {
                return;
            }
            String[] args = {"checkout", "-b", name};
            service.runWorktreeMutation(
                    root,
                    running(args, r -> {
                        if (r.ok()) {
                            host.setStatus(tr("status.createdBranch", name));
                        } else {
                            gitError(tr("status.git.createBranchFailed", name), r.message());
                        }
                        afterMutation();
                    }),
                    args);
        });
    }

    void gitSync(String label, String... args) {
        if (reportIfNoRepo()) {
            return;
        }
        syncIn(repoRoot, label, null, args);
    }

    /**
     * @param onFailure given a failed result first; true means it dealt with it (a diverged pull offering the
     *     way forward) and nothing more is reported
     */
    private void syncIn(
            Path root, String label, java.util.function.Predicate<ProcessRunner.Result> onFailure, String... args) {
        host.setStatus(tr("status.gitRunning", label));
        boolean changesWorkingTree = args.length > 0 && "pull".equals(args[0]);
        if (changesWorkingTree) {
            invalidatePendingWrites(root, List.of());
        }
        Consumer<ProcessRunner.Result> finished = running(args, r -> {
            if (changesWorkingTree) {
                invalidatePendingWrites(root, List.of());
                // Also after a failure: a pull that stopped on conflicts has rewritten files (with markers).
                ops.reloadAllFromDiskSilently();
            }
            afterMutation();
            if (r.ok()) {
                host.setStatus(tr("status.gitDone", label));
            } else if ((onFailure == null || !onFailure.test(r))
                    && !reportConflictStop(r, "status.git.stoppedOnConflicts")) {
                gitError(tr("status.git.syncFailed", label), r.message());
            }
        });
        if (changesWorkingTree) {
            service.runNetworkWorktreeMutation(root, finished, args);
        } else {
            service.runNetwork(root, finished, args);
        }
    }

    // --- pull ------------------------------------------------------------------------------------------

    /** The name of a pull mode as Settings and the {@code git.setPullMode} picker show it. */
    static String pullModeLabel(GitPullMode mode) {
        return switch (mode) {
            case FF_ONLY -> tr("settings.git.pullMode.ffOnly");
            case REBASE -> tr("settings.git.pullMode.rebase");
            case MERGE -> tr("settings.git.pullMode.merge");
        };
    }

    /** Stores the "Pull mode" setting ({@code git.setPullMode}) and keeps an open Settings window in step. */
    void setPullMode(String id) {
        GitPullMode mode = GitPullMode.of(id);
        host.settings().setGitPullMode(mode.id());
        host.requestSave();
        ops.syncBlameCheck(); // re-reads the Git page's controls from the settings
        host.setStatus(tr("status.settingChanged", tr("command.git.setPullMode"), pullModeLabel(mode)));
    }

    /** The "Pull mode" setting: fast-forward only unless the user chose rebase or merge. */
    GitPullMode pullMode() {
        return GitPullMode.of(host.settings().getGitPullMode());
    }

    /**
     * Pulls in the configured mode ({@code git.pull}). A fast-forward-only pull of a branch that has
     * diverged from its upstream cannot succeed, and git's "Not possible to fast-forward" names no way out:
     * the user is offered the two there are — rebase or merge — instead of the error.
     */
    void gitPull() {
        if (!reportIfNoRepo()) {
            pullIn(repoRoot, pullMode(), true);
        }
    }

    /** Pulls in {@code mode} whatever the setting says ({@code git.pullRebase}, {@code git.pullMerge}). */
    void gitPull(GitPullMode mode) {
        if (!reportIfNoRepo()) {
            pullIn(repoRoot, mode, false);
        }
    }

    private void pullIn(Path root, GitPullMode mode, boolean offerOnDivergence) {
        java.util.function.Predicate<ProcessRunner.Result> diverged = result -> {
            if (!offerOnDivergence || mode != GitPullMode.FF_ONLY || !GitPullMode.diverged(result)) {
                return false;
            }
            GitPullMode chosen = divergedPullChooser.get();
            if (chosen == null || chosen == GitPullMode.FF_ONLY) {
                host.setStatus(tr("status.git.pullDiverged"));
            } else {
                pullIn(root, chosen, false); // in the repository the pull was started in, whatever is active now
            }
            return true;
        };
        syncIn(root, tr("gitlabel.pull"), diverged, mode.args());
    }

    /** Asks how to pull a diverged branch; null = cancel. Replaceable so a test need not show the dialog. */
    java.util.function.Supplier<GitPullMode> divergedPullChooser = this::askDivergedPull;

    private GitPullMode askDivergedPull() {
        ButtonType rebase = new ButtonType(tr("dialog.pullDiverged.rebase"), ButtonBar.ButtonData.OTHER);
        ButtonType merge = new ButtonType(tr("dialog.pullDiverged.merge"), ButtonBar.ButtonData.OTHER);
        Alert ask = new Alert(
                Alert.AlertType.CONFIRMATION, tr("dialog.pullDiverged.message"), rebase, merge, ButtonType.CANCEL);
        ask.initOwner(host.window());
        ask.setTitle(tr("dialog.pullDiverged.title"));
        ask.setHeaderText(null);
        ButtonType chosen = ask.showAndWait().orElse(ButtonType.CANCEL);
        return chosen == rebase ? GitPullMode.REBASE : chosen == merge ? GitPullMode.MERGE : null;
    }

    /**
     * Pushes the current branch. The argv decision (first push of an upstream-less branch →
     * {@code --set-upstream origin <branch>}, else a plain {@code push}) is the pure, unit-tested
     * {@link GitService#pushArgs}.
     */
    void gitPush() {
        if (reportIfNoRepo()) {
            return;
        }
        if (pushHandler != null) {
            pushHandler.run(); // GitBranchCoordinator.push: the same push, with an answer to a rejection
            return;
        }
        gitSync(tr("gitlabel.push"), GitService.pushArgs(branchName, upstream));
    }

    /**
     * Whether the current branch has no upstream, i.e. it has never been pushed. Creating a PR from such a
     * branch needs a push first — {@code gh pr create} would otherwise want to ask where to push, and the
     * GitHub integration runs {@code gh} with prompting disabled and stdin closed, so it just fails.
     */
    boolean currentBranchUnpushed() {
        return repoRoot != null && !branchName.isBlank() && upstream.isBlank();
    }

    /**
     * Pushes the current branch and hands the outcome back, for a caller that must chain work onto a
     * successful push (creating a PR). Same argv decision as {@link #gitPush} (the pure
     * {@link GitService#pushArgs}), but the result is reported to {@code onDone} — on the FX thread — rather
     * than only echoed, so the caller decides what a failure means. Does nothing (beyond the standard
     * "not a repo" echo) when there's no repo.
     */
    void pushCurrentBranch(Consumer<ProcessRunner.Result> onDone) {
        if (reportIfNoRepo()) {
            return;
        }
        String[] args = GitService.pushArgs(branchName, upstream);
        service.runNetwork(
                repoRoot,
                running(args, r -> {
                    if (r.ok()) {
                        ops.reloadAllFromDiskSilently();
                    }
                    afterMutation();
                    onDone.accept(r);
                }),
                args);
    }

    /** Opens the Git tool window and focuses the commit message box. */
    void gitCommitFocus() {
        ops.openCommitWindow();
        ops.focusCommitMessage();
    }

    /** Stages the active file (palette {@code git.stageFile}). */
    void gitStageActiveFile() {
        Path file = activeGitFile();
        if (file != null) {
            gitStagePath(file);
        }
    }

    /** Unstages the active file (palette {@code git.unstageFile}; mirrors {@link #gitStageActiveFile}). */
    void gitUnstageActiveFile() {
        Path file = activeGitFile();
        if (file != null) {
            gitUnstagePath(file);
        }
    }

    /** Discards the active file's changes (palette {@code git.discardFile}; confirms first). */
    void gitDiscardActiveFile() {
        Path file = activeGitFile();
        if (file != null) {
            gitRevertPath(file);
        }
    }

    /** The active buffer's file when there is a repository to act in; otherwise reports it and returns null. */
    private Path activeGitFile() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || repoRoot == null) {
            host.setStatus(tr("status.noGitFile"));
            return null;
        }
        return file;
    }

    // --- actions on a given path (the Project tree, the tab menu, the active file) ----------------

    /**
     * Which repository {@code file} belongs to, relative to the active one; {@link GitPathScope#NONE} when
     * Git is off. The Project tree's menu uses it to decide what it can enable.
     */
    GitPathScope scopeOf(Path file) {
        return isEnabled() ? GitPathScope.of(file, repoRoot) : GitPathScope.NONE;
    }

    /**
     * Runs {@code action} with the repository {@code file} belongs to and that repository's status.
     *
     * <p>The Git UI follows the active tab, but a path picked in the Project tree can sit in a nested
     * repository, a submodule or another worktree. Its path was computed against the active root, which
     * produced either no pathspec at all (the action silently did nothing) or one the outer repository
     * rejects. The active repository is answered at once from the last status; another one is asked for.
     */
    private void inRepositoryOf(Path file, java.util.function.BiConsumer<Path, GitStatus> action) {
        switch (GitPathScope.of(file, repoRoot)) {
            case ACTIVE -> action.accept(repoRoot, lastStatus);
            case OTHER ->
                service.status(file, state -> {
                    if (state.isRepo()) {
                        action.accept(state.root(), state.status());
                    } else {
                        host.setStatus(tr(service.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
                    }
                });
            case NONE -> host.setStatus(tr(service.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
        }
    }

    /**
     * Runs an action written against {@link #repoRoot()} (compare with HEAD / a branch / a tag / a revision)
     * for a path that may belong to another repository: {@link #repoRoot()} answers that repository for the
     * duration of {@code action}, which must read it before returning — as those actions do, because the root
     * can change under any callback they register.
     */
    void withRepositoryOf(Path file, Runnable action) {
        ifEnabled(() -> inRepositoryOf(file, (root, status) -> {
            Path activeRoot = repoRoot;
            GitStatus activeStatus = lastStatus;
            repoRoot = root;
            lastStatus = status;
            try {
                action.run();
            } finally {
                repoRoot = activeRoot;
                lastStatus = activeStatus;
            }
        }));
    }

    /**
     * Runs an action that needs {@code file}'s repository to be the <em>active</em> one (its history in the
     * Git Log, which lists the active repository). A file of another repository is opened first — the Git UI
     * follows the active tab — and the action runs once that repository's state has been applied.
     */
    void activatingRepositoryOf(Path file, Runnable action) {
        ifEnabled(() -> {
            if (GitPathScope.of(file, repoRoot) != GitPathScope.OTHER || Files.isDirectory(file)) {
                action.run();
                return;
            }
            ops.openPath(file);
            // Queued behind the refresh the tab switch started, so normally that has been applied by now.
            service.status(file, state -> {
                if (state.isRepo() && !state.root().equals(repoRoot)) {
                    applyState(state);
                }
                action.run();
            });
        });
    }

    /** The literal pathspec for {@code file} in {@code root} ({@code .} for the root itself), or null. */
    private static String pathspec(Path root, Path file) {
        // repoRelative symlink-resolves both sides (the root comes from git as a real path, the file is
        // as-opened) and forward-slash-normalizes the result (git treats "\" as an escape on Windows).
        String relative = GitService.repoRelative(root, file);
        return relative != null && relative.isEmpty() ? "." : relative;
    }

    /** {@code git add -- <path>} for a file or folder (the Project-tree "Stage" action). */
    void gitStagePath(Path file) {
        inRepositoryOf(file, (root, status) -> {
            String spec = pathspec(root, file);
            if (spec != null) {
                stageIn(root, status, List.of(spec));
            }
        });
    }

    /** {@code git reset -q HEAD -- <path>} for a file or folder (the Project-tree "Unstage" action). */
    void gitUnstagePath(Path file) {
        inRepositoryOf(file, (root, status) -> {
            String spec = pathspec(root, file);
            if (spec != null) {
                unstageIn(root, status, List.of(spec));
            }
        });
    }

    /**
     * Reverts local changes to {@code file} — the Project-tree "Revert" action and the active file's Discard.
     * What runs depends on the path's status ({@link GitDiscardPlan}): an untracked file is deleted, unstaged
     * changes are restored from the index, staged ones from the last commit, an unmerged path is refused and
     * a path without changes is reported as such. Confirms first. A folder reverts the tracked changes under it.
     */
    void gitRevertPath(Path file) {
        inRepositoryOf(file, (root, status) -> {
            GitDiscardPlan plan = GitDiscardPlan.of(status, pathspec(root, file));
            switch (plan.kind()) {
                case NOTHING -> host.setStatus(tr("status.git.nothingToDiscard", file.getFileName()));
                case CONFLICT -> host.setStatus(tr("status.git.discardConflict", plan.subject()));
                case UNTRACKED -> discard(root, status, new Discard(List.of(), List.of(), plan.specs()));
                case WORKTREE -> discard(root, status, new Discard(plan.specs(), List.of(), List.of()));
                case HEAD -> discard(root, status, new Discard(List.of(), plan.specs(), List.of()));
            }
        });
    }

    /** The Git working-tree status of {@code file} (null = clean / not tracked-as-changed), for menu
     *  enable/disable — mirrors the map the Project tree colors from. */
    com.editora.git.GitFileStatus statusFor(Path file) {
        return file == null ? null : lastStatusByPath.get(file.toAbsolutePath().normalize());
    }

    /**
     * Adds {@code file} (a directory gets a trailing {@code /}) to the repo-root {@code .gitignore}, creating
     * it if absent — the Project-tree "Add to .gitignore" action. A no-op when the entry is already present.
     * Writes the small file on the FX thread then refreshes so the now-ignored file drops from the tree color.
     */
    void addToGitignore(Path file) {
        inRepositoryOf(file, (root, status) -> addToGitignore(root, file));
    }

    private void addToGitignore(Path root, Path file) {
        String rel = GitService.repoRelative(root, file);
        if (rel == null || rel.isEmpty()) {
            return;
        }
        String entry = com.editora.git.GitIgnore.entryFor(rel, java.nio.file.Files.isDirectory(file));
        Path ignore = root.resolve(".gitignore");
        try {
            String existing = java.nio.file.Files.exists(ignore) ? java.nio.file.Files.readString(ignore) : "";
            String updated = com.editora.git.GitIgnore.withEntry(existing, entry);
            if (updated == null) {
                host.setStatus(tr("status.git.alreadyIgnored", entry));
                return;
            }
            java.nio.file.Files.writeString(ignore, updated);
            host.setStatus(tr("status.git.addedToGitignore", entry));
            afterMutation();
        } catch (java.io.IOException e) {
            gitError(tr("status.git.gitignoreFailed"), String.valueOf(e.getMessage()));
        }
    }

    // --- stash -------------------------------------------------------------------------------------

    /** Stashes the working tree (optionally with a message). */
    void gitStash() {
        if (reportIfNoRepo()) {
            return;
        }
        Path root = repoRoot; // the repository the prompt was opened for, not whichever is active on accept
        host.promptText(tr("stash.prompt.title"), tr("stash.prompt.label"), "", msg -> {
            String m = msg.strip();
            String[] args = m.isEmpty() ? new String[] {"stash", "push"} : new String[] {"stash", "push", "-m", m};
            invalidatePendingWrites(root, List.of());
            service.runWorktreeMutation(
                    root,
                    running(args, r -> {
                        invalidatePendingWrites(root, List.of());
                        if (r.ok()) {
                            host.setStatus(tr("stash.pushed"));
                        } else {
                            gitError(tr("status.git.opFailed"), r.message());
                        }
                        afterMutation();
                        ops.reloadAllFromDiskSilently();
                    }),
                    args);
        });
    }

    /** Pops the most recent stash. */
    void gitStashPop() {
        if (reportIfNoRepo()) {
            return;
        }
        gitMutateStash(repoRoot, tr("stash.popped"), "stash", "pop");
    }

    /** Opens a picker over the stash list to apply a chosen entry. */
    void gitUnstash() {
        chooseStash(
                tr("stash.picker.applyTitle"),
                (root, entry) -> gitMutateStash(root, tr("stash.applied"), "stash", "apply", entry.ref()));
    }

    /**
     * Opens a picker over the stash list to drop a chosen entry. Dropping deletes the stashed changes for
     * good — there is no undo short of digging the commit out of the object store — so it is confirmed with
     * the same danger-styled dialog a file discard uses.
     */
    void gitStashDrop() {
        chooseStash(tr("stash.picker.dropTitle"), this::dropStash);
    }

    /** Confirms, then drops {@code entry} from the repository at {@code root} (the one it was listed from). */
    void dropStash(Path root, com.editora.git.StashParser.StashEntry entry) {
        String described = entry.subject().isBlank() ? entry.ref() : entry.ref() + " — " + entry.subject();
        if (confirmDestructive(
                tr("stash.picker.dropTitle"), tr("dialog.stashDrop.confirm", described), tr("dialog.stashDrop"))) {
            gitMutateStash(root, tr("stash.dropped"), "stash", "drop", entry.ref());
        }
    }

    /**
     * Lists the stashes of the repository that is active <em>now</em> and hands the pick back together with
     * that root: {@code stash@{0}} names a different stash in every repository, so the mutation must not
     * re-read {@code repoRoot} after the picker (or a confirmation) has been on screen.
     */
    private void chooseStash(
            String title, java.util.function.BiConsumer<Path, com.editora.git.StashParser.StashEntry> onPick) {
        if (reportIfNoRepo()) {
            return;
        }
        Path root = repoRoot;
        service.stashList(root, stashes -> {
            if (stashes.isEmpty()) {
                host.setStatus(tr("stash.empty"));
                return;
            }
            QuickOpen<com.editora.git.StashParser.StashEntry> picker = new QuickOpen<>(
                    title,
                    tr("stash.picker.prompt"),
                    () -> stashes,
                    e -> e.ref() + "  " + e.subject(),
                    e -> e.branch(),
                    e -> e.ref() + " " + e.subject() + " " + e.branch(),
                    entry -> onPick.accept(root, entry));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    private void gitMutateStash(Path root, String successMessage, String... args) {
        if (root == null) {
            return;
        }
        boolean changesWorkingTree = args.length < 2 || !"drop".equals(args[1]);
        if (changesWorkingTree) {
            invalidatePendingWrites(root, List.of());
        }
        service.runWorktreeMutation(
                root,
                running(args, r -> {
                    if (changesWorkingTree) {
                        invalidatePendingWrites(root, List.of());
                    }
                    if (r.ok()) {
                        host.setStatus(successMessage);
                    } else if (!reportConflictStop(r, "status.git.stashConflicts")) {
                        // (A pop or apply that conflicts leaves the stash in place and the files unmerged.)
                        gitError(tr("status.git.opFailed"), r.message());
                    }
                    afterMutation();
                    if (changesWorkingTree) {
                        ops.reloadAllFromDiskSilently();
                    }
                }),
                args);
    }

    /** Supersedes pending saves for open files selected by the working-tree mutation. Empty means the repo. */
    private void invalidatePendingWrites(List<String> pathspecs) {
        invalidatePendingWrites(repoRoot, pathspecs);
    }

    /** As above, for the repository {@code root} captured when the action began. */
    private void invalidatePendingWrites(Path root, List<String> pathspecs) {
        if (root == null) {
            return;
        }
        host.forEachBuffer(buffer -> {
            Path file = buffer.getPath();
            if (!Vfs.isLocal(file)) {
                return; // a remote tab is never in a local repository; resolving it would be a network round trip
            }
            String relative = GitService.repoRelative(root, file);
            if (relative != null && (pathspecs.isEmpty() || pathspecs.stream().anyMatch(p -> selects(p, relative)))) {
                ops.invalidatePendingWrite(file);
            }
        });
        ops.invalidatePendingWritesInOtherWindows(root, pathspecs);
    }

    static boolean selects(String pathspec, String relative) {
        if (".".equals(pathspec) || relative.equals(pathspec)) {
            return true;
        }
        String directory = pathspec.endsWith("/") ? pathspec : pathspec + "/";
        return relative.startsWith(directory);
    }

    /** Stops the running clone, fetch, pull or push (the Git console's Stop button and {@code git.cancel}). */
    void cancelNetworkCommand() {
        host.setStatus(tr(service.cancelRunningCommand() ? "status.git.cancelling" : "status.git.nothingToCancel"));
    }

    /**
     * Shows a Git command's (often multi-line) error output in a readable, scrollable dialog rather than
     * cramming it into the one-line status bar. The status bar gets a short summary.
     */
    void gitError(String summary, String detail) {
        if (ProcessRunner.CANCELLED.equals(detail)) {
            host.setStatus(tr("status.git.cancelled")); // the user stopped it: nothing to explain in a dialog
            return;
        }
        host.setStatus(summary);
        // A sign-in failure gets its next step appended: git's own line ("terminal prompts disabled") has none.
        String body = detail == null || detail.isBlank() ? summary : GitAuthFailure.withGuidance(detail.strip());
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.git.title"));
        alert.setHeaderText(summary);
        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(52);
        area.setPrefRowCount(Math.min(14, (int) body.lines().count() + 1));
        area.getStyleClass().add("git-error-text");
        alert.getDialogPane().setContent(area);
        alert.showAndWait();
    }

    // --- clone (URL + destination form → git clone → open a file so Git lights up) ---

    /**
     * Clones a remote repository via one form asking for both the <em>URL</em> and the destination directory
     * (a Browse button + auto-fill of {@code <home>/<repo-name>} as you type the URL, until you edit it
     * yourself). Clones into that folder, then opens a file from it (its README, if any) so Git lights up.
     * Clone and Projects are independent — no project is created.
     */
    /**
     * Creates a repository in a folder ({@code git init}) — the other half of {@link #cloneRepo()}, and the
     * only way to start version control on a folder that Editora opened but that has no repo yet.
     *
     * <p>Defaults to this window's project root, else the active file's folder. Refuses a folder that is
     * already inside a repository rather than nesting one, which is almost always a mistake and is
     * confusing to undo. On success the whole Git UI lights up through the normal
     * {@link #afterMutation()} path — status bar, Commit window, gutter change bars.
     */
    void initRepo() {
        Path fromProject = ops.projectRoot();
        EditorBuffer active = host.activeBuffer();
        final Path suggested = fromProject != null
                ? fromProject
                : (active != null && active.getPath() != null ? active.getPath().getParent() : null);
        host.promptText(
                tr("dialog.gitInit.title"),
                tr("dialog.gitInit.folder"),
                suggested == null ? "" : suggested.toString(),
                text -> {
                    if (text == null || text.isBlank()) {
                        return;
                    }
                    Path dir = com.editora.config.PathKeys.resolveUserInput(
                            text.strip(), suggested, System.getProperty("user.home"));
                    if (dir == null || !java.nio.file.Files.isDirectory(dir)) {
                        host.setError(tr("status.gitInit.notAFolder", text.strip()));
                        return;
                    }
                    host.setStatus(tr("status.gitInit.initializing", dir.toString()));
                    // The already-a-repo check lives in the service: answering it runs git rev-parse, which
                    // must not happen on the FX thread.
                    service.init(dir, (r, existing) -> {
                        if (existing != null) {
                            host.setError(tr("status.gitInit.alreadyRepo", existing.toString()));
                            return;
                        }
                        if (!r.ok()) {
                            gitError(tr("status.gitInit.failed"), r.message());
                            return;
                        }
                        host.setStatus(tr("status.gitInit.done", dir.toString()));
                        afterMutation(); // status bar, Commit window, gutter bars all light up
                    });
                });
    }

    void cloneRepo() {
        KeymapManager keymap = ops.keymap();
        TextField urlField = new TextField();
        urlField.setPromptText("https://github.com/user/repo.git");
        urlField.setPrefColumnCount(34);
        TextInputKeymap.install(urlField, keymap);
        TextField dirField = new TextField();
        dirField.setPromptText(tr("dialog.clone.dirPrompt"));
        dirField.setPrefColumnCount(28);
        TextInputKeymap.install(dirField, keymap);
        Button browse = new Button(tr("dialog.clone.browse"));
        browse.setFocusTraversable(false);

        String defaultParent = System.getProperty("user.home", "");
        boolean[] dirEdited = {false};
        boolean[] autoFilling = {false};
        urlField.textProperty().addListener((o, a, b) -> {
            if (!dirEdited[0]) {
                String name = repoNameFromUrl(b);
                autoFilling[0] = true;
                dirField.setText(suggestedCloneDir(defaultParent, name));
                autoFilling[0] = false;
            }
        });
        dirField.textProperty().addListener((o, a, b) -> {
            if (!autoFilling[0]) {
                dirEdited[0] = true; // user took control of the directory; stop auto-filling
            }
        });
        browse.setOnAction(e -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(tr("dialog.clone.parentTitle"));
            File parent = chooser.showDialog(host.window());
            if (parent != null) {
                String name = repoNameFromUrl(urlField.getText());
                String suggested = suggestedCloneDir(parent.getPath(), name.isEmpty() ? "repository" : name);
                dirField.setText(suggested.isEmpty() ? parent.getPath() : suggested);
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("dialog.clone.url")), 0, 0);
        grid.add(urlField, 1, 0, 2, 1);
        grid.add(new Label(tr("dialog.clone.directory")), 0, 1);
        grid.add(dirField, 1, 1);
        grid.add(browse, 2, 1);
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(dirField, Priority.ALWAYS);

        // Enable Clone only when both fields are filled (mirrors the old dialog's validation).
        BooleanProperty valid = new SimpleBooleanProperty(false);
        Runnable revalidate = () ->
                valid.set(!urlField.getText().isBlank() && !dirField.getText().isBlank());
        urlField.textProperty().addListener((o, a, b) -> revalidate.run());
        dirField.textProperty().addListener((o, a, b) -> revalidate.run());
        revalidate.run();

        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.clone.title"),
                grid,
                urlField,
                tr("dialog.clone.button"),
                valid,
                () -> {
                    String url = urlField.getText().strip();
                    // Resolved like the folder typed for git.init (~ and relative paths), validated, and
                    // its missing parent folders created — git creates the target, not what is above it.
                    CloneDestination target = CloneDestination.prepare(
                            dirField.getText(), defaultParent.isBlank() ? null : Path.of(defaultParent), defaultParent);
                    if (!target.ok()) {
                        host.setError(tr(target.errorKey(), target.shown()));
                        return;
                    }
                    Path destination = target.path();
                    host.setStatus(tr("status.cloning", url));
                    service.clone(url, destination, running(new String[] {"clone"}, r -> {
                        if (r.ok()) {
                            host.setStatus(tr("status.clonedInto", destination));
                            openClonedEntry(destination);
                        } else {
                            gitError(tr("status.git.cloneFailed"), r.message());
                        }
                    }));
                },
                null,
                false);
    }

    /**
     * The folder the clone form proposes: {@code parent/name}, or {@code ""} when the two do not make a path
     * (a repository name taken from a pasted URL can hold characters the file system rejects — that used to
     * throw from inside the text field's listener on every keystroke).
     */
    static String suggestedCloneDir(String parent, String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        try {
            return Path.of(parent == null ? "" : parent).resolve(name).toString();
        } catch (java.nio.file.InvalidPathException e) {
            return "";
        }
    }

    /**
     * Opens a representative file from a freshly cloned repo (its README if present) so Git activates for it —
     * no project involved. If there's no obvious entry file, the clone is just reported and the user can open
     * files from it (File: Open / Find File).
     */
    private void openClonedEntry(Path dir) {
        for (String candidate : new String[] {"README.md", "README.markdown", "README.rst", "README.txt", "README"}) {
            Path file = dir.resolve(candidate);
            if (Files.isRegularFile(file)) {
                ops.openPath(file);
                return;
            }
        }
        host.setStatus(tr("status.clonedOpen", dir));
    }

    /**
     * Derives the working-folder name for a clone URL: the last path segment with any {@code .git} suffix and
     * trailing slashes removed. Handles {@code https://…/repo.git}, {@code git@host:org/repo.git}, and local
     * paths. Pure/unit-tested. Returns {@code ""} when no name can be found.
     */
    static String repoNameFromUrl(String url) {
        if (url == null) {
            return "";
        }
        String s = url.strip();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".git")) {
            s = s.substring(0, s.length() - 4);
        }
        // The last segment after a '/' or (for scp-style "git@host:org/repo") a ':'.
        int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf(':'));
        String name = cut >= 0 ? s.substring(cut + 1) : s;
        return name.strip();
    }
}
