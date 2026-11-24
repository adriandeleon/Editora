package com.editora.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
import com.editora.editor.ControlNames;
import com.editora.editor.EditorBuffer;
import com.editora.editor.UiZoom;
import com.editora.git.GitChangeBars;
import com.editora.git.GitConflicts;
import com.editora.git.GitOperation;
import com.editora.git.GitPullMode;
import com.editora.git.GitSafety;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

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

        void setStatusBarBranch(@Nullable String branch, int ahead, int behind);

        void setCommitWindowAvailable(boolean available);

        void setGitLogWindowAvailable(boolean available);

        void setGitPanelStatus(@Nullable GitStatus status);

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
        @Nullable
        Path projectRoot();

        /** Invalidates queued or staged editor writes before Git mutates the same working-tree path. */
        void invalidatePendingWrite(Path file);

        /** Extends write invalidation to buffers owned by other application windows. */
        default void invalidatePendingWritesInOtherWindows(Path root, List<String> pathspecs) {}

        /**
         * Reports {@code message}, a failure, in another open window — its status line and message log, as an
         * unread error; false when no other window is open.
         */
        default boolean errorInAnotherWindow(String message) {
            return false;
        }

        /**
         * Whether the merge or rebase in progress holds the uncommitted changes in an autostash, given back when it
         * finishes: the Commit window's operation banner says so.
         */
        default void setGitOperationAutostash(boolean held) {}

        /** After a branch switch / pull, silently reload any open buffer whose file changed on disk. */
        void reloadAllFromDiskSilently();

        /**
         * Reloads {@code buffers} from disk although they hold unsaved edits: a confirmed Discard whose question
         * said those edits go too. The replaced text is kept in Local History.
         */
        default void revertBuffersFromDisk(List<EditorBuffer> buffers) {}

        /**
         * The open buffers at or under {@code root} in every window, or null for this window's only (test
         * stand-ins): a discard asked from one window reverts a file that may be open with unsaved edits in another.
         */
        default @Nullable List<EditorBuffer> buffersAcrossWindowsUnder(Path root) {
            return null;
        }

        /**
         * Closes {@code buffers}, whose files a confirmed Discard deleted, in whichever window holds them: their unsaved
         * edits were named in the question, so the tab does not keep them as the only copy.
         */
        default void closeDeletedBuffers(List<EditorBuffer> buffers) {}

        /**
         * Copies {@code files} into Local History as pre-delete revisions, then reports — on the FX thread —
         * whether every one that Local History can hold is safely stored. Untracked files have no other copy.
         */
        default void captureBeforeDelete(List<Path> files, Consumer<Boolean> completion) {
            completion.accept(true);
        }

        /**
         * Copies tracked files a Rollback is about to overwrite into Local History, then tells {@code completion}
         * on the FX thread the first one whose copy could not be made, or empty when every one is safe. A change
         * made outside Editora (a formatter, {@code sed}, another editor) is in no commit and no other copy.
         */
        default void captureBeforeOverwrite(List<Path> files, Consumer<java.util.Optional<Path>> completion) {
            completion.accept(java.util.Optional.empty());
        }

        /**
         * Saves {@code buffer} through the window's ordinary save path (encoding, line endings, format on save) and
         * tells {@code then}, on the FX thread, whether it is on disk afterwards. The write runs off the FX thread
         * (PERF-01, Pauta 6.2.0); this default, for test stand-ins, answers {@link #saveBeforeGit} at once.
         */
        default void saveBeforeGitThen(EditorBuffer buffer, Consumer<Boolean> then) {
            then.accept(saveBeforeGit(buffer));
        }

        /** A test stand-in's save, answered at once; the app saves through {@link #saveBeforeGitThen}. */
        default boolean saveBeforeGit(EditorBuffer buffer) {
            return false;
        }

        /** Opens the Commit tool window. */
        void openCommitWindow();

        /** Focuses the Commit tool window's message box (deferred onto the FX thread). */
        void focusCommitMessage();

        /** This window's active keymap (for installing caret navigation on the clone form's text fields). */
        com.editora.command.KeymapManager keymap();

        /** Opens (or focuses) the tab for {@code file} (used to open a file from a fresh clone). */
        void openPath(@Nullable Path file);

        /** Re-syncs the Settings window's "inline blame" checkbox after the blame toggle. */
        void syncBlameCheck();

        /** Opens a read-only diff of {@code repoRel} at {@code hash} vs its parent (a blame-annotation click).
         *  Routed through the window to reach {@code DiffCoordinator} without a circular dependency. */
        void openCommitFileDiff(String hash, String repoRel, @Nullable String origRepoRel);
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final GitService service = new GitService();

    /** The blame column, its options and revision tabs; see {@link GitBlameCoordinator}. */
    private final GitBlameCoordinator blame;

    /** The stash list, stash options and conflict reporting; see {@link GitStashCoordinator}. */
    private final GitStashCoordinator stashes;

    /** Applying and creating patches; see {@link GitPatchCoordinator}. */
    private final GitPatchCoordinator patches;

    /** Amend, sign-off, commit-and-push, undo, template, recent messages; see {@link GitCommitCoordinator}. */
    private final GitCommitCoordinator commits;

    /** "Fetch automatically"; see {@link GitAutoFetch}. */
    private final GitAutoFetch autoFetch;

    private @Nullable Path repoRoot;
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
    @Nullable
    Runnable pushHandler;

    GitCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
        this.blame = new GitBlameCoordinator(host, this, ops);
        this.stashes = new GitStashCoordinator(host, this);
        this.patches = new GitPatchCoordinator(host, this);
        this.commits = new GitCommitCoordinator(host, this, ops);
        this.autoFetch = new GitAutoFetch(host, this);
    }

    GitCommitCoordinator commits() {
        return commits;
    }

    GitAutoFetch autoFetch() {
        return autoFetch;
    }

    GitBlameCoordinator blame() {
        return blame;
    }

    GitStashCoordinator stashes() {
        return stashes;
    }

    GitPatchCoordinator patches() {
        return patches;
    }

    /**
     * Hands the stash, patch and blame features the two window surfaces they open tabs through. Late-bound:
     * the diff coordinator is itself built from this one.
     */
    void attachWindow(DiffCoordinator diff, Consumer<EditorBuffer> openTab) {
        blame.attach(openTab);
        stashes.attach(diff);
        patches.attach(openTab);
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
    @Nullable
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

    /**
     * Guard shared by every repo-only operation: when there's no active repo, echoes the standard
     * "not a repo" / "git not installed" status and returns {@code true} so the caller early-returns.
     * Returns {@code false} (no echo) when a repo is present.
     */
    boolean reportIfNoRepo() {
        if (repoRoot != null) {
            return false;
        }
        host.setStatus(tr(service.gitAvailable() ? "status.notARepo" : "status.gitNotInstalled"));
        return true;
    }

    /** The active repo root, or {@code null} after {@link #reportIfNoRepo()}'s status when there is none. */
    @Nullable
    Path repoRootOrReport() {
        Path root = repoRoot;
        if (root == null) {
            reportIfNoRepo();
        }
        return root;
    }

    /**
     * The path whose repo drives the Git UI: the active file, else the active project root, else — on a diff
     * or review tab — the repository already active, else null.
     */
    @Nullable
    Path contextPath() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null) {
            return file;
        }
        Path revisionRoot = blame.revisionRoot(b); // a "file as of a commit" tab stays in its repository
        if (revisionRoot != null) {
            return revisionRoot;
        }
        Path project = ops.projectRoot();
        if (project != null) {
            return project;
        }
        // A diff or review tab has no file of its own. In a window without a project that used to mean "no
        // repository": opening a commit's diff from the Git Log dropped the repository under the log that
        // had just opened it — the log closed, and a file history was forgotten. Such a tab stays in the
        // repository it was opened in.
        return repoRoot != null && GitWindowGate.showsGitView(host.window()) ? repoRoot : null;
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
        autoFetch.apply();
        ops.setStatusBarGitEnabled(on);
        if (!on) {
            closeMetadataWatch();
            if (treeRefresh != null) {
                treeRefresh.stop(); // no refresh for a Git that was switched off
            }
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
        coalescer.fullStarted(refreshClock.getAsLong());
        if (trailingRefresh != null) {
            trailingRefresh.stop(); // this one covers it
        }
        EditorBuffer b = host.activeBuffer();
        Path context = contextPath();
        // No active file and no project context (e.g. the Welcome tab in a No-Project window): show no Git
        // rather than falling back to the process working directory's repo.
        if (context == null) {
            service.invalidateRefreshes();
            applyState(GitService.RepoState.NONE);
            return;
        }
        // Git runs where the files are: for a remote (SFTP) context that is its host, when it may run commands.
        if (!RemoteExec.runsFor(context)) {
            service.invalidateRefreshes();
            applyState(GitService.RepoState.NONE);
            return;
        }
        // Only diff a real, non-huge file (huge files disable the gutter anyway). A narrowed buffer shows
        // region-relative lines, so whole-file change bars would land on the wrong ones: it gets none.
        // An encrypted file's text would be diffed against its ciphertext in the repository: meaningless bars.
        Path diffFile = diffFileOf(b);
        service.refresh(context, diffFile, this::applyState);
    }

    /** The active file when its HEAD diff feeds the gutter, else null (see {@link #refresh()}). */
    private static @Nullable Path diffFileOf(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        return (b != null
                        && file != null
                        && !b.isLargeFile()
                        && !b.isNarrowed()
                        && !b.encryption().isEncrypted())
                ? file
                : null;
    }

    /** Light refreshes within a moment of a full one keep its status (F2); see {@link GitRefreshCoalescer}. */
    private final GitRefreshCoalescer coalescer = new GitRefreshCoalescer();

    // --- changes made outside Editora (a terminal's git) ---------------------------------------------------

    /** How long the git folder must be quiet before a change heard in it refreshes: a rebase writes HEAD often. */
    static final long EXTERNAL_QUIET_MS = 300;

    /** A change heard this soon after one of Editora's own commands finished is that command's: it refreshed. */
    static final long OWN_ECHO_NANOS = 1_000_000_000L;

    /** The watch on the active repository's index, HEAD and branches ({@link GitMetadataWatcher}), or null. */
    private GitMetadataWatcher.@Nullable Handle metadataWatch;

    /** The repository {@link #metadataWatch} is on. */
    private @Nullable Path watchedRoot;

    /** When this window's last git command finished ({@link #afterMutation(Path)}), on {@link System#nanoTime}. */
    private volatile long lastOwnMutationNanos = System.nanoTime() - OWN_ECHO_NANOS;

    /** When a change was last heard in the watched git folder, on {@link System#nanoTime} (FX thread). */
    private long lastHeardNanos = System.nanoTime() - 2 * OWN_ECHO_NANOS;

    /** The refresh a burst of outside changes ends with; created on first use, on the FX thread. */
    private javafx.animation.@Nullable PauseTransition externalRefresh;

    /** How many refreshes outside changes have caused (tests). */
    int externalRefreshesForTest;

    /** Treats this window's last git command as long finished, so the next change heard is from outside (tests). */
    void forgetOwnMutationsForTest() {
        lastOwnMutationNanos = System.nanoTime() - 2 * OWN_ECHO_NANOS;
    }

    /** Watches {@code root}'s git folder (null: none), the active repository, from now on. */
    private void watchMetadata(@Nullable Path root) {
        GitMetadataWatcher.Handle current = metadataWatch;
        if (java.util.Objects.equals(root, watchedRoot) && (current == null || !current.stale())) {
            return; // a stale watch (the git folder made again, a watch the system dropped) is made again below
        }
        closeMetadataWatch();
        watchedRoot = root;
        if (root == null || !com.editora.vfs.Vfs.isLocal(root)) {
            return; // a remote host's .git is not watched: focus regain refreshes it
        }
        metadataWatch = GitMetadataWatcher.SHARED.watch(
                root, () -> javafx.application.Platform.runLater(() -> metadataChanged(root)));
    }

    private void closeMetadataWatch() {
        GitMetadataWatcher.Handle watch = metadataWatch;
        metadataWatch = null;
        watchedRoot = null;
        if (watch != null) {
            watch.close();
        }
        if (externalRefresh != null) {
            externalRefresh.stop();
        }
    }

    /** The git folder of {@code root} changed: a refresh once it has been quiet a moment (FX thread). */
    private void metadataChanged(Path root) {
        if (!root.equals(watchedRoot) || !isEnabled()) {
            return;
        }
        lastHeardNanos = System.nanoTime();
        if (externalRefresh == null) {
            externalRefresh = new javafx.animation.PauseTransition();
            externalRefresh.setOnFinished(e -> externalRefreshDue());
        }
        externalRefresh.setDuration(javafx.util.Duration.millis(EXTERNAL_QUIET_MS));
        externalRefresh.playFromStart(); // each change in a burst moves the refresh back: one for the burst
    }

    /** The refresh after files were renamed, moved or deleted in the Project tree; created on first use. */
    private javafx.animation.@Nullable PauseTransition treeRefresh;

    /**
     * Files were renamed, moved or deleted by Editora (the Project tree, a rename refactoring): the status changed,
     * but the tree's watcher takes those events for its own operation's echo and nothing else refreshed Git. One
     * refresh for a batch (a multi-delete reports each file), a moment after its last file.
     */
    void filesChangedByEditora() {
        if (!isEnabled()) {
            return;
        }
        if (treeRefresh == null) {
            treeRefresh = new javafx.animation.PauseTransition(javafx.util.Duration.millis(EXTERNAL_QUIET_MS));
            treeRefresh.setOnFinished(e -> refresh());
        }
        treeRefresh.playFromStart();
    }

    private void externalRefreshDue() {
        long delay = externalRefreshDelay(System.nanoTime(), lastOwnMutationNanos, lastHeardNanos);
        if (delay < 0) {
            return; // Editora's own command wrote it, and its completion refreshed already
        }
        if (delay > 0 && externalRefresh != null) {
            // Heard after this window's own command finished, but inside its echo: once the echo is over.
            externalRefresh.setDuration(javafx.util.Duration.millis(Math.max(1, delay / 1_000_000L)));
            externalRefresh.playFromStart();
            return;
        }
        externalRefreshesForTest++;
        refresh();
    }

    /**
     * What a burst of outside changes whose quiet period ended at {@code now} does, from when this window's last git
     * command finished and when the burst's last change was heard: 0 refresh now; a positive count of nanoseconds,
     * refresh once the command's echo window is over (a change heard after the command finished is not its echo); -1
     * nothing, when the command explains every change (all heard before it finished, and its own refresh came
     * after). Pure.
     */
    static long externalRefreshDelay(long now, long lastOwnMutation, long lastHeard) {
        long sinceMutation = now - lastOwnMutation;
        if (sinceMutation >= OWN_ECHO_NANOS) {
            return 0;
        }
        if (lastHeard - lastOwnMutation <= 0) {
            return -1;
        }
        return OWN_ECHO_NANOS - sinceMutation;
    }

    /** The full refresh that ends a window of light ones; created on first use, on the FX thread. */
    private javafx.animation.@Nullable PauseTransition trailingRefresh;

    /** The clock the coalescing window is measured on; a test moves it instead of waiting. */
    java.util.function.LongSupplier refreshClock = System::nanoTime;

    /**
     * {@link #refresh()} for a tab switch, a save or an autosave. A moment after a full refresh of the same
     * repository it keeps that status and diffs only the active file for its gutter, and a full refresh follows at
     * the end of the moment: switching tabs or saving often in a large work tree ran a whole-repository
     * {@code git status} each time. The first trigger after a quiet spell refreshes in full, at once.
     */
    void refreshSoon() {
        if (!isEnabled()) {
            return;
        }
        long delay = coalescer.lightDelay(refreshClock.getAsLong());
        Path root = repoRoot;
        Path context = contextPath();
        if (delay < 0
                || root == null
                || !lastStatus.isRepo()
                || context == null
                || !com.editora.vfs.Vfs.isLocal(context)
                || !RemoteExec.runsFor(context)) {
            refresh();
            return;
        }
        Path diffFile = diffFileOf(host.activeBuffer());
        service.refreshDiff(context, root, diffFile, state -> {
            if (state == null) {
                refresh(); // another repository: its status is wanted now
                return;
            }
            if (!root.equals(repoRoot) || !lastStatus.isRepo()) {
                return; // a full refresh landed meanwhile with another answer; it stands
            }
            applyState(
                    new GitService.RepoState(
                            root,
                            state.diffFile(),
                            lastStatus,
                            state.changes(),
                            state.hunks(),
                            "",
                            state.operation(),
                            state.hunkList()),
                    false);
        });
        if (trailingRefresh == null) {
            trailingRefresh = new javafx.animation.PauseTransition();
            trailingRefresh.setOnFinished(e -> refresh());
        }
        if (trailingRefresh.getStatus() != javafx.animation.Animation.Status.RUNNING) {
            trailingRefresh.setDuration(javafx.util.Duration.millis(Math.max(1, delay / 1_000_000L)));
            trailingRefresh.playFromStart();
        }
    }

    /** Applies a completed Git refresh to the status bar, tool window, and active buffer's gutter. */
    void applyState(GitService.RepoState state) {
        applyState(state, true);
    }

    /**
     * As {@link #applyState(GitService.RepoState)}; {@code freshStatus} false when the state carries the status
     * this coordinator already had ({@link #refreshSoon}): what is read per status (the Commit window's line
     * counts) waits for the full refresh that follows.
     */
    private void applyState(GitService.RepoState state, boolean freshStatus) {
        Path root = state.root();
        Path previousRoot = repoRoot;
        GitStatus previousStatus = lastStatus;
        repoRoot = root;
        lastStatus = state.isRepo() ? state.status() : GitStatus.NOT_A_REPO;
        // Before the window surfaces below are told: they re-evaluate menu and palette enablement, which asks
        // whether an operation is in progress.
        operation = state.isRepo() ? state.operation() : GitOperation.NONE;
        EditorBuffer b = host.activeBuffer();
        // The Log needs a repository. The Commit window does not: outside one it shows "Not a Git repository"
        // with a Clone button, which is the way in. (Transient; the user's show/hide preference is untouched.)
        ops.setCommitWindowAvailable(isEnabled());
        ops.setGitLogWindowAvailable(state.isRepo());
        watchMetadata(state.isRepo() ? root : null);
        if (!state.isRepo() || root == null) {
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
                refreshBlame(b); // clears it, except on a revision tab, which carries its own repository
            }
            commits.repositoryApplied(null);
            return;
        }
        var status = state.status();
        branchName = status.branch();
        upstream = status.upstream();
        repositoryListener.accept(repoRoot, branchName);
        ops.setStatusBarBranch(status.branch(), status.ahead(), status.behind());
        ops.setGitRefusal("");
        ops.setGitOperation(operation, GitConflicts.unmerged(status).size());
        lookForAutostash(root, operation);
        ops.setGitPanelStatus(status);
        if (freshStatus) {
            commits.repositoryApplied(repoRoot); // the template of a new repository, the line counts of this status
        }
        if (!status.equals(previousStatus) || !root.equals(previousRoot)) {
            // Built only when the status moved: a path resolve and normalize per changed file, each refresh.
            lastStatusByPath = com.editora.git.GitFileStatus.byPath(status, root);
        }
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
        afterMutation(repoRoot);
    }

    /**
     * {@link #afterMutation()} for a mutation in {@code mutated}, the repository its command ran in: the open
     * buffers of <em>that</em> repository are diffed again. A mutation in another repository than the active one
     * (a Project-tree action in a nested repository, one that finished after a tab switch) used to diff the
     * active repository's buffers instead — a {@code git diff} each, for nothing — and leave its own bars stale.
     */
    void afterMutation(@Nullable Path mutated) {
        refresh(); // status bar + tool window + active buffer's gutter
        mutationListener.run();
        lastOwnMutationNanos = System.nanoTime(); // the .git watcher's echo of it is not a change from outside
        if (mutated == null) {
            return;
        }
        Path root = mutated;
        EditorBuffer active = host.activeBuffer();
        boolean activeRepository = root.equals(repoRoot);
        host.forEachBuffer(buf -> {
            // The active buffer of the active repository is handled by refresh(); skip non-file/huge buffers and
            // files outside the mutated repository.
            Path bufPath = buf.getPath();
            if (bufPath == null
                    || buf.isNarrowed()
                    || !GitChangeBars.shouldRediff(
                            bufPath,
                            root,
                            buf == active && activeRepository,
                            buf.isLargeFile() || buf.encryption().isEncrypted())) {
                return;
            }
            Path path = bufPath.toAbsolutePath();
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
        commits.invalidate();
    }

    void shutdown() {
        autoFetch.stop();
        closeMetadataWatch();
        if (trailingRefresh != null) {
            trailingRefresh.stop(); // no full refresh for a window that is going away
        }
        if (treeRefresh != null) {
            treeRefresh.stop();
        }
        service.shutdown();
    }

    // --- Inline blame annotations (IntelliJ-style gutter column): see GitBlameCoordinator -----------

    boolean isBlameEnabled() {
        return blame.isEnabled();
    }

    void applyBlame() {
        blame.apply();
    }

    void toggleBlame() {
        blame.toggle();
    }

    void annotateActive() {
        blame.annotateActive();
    }

    void blameShowCommit() {
        blame.showCommitAtCaret();
    }

    /**
     * Git: Copy Permalink — the web address of the active file at the HEAD commit, the selected lines (or the caret's)
     * marked, on the clipboard; the status bar says what was copied, or why nothing was.
     */
    void copyPermalink() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null) {
            host.setStatus(tr("status.git.permalink.noFile"));
            return;
        }
        int[] lines = GitPermalinks.selectedLines(b.getArea());
        int start = lines[0];
        int end = lines[1];
        service.permalink(file, start, end, link -> host.setStatus(GitPermalinks.copy(link)));
    }

    void onGutterBlameClick(EditorBuffer buffer, int line) {
        blame.onGutterClick(buffer, line);
    }

    void refreshBlame(@Nullable EditorBuffer b) {
        blame.refresh(b);
    }

    // --- user-facing operations (commit / branch / stash / discard) --------------------------------

    /** Runs a git command, reporting success/failure + refreshing state afterward. */
    void gitOp(String successMessage, String... args) {
        Path root = repoRootOrReport();
        if (root != null) {
            gitOpIn(root, successMessage, args);
        }
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
                    afterMutation(root);
                }),
                args);
    }

    /**
     * Writes the unsaved buffers of {@code root} that {@code pathspecs} select (all of them when empty) to
     * disk before git reads those files. git only ever sees the saved copy, so staging or committing a file
     * that is still being edited silently recorded its <em>previous</em> text. Each buffer goes through the
     * window's normal save; one that cannot be saved stops the operation, with the reason in the status bar.
     *
     * The writes run off the FX thread (PERF-01, Pauta 6.2.0): {@code then} runs once every one has landed, and not
     * at all when one could not be saved — the caller's command does not run.
     */
    void saveUnsaved(Path root, List<String> pathspecs, Runnable then) {
        saveUnsaved(root, pathspecs, then, () -> {});
    }

    /** As above; {@code failed} runs, after the error is said, when a buffer could not be saved. */
    void saveUnsaved(Path root, List<String> pathspecs, Runnable then, Runnable failed) {
        List<EditorBuffer> unsaved = new ArrayList<>();
        host.forEachBuffer(buffer -> {
            Path file = buffer.getPath();
            if (file == null || !buffer.isDirty()) {
                return; // a file on another filesystem than the repository's is not in it: repoRelative says so
            }
            String relative = GitService.repoRelative(root, file);
            if (relative != null && (pathspecs.isEmpty() || pathspecs.stream().anyMatch(p -> selects(p, relative)))) {
                unsaved.add(buffer);
            }
        });
        SaveChain.all(unsaved, ops::saveBeforeGitThen, unsavable -> {
            if (unsavable != null) {
                host.setError(tr("status.git.unsavedFailed", unsavable.getTitle()));
                failed.run();
            } else {
                then.run();
            }
        });
    }

    /**
     * Shows {@code git <subcommand>…} in the status bar's background-task segment until the command reports
     * back. A commit inside slow hooks, a checkout of a large tree or a push to a slow remote can run for
     * minutes; without this the editor looked idle (or hung) for the whole of it.
     */
    Consumer<ProcessRunner.Result> running(String[] args, Consumer<ProcessRunner.Result> onResult) {
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
    boolean confirmDestructive(Confirm.Question question) {
        return Confirm.ask(host.window(), question);
    }

    /** The words of the question before files that still hold conflict markers ({@code marked}) are staged. */
    static Confirm.Question stageConflictQuestion(List<String> marked) {
        return new Confirm.Question(
                tr("dialog.stageConflict.title"),
                null,
                marked.size() == 1
                        ? tr("dialog.stageConflict.message", marked.get(0))
                        : tr("dialog.stageConflict.messageMany", marked.size(), marked.get(0)),
                tr("dialog.stageConflict.action"));
    }

    /** The words of the Discard question, around the sentence that says what is lost. */
    static Confirm.Question discardQuestion(String prompt) {
        return new Confirm.Question(tr("dialog.discard.title"), null, prompt, tr("dialog.discard"));
    }

    /** The words of the question before every change is discarded: {@code tracked} files and {@code untracked}. */
    static Confirm.Question discardAllQuestion(int tracked, int untracked) {
        return discardAllQuestion(tracked, untracked, "");
    }

    /** As above, with {@code notes} (what else the user should know: the Local History limit, unsaved edits). */
    static Confirm.Question discardAllQuestion(int tracked, int untracked, String notes) {
        return new Confirm.Question(
                tr("dialog.discardAll.title"),
                null,
                tr("dialog.discardAll.message", tracked, untracked) + notes,
                tr("dialog.discardAll.action"));
    }

    /** The words of the question before the operation under way (named {@code name}) skips a commit. */
    static Confirm.Question skipOperationQuestion(String name) {
        return new Confirm.Question(
                tr("dialog.skipOperation.title", name),
                null,
                tr("dialog.skipOperation.message", name),
                tr("dialog.skipOperation.action"));
    }

    /** The words of the question before the operation under way is aborted, saying what its kind loses. */
    static Confirm.Question abortOperationQuestion(GitOperation.Kind kind) {
        String name = operationName(kind);
        String lost = switch (kind) {
            case MERGE -> tr("dialog.abortOperation.merge");
            case REBASE -> tr("dialog.abortOperation.rebase");
            case CHERRY_PICK -> tr("dialog.abortOperation.cherryPick");
            default -> tr("dialog.abortOperation.revert");
        };
        return new Confirm.Question(
                tr("dialog.abortOperation.title", name), null, lost, tr("dialog.abortOperation.action", name));
    }

    /**
     * The words of the question before one side ({@code side}, also the button's label) is taken for the conflicted
     * {@code paths}: the one path by name, or how many.
     */
    static Confirm.Question acceptSideQuestion(String side, List<String> paths) {
        return new Confirm.Question(
                tr("dialog.acceptSide.title"),
                null,
                paths.size() == 1
                        ? tr("dialog.acceptSide.message", side, paths.get(0))
                        : tr("dialog.acceptSide.messageMany", side, paths.size()),
                side);
    }

    /**
     * {@code git add} over one or more repo-relative paths (the Commit window's Stage action, which is
     * multi-select). One invocation — and therefore one refresh — for the whole set.
     */
    void gitStagePaths(List<String> paths) {
        Path root = paths.isEmpty() ? null : repoRootOrReport();
        if (root != null) {
            stageIn(root, paths);
        }
    }

    private void stageIn(Path root, List<String> paths) {
        stageIn(root, root.equals(repoRoot) ? lastStatus : null, paths);
    }

    private void stageIn(Path root, @Nullable GitStatus status, List<String> paths) {
        saveUnsaved(
                root,
                paths,
                () -> confirmStagingMarkers(
                        root,
                        status,
                        paths,
                        () -> gitOpIn(
                                root,
                                paths.size() == 1
                                        ? tr("status.git.staged", paths.get(0))
                                        : tr("status.git.stagedMany", paths.size()),
                                argv(paths, "add", "--"))));
    }

    /** {@code git add -A}: the Commit window's Stage All. */
    void gitStageAll() {
        Path root = repoRootOrReport(); // the confirmation below runs a nested event loop: the root is captured first
        if (root != null) {
            saveUnsaved(
                    root,
                    List.of(),
                    () -> confirmStagingMarkers(
                            root, lastStatus, List.of(), () -> gitOpIn(root, tr("status.git.stagedAll"), "add", "-A")));
        }
    }

    /** The largest conflicted file read to look for leftover markers before it is staged. */
    static final long MARKER_SCAN_MAX_BYTES = 4L * 1024 * 1024;

    /**
     * Staging an unmerged path tells git "this conflict is resolved". Doing that to a file that still holds
     * {@code <<<<<<<} markers commits the markers, so it is confirmed first, naming the file. Called after
     * the unsaved buffers were written, so the disk copy is what git is about to stage.
     *
     * <p>The unmerged files are read off the FX thread (PERF-01, Pauta 6.2.0); with none, {@code then} runs at once.
     *
     * @param pathspecs the paths being staged; empty means the whole repository (Stage All)
     * @param then the staging, unless the user declined
     */
    private void confirmStagingMarkers(Path root, @Nullable GitStatus status, List<String> pathspecs, Runnable then) {
        if (status == null || GitConflicts.unmerged(status).isEmpty()) {
            then.run();
            return;
        }
        DebugPlanning.offFx("git-conflict-markers", () -> unresolvedAmong(root, status, pathspecs), a -> {
            List<String> marked = a.value() == null ? List.of() : a.value();
            if (marked.isEmpty() || confirmDestructive(stageConflictQuestion(marked))) {
                then.run();
            }
        });
    }

    /** The unmerged paths {@code pathspecs} select whose file on disk still contains conflict markers. */
    static List<String> unresolvedAmong(Path root, @Nullable GitStatus status, List<String> pathspecs) {
        List<String> marked = new ArrayList<>();
        List<GitStatus.FileEntry> unmerged = status == null ? List.of() : GitConflicts.unmerged(status);
        for (GitStatus.FileEntry entry : unmerged) {
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
        Path root = paths.isEmpty() ? null : repoRootOrReport();
        if (root != null) {
            unstageIn(root, lastStatus, paths);
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
    static @Nullable String firstUnmergedUnder(GitStatus status, List<String> pathspecs) {
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
        GitStatus status = lastStatus;
        // The Commit window's Changes rows go back to the index — except a file added with "git add -N", whose
        // index entry is an empty placeholder: checkout would empty it. It leaves the index and is deleted.
        List<String> worktree = new ArrayList<>(tracked.size());
        java.util.LinkedHashSet<String> added = new java.util.LinkedHashSet<>();
        boolean keepsStaged = false;
        for (String spec : tracked) {
            boolean onlyIntentToAdd = false;
            for (GitStatus.FileEntry entry : status.files()) {
                if (entry.path() == null || entry.untracked() || !selects(spec, entry.path())) {
                    continue;
                }
                if (GitDiscardPlan.intentToAdd(entry)) {
                    added.add(entry.path());
                    onlyIntentToAdd |= spec.equals(entry.path());
                } else if (entry.staged() && entry.unstaged()) {
                    keepsStaged = true; // only the unstaged part goes: the prompt must not say "all changes"
                }
            }
            if (!onlyIntentToAdd) {
                worktree.add(spec);
            }
        }
        discard(
                repoRoot,
                status,
                new Discard(List.copyOf(worktree), List.of(), untracked, List.copyOf(added), keepsStaged, List.of()));
    }

    /**
     * The paths of one discard, by the command each needs: {@code worktree} paths are restored from the
     * index ({@code checkout --}), {@code head} paths have staged changes and go back to the last commit
     * ({@code restore --staged --worktree --source=HEAD}), {@code untracked} ones are deleted ({@code clean}),
     * and {@code added} ones — in the index but in no commit — leave the index ({@code rm --cached}) and are
     * deleted. {@code keepsStaged}: some {@code worktree} path also has staged changes, which stay.
     * {@code overwritten} are the files the first two put back, as absolute paths, copied to Local History first.
     */
    private record Discard(
            List<String> worktree,
            List<String> head,
            List<String> untracked,
            List<String> added,
            boolean keepsStaged,
            List<Path> overwritten) {
        Discard(List<String> worktree, List<String> head, List<String> untracked) {
            this(worktree, head, untracked, List.of(), false, List.of());
        }

        /** This discard with the tracked files under {@code root} that it overwrites, as {@code status} lists them. */
        Discard overwriting(Path root, @Nullable GitStatus status) {
            List<String> tracked = new ArrayList<>(worktree);
            tracked.addAll(head);
            List<Path> overwritten = new ArrayList<>(GitDiscardCaptures.overwritten(root, status, tracked));
            // An added file is deleted, not overwritten: it is copied with the deleted files.
            java.util.Set<Path> deleted = new java.util.HashSet<>();
            for (String path : added) {
                deleted.add(root.resolve(path).normalize());
            }
            overwritten.removeIf(deleted::contains);
            return new Discard(worktree, head, untracked, added, keepsStaged, List.copyOf(overwritten));
        }

        /** This discard without the untracked rows {@code kept} (nested repositories, left alone). */
        Discard keeping(List<String> kept) {
            if (kept.isEmpty()) {
                return this;
            }
            List<String> rest = new ArrayList<>(untracked);
            rest.removeAll(kept);
            return new Discard(worktree, head, List.copyOf(rest), added, keepsStaged, overwritten);
        }

        boolean isEmpty() {
            return worktree.isEmpty() && head.isEmpty() && untracked.isEmpty() && added.isEmpty();
        }

        /** The tracked paths: put back from the index or the last commit. */
        List<String> tracked() {
            List<String> all = new ArrayList<>(worktree);
            all.addAll(head);
            return all;
        }

        /** The files this deletes: the untracked rows and the added files. */
        List<String> deleted() {
            List<String> all = new ArrayList<>(untracked);
            all.addAll(added);
            return all;
        }

        List<String> affected() {
            List<String> all = tracked();
            all.addAll(untracked);
            all.addAll(added);
            return all;
        }
    }

    private void discard(@Nullable Path root, GitStatus status, Discard discard) {
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
        List<String> untracked = discard.untracked();
        Discard copying = discard.overwriting(root, status);
        List<EditorBuffer> edited = editedBuffers(root, discard);
        // which rows are folders, and which of those are repositories of their own, is looked at off the FX
        // thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then(
                "git-untracked-look",
                () -> GitUntrackedDelete.look(root, untracked),
                looked -> discardLooked(root, copying, looked.or(GitUntrackedDelete.Look.NONE), edited));
    }

    /**
     * The open buffers of {@code root}, in every window, with unsaved edits whose files {@code discard} puts back or
     * deletes (an untracked or intent-to-add file): a discard changes the disk, and the buffer — had nobody said so
     * — kept the edits and saved them again later.
     */
    private List<EditorBuffer> editedBuffers(Path root, Discard discard) {
        List<String> specs = discard.affected();
        List<EditorBuffer> edited = new ArrayList<>();
        if (specs.isEmpty()) {
            return edited;
        }
        Consumer<EditorBuffer> look = buffer -> {
            if (buffer.isDirty() && !edited.contains(buffer) && selectedBy(root, buffer, specs)) {
                edited.add(buffer);
            }
        };
        List<EditorBuffer> everywhere = ops.buffersAcrossWindowsUnder(root);
        if (everywhere == null) {
            host.forEachBuffer(look);
        } else {
            everywhere.forEach(look);
        }
        return edited;
    }

    /** Whether {@code buffer}'s file is one of {@code specs}, paths of the repository at {@code root}, or under one. */
    private static boolean selectedBy(Path root, EditorBuffer buffer, List<String> specs) {
        Path file = buffer.getPath();
        if (file == null || file.getFileSystem() != root.getFileSystem()) {
            return false; // paths of two filesystems cannot be compared
        }
        String relative = GitService.repoRelative(root, file);
        return relative != null && specs.stream().anyMatch(spec -> selects(spec, relative));
    }

    /** What the prompt adds after what is discarded: unsaved edits that go too, nested repositories left alone. */
    private static String discardNotes(List<EditorBuffer> edited, List<String> nested) {
        StringBuilder notes = new StringBuilder();
        if (!edited.isEmpty()) {
            notes.append("\n\n").append(tr("dialog.discard.unsavedEdits", edited.size()));
        }
        if (!nested.isEmpty()) {
            notes.append("\n\n")
                    .append(
                            nested.size() == 1
                                    ? tr("dialog.discard.nestedRepo", nested.get(0))
                                    : tr("dialog.discard.nestedRepos", nested.size()));
        }
        return notes.toString();
    }

    /** The buffers of {@code edited} whose files {@code discard} still changes. */
    private static List<EditorBuffer> stillSelected(Path root, List<EditorBuffer> edited, Discard discard) {
        List<String> specs = discard.affected();
        return edited.stream().filter(b -> selectedBy(root, b, specs)).toList();
    }

    /** Whether Local History keeps copies of what a discard deletes or overwrites. */
    private boolean historyOn() {
        return host.settings().isLocalHistory() && !host.simpleModeActive();
    }

    private void discardLooked(Path root, Discard all, GitUntrackedDelete.Look look, List<EditorBuffer> anyEdited) {
        Discard discard = all.keeping(look.nested());
        if (discard.isEmpty()) {
            host.setStatus(tr("status.git.nestedRepoOnly", look.nested().get(0)));
            return;
        }
        List<EditorBuffer> edited = stillSelected(root, anyEdited, discard); // a nested repository is left alone
        String notes = discardNotes(edited, look.nested());
        List<String> untracked = discard.untracked();
        int folders = look.folders() - look.nested().size();
        if (folders > 0) {
            // Git shows a wholly untracked folder as one row; ask it what deleting that row removes.
            service.untrackedFiles(root, untracked, files -> {
                if (files == null) {
                    host.setError(tr("status.git.opFailed"));
                    return;
                }
                List<String> deleted = new ArrayList<>(files);
                deleted.addAll(discard.added());
                String prompt =
                        GitUntrackedDelete.prompt(discard.tracked().size(), untracked, folders, deleted, historyOn());
                confirmAndDiscard(root, discard, prompt + notes, GitUntrackedDelete.captures(root, deleted), edited);
            });
            return;
        }
        List<String> deleted = discard.deleted();
        String prompt = discardPrompt(discard) + GitUntrackedDelete.historyNote(deleted.size(), historyOn());
        confirmAndDiscard(root, discard, prompt + notes, GitUntrackedDelete.captures(root, deleted), edited);
    }

    /**
     * Confirms, copies the untracked files about to be deleted into Local History (they are in no commit, so
     * nothing else could bring them back), and only then runs the commands.
     */
    private void confirmAndDiscard(
            Path root, Discard discard, String prompt, List<Path> untrackedFiles, List<EditorBuffer> edited) {
        if (!confirmDestructive(discardQuestion(prompt))) {
            return;
        }
        captureThenDiscard(root, discard, discardSuccessMessage(discard), untrackedFiles, edited);
    }

    /**
     * Copies the tracked files about to be overwritten and the untracked files about to be deleted into Local
     * History, then runs the confirmed discard; nothing runs when a copy could not be made.
     */
    private void captureThenDiscard(
            Path root, Discard discard, String successMessage, List<Path> untrackedFiles, List<EditorBuffer> edited) {
        List<String> affected = discard.affected();
        invalidatePendingWrites(root, affected);
        if (discard.overwritten().isEmpty()) {
            captureUntrackedThenDiscard(root, discard, successMessage, untrackedFiles, affected, edited);
            return;
        }
        ops.captureBeforeOverwrite(discard.overwritten(), failed -> {
            if (failed.isEmpty()) {
                captureUntrackedThenDiscard(root, discard, successMessage, untrackedFiles, affected, edited);
            } else {
                host.setError(tr("status.git.discardHistoryFailed", failed.get().getFileName()));
            }
        });
    }

    private void captureUntrackedThenDiscard(
            Path root,
            Discard discard,
            String successMessage,
            List<Path> untrackedFiles,
            List<String> affected,
            List<EditorBuffer> edited) {
        if (untrackedFiles.isEmpty()) {
            runDiscard(root, discard, successMessage, affected, edited);
            return;
        }
        ops.captureBeforeDelete(untrackedFiles, kept -> {
            if (kept) {
                runDiscard(root, discard, successMessage, affected, edited);
            } else {
                host.setStatus(tr("project.deleteHistoryFailed"));
            }
        });
    }

    /** The commands of a confirmed discard, in the order they run. Pure. */
    private static List<String[]> discardCommands(Discard discard) {
        List<String[]> commands = new ArrayList<>(5);
        if (!discard.added().isEmpty()) {
            // First: once out of the index they are untracked, so the restores below leave them alone.
            commands.add(argv(discard.added(), "rm", "--cached", "-f", "-q", "--"));
        }
        if (!discard.worktree().isEmpty()) {
            commands.add(argv(discard.worktree(), "checkout", "--"));
        }
        if (!discard.head().isEmpty()) {
            commands.add(argv(discard.head(), "restore", "--staged", "--worktree", "--source=HEAD", "--"));
        }
        if (!discard.untracked().isEmpty()) {
            commands.add(argv(discard.untracked(), "clean", "-f", "--"));
        }
        if (!discard.added().isEmpty()) {
            // Named files, not folders: -x so an added file that a .gitignore now matches is deleted too.
            commands.add(argv(discard.added(), "clean", "-f", "-x", "--"));
        }
        return commands;
    }

    /** Runs a confirmed discard: one command per kind of path, then one refresh and reload. */
    private void runDiscard(
            Path root, Discard discard, String successMessage, List<String> affected, List<EditorBuffer> edited) {
        List<String[]> commands = discardCommands(discard);
        // Mixed sets run in order; one refresh/reload after every requested path was attempted.
        service.runWorktreeMutation(
                root,
                commands,
                running(
                        commands.get(0),
                        result -> finishDiscard(root, discard, result, successMessage, affected, edited)));
    }

    private void finishDiscard(
            Path root,
            Discard discard,
            ProcessRunner.Result result,
            String successMessage,
            List<String> affected,
            List<EditorBuffer> edited) {
        invalidatePendingWrites(root, affected);
        boolean ok = result != null && result.ok();
        if (ok) {
            host.setStatus(successMessage);
        } else {
            gitError(tr("status.git.opFailed"), result == null ? tr("status.git.opFailed") : result.message());
        }
        afterMutation(root);
        ops.reloadAllFromDiskSilently();
        if (ok && !edited.isEmpty()) {
            // The user was told their unsaved edits go too: a file put back is read again, a deleted one closed.
            List<String> deletedSpecs = discard.deleted();
            List<EditorBuffer> deleted = edited.stream()
                    .filter(b -> selectedBy(root, b, deletedSpecs))
                    .toList();
            List<EditorBuffer> reverted =
                    edited.stream().filter(b -> !deleted.contains(b)).toList();
            if (!reverted.isEmpty()) {
                ops.revertBuffersFromDisk(reverted);
            }
            if (!deleted.isEmpty()) {
                ops.closeDeletedBuffers(deleted);
            }
        }
    }

    private static String discardSuccessMessage(Discard discard) {
        List<String> deleted = discard.deleted();
        if (!discard.untracked().isEmpty()) {
            return deleted.size() == 1
                    ? tr("status.git.deleted", deleted.get(0))
                    : tr("status.git.deletedMany", deleted.size());
        }
        if (!discard.head().isEmpty()) {
            return tr("status.git.discarded", discard.head().get(0));
        }
        List<String> tracked = new ArrayList<>(discard.worktree());
        tracked.addAll(discard.added());
        return tracked.size() == 1
                ? tr("status.git.discarded", tracked.get(0))
                : tr("status.git.discardedMany", tracked.size());
    }

    /** The confirmation body: names the single file, else counts, and spells out a mixed set exactly. */
    private static String discardPrompt(Discard discard) {
        if (!discard.head().isEmpty()) {
            return tr("dialog.discard.toHead", discard.head().get(0)); // staged changes go too: say so
        }
        List<String> tracked = discard.worktree();
        List<String> deleted = discard.deleted();
        if (deleted.isEmpty()) {
            if (discard.keepsStaged()) { // a partly staged file: its staged part stays
                return tracked.size() == 1
                        ? tr("dialog.discard.unstagedOnly", tracked.get(0))
                        : tr("dialog.discard.unstagedOnlyMany", tracked.size());
            }
            return tracked.size() == 1
                    ? tr("dialog.discard.tracked", tracked.get(0))
                    : tr("dialog.discard.trackedMany", tracked.size());
        }
        if (tracked.isEmpty()) {
            if (discard.untracked().isEmpty()) { // only files added to Git and never committed
                return deleted.size() == 1
                        ? tr("dialog.discard.added", deleted.get(0))
                        : tr("dialog.discard.addedMany", deleted.size());
            }
            return deleted.size() == 1
                    ? tr("dialog.discard.untracked", deleted.get(0))
                    : tr("dialog.discard.untrackedMany", deleted.size());
        }
        return tr("dialog.discard.mixed", tracked.size(), deleted.size());
    }

    void gitCommit(String message) {
        gitCommit(message, committed -> {});
    }

    /**
     * Commits the index. {@code onDone} is told — on the FX thread, exactly once — whether a commit was made,
     * so the Commit window can re-enable itself and clear the message it submitted (and only that).
     */
    void gitCommit(String message, Consumer<Boolean> onDone) {
        commits.commit(new GitPanel.CommitRequest(message, false, false, false), onDone);
    }

    // --- bulk: unstage all, discard all --------------------------------------------------------------

    /**
     * {@code git.unstageAll} / the Staged group's Unstage All: every staged path goes back to unstaged, as
     * if each had been unstaged by hand (rename sources included; unmerged paths are never touched).
     */
    void gitUnstageAll() {
        Path root = repoRootOrReport();
        if (root == null) {
            return;
        }
        List<String> staged = lastStatus.files().stream()
                .filter(GitStatus.FileEntry::staged)
                .map(GitStatus.FileEntry::path)
                .toList();
        if (staged.isEmpty()) {
            host.setStatus(tr("status.git.nothingStaged"));
            return;
        }
        gitOpIn(
                root,
                tr("status.git.unstagedMany", staged.size()),
                argv(withRenameSources(lastStatus, staged), "reset", "-q", "HEAD", "--"));
    }

    /**
     * What Discard All runs: each changed path sorted by what its status needs ({@link GitDiscardPlan}) —
     * back to the index, back to HEAD, deleted, or (in the index but in no commit) out of the index and deleted.
     * {@code conflict} names the first unmerged path, which stops the whole thing: a conflict is resolved, not
     * discarded. Pure.
     */
    record DiscardAll(
            List<String> worktree,
            List<String> head,
            List<String> untracked,
            List<String> added,
            @Nullable String conflict) {
        int tracked() {
            return worktree.size() + head.size() + added.size();
        }

        static DiscardAll of(GitStatus status) {
            java.util.LinkedHashSet<String> worktree = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> head = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> untracked = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<String> added = new java.util.LinkedHashSet<>();
            String conflict = null;
            for (GitStatus.FileEntry entry : status == null ? List.<GitStatus.FileEntry>of() : status.files()) {
                GitDiscardPlan plan = GitDiscardPlan.of(status, entry.path());
                switch (plan.kind()) {
                    case CONFLICT -> conflict = conflict == null ? plan.subject() : conflict;
                    case UNTRACKED -> untracked.addAll(plan.specs());
                    case WORKTREE -> worktree.addAll(plan.specs());
                    case HEAD -> head.addAll(plan.specs());
                    case ADDED -> {}
                    case NOTHING -> {}
                }
                added.addAll(plan.added());
            }
            return new DiscardAll(
                    List.copyOf(worktree), List.copyOf(head), List.copyOf(untracked), List.copyOf(added), conflict);
        }
    }

    /**
     * {@code git.discardAll}: throws away every local change — staged and unstaged edits go back to the
     * last commit and untracked files are deleted. Confirmed once, danger-styled, with both counts in the
     * message — the untracked count is of files, a wholly untracked folder's included. Refused while a file is
     * unmerged; a nested repository is left alone, and the question says so.
     */
    void gitDiscardAll() {
        Path root = repoRootOrReport();
        if (root == null) {
            return;
        }
        DiscardAll all = DiscardAll.of(lastStatus);
        if (all.conflict() != null) {
            host.setStatus(tr("status.git.discardConflict", all.conflict()));
            return;
        }
        Discard discard = new Discard(all.worktree(), all.head(), all.untracked(), all.added(), false, List.of())
                .overwriting(root, lastStatus);
        if (discard.isEmpty()) {
            host.setStatus(tr("status.git.nothingToDiscard"));
            return;
        }
        List<String> untracked = discard.untracked();
        List<EditorBuffer> edited = editedBuffers(root, discard);
        // which rows are folders or nested repositories is looked at off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then(
                "git-untracked-look",
                () -> GitUntrackedDelete.look(root, untracked),
                looked -> discardAllLooked(root, discard, looked.or(GitUntrackedDelete.Look.NONE), edited));
    }

    private void discardAllLooked(Path root, Discard all, GitUntrackedDelete.Look look, List<EditorBuffer> anyEdited) {
        Discard discard = all.keeping(look.nested());
        if (discard.isEmpty()) {
            host.setStatus(tr("status.git.nestedRepoOnly", look.nested().get(0)));
            return;
        }
        List<EditorBuffer> edited = stillSelected(root, anyEdited, discard);
        if (look.folders() - look.nested().size() > 0) {
            // A wholly untracked folder is one row: ask Git which files deleting it removes, to count and copy them.
            service.untrackedFiles(root, discard.untracked(), files -> {
                if (files == null) {
                    host.setError(tr("status.git.opFailed"));
                    return;
                }
                confirmDiscardAll(root, discard, files, look.nested(), edited);
            });
            return;
        }
        confirmDiscardAll(root, discard, discard.untracked(), look.nested(), edited);
    }

    private void confirmDiscardAll(
            Path root, Discard discard, List<String> untrackedFiles, List<String> nested, List<EditorBuffer> edited) {
        int tracked = discard.tracked().size() + discard.added().size();
        List<String> deleted = new ArrayList<>(untrackedFiles);
        deleted.addAll(discard.added());
        String notes = GitUntrackedDelete.historyNote(deleted.size(), historyOn()) + discardNotes(edited, nested);
        if (!confirmDestructive(discardAllQuestion(tracked, untrackedFiles.size(), notes))) {
            return;
        }
        captureThenDiscard(
                root,
                discard,
                tr("status.git.discardedAll", tracked, untrackedFiles.size()),
                GitUntrackedDelete.captures(root, deleted),
                edited);
    }

    void checkoutBranch(String name) {
        if (repoRoot == null || name == null || name.isBlank()) {
            return;
        }
        if (rejectUnsafeRevision(name)) {
            return;
        }
        Path root = repoRoot;
        // The closing "--": a branch deleted elsewhere whose name is also a path would otherwise make git restore
        // that path from the index, discarding its uncommitted edits, and call it a success.
        String[] args = {"checkout", name, "--"};
        GitLeaveDetachedHead.thenMove(
                this,
                root,
                name,
                () -> aroundWorkingTreeMutation(
                        root,
                        done -> service.runWorktreeMutation(root, running(args, done), args),
                        r -> {
                            if (r.ok()) {
                                host.setStatus(tr("status.switchedBranch", name));
                            } else {
                                gitError(tr("status.git.switchFailed", name), r.message());
                            }
                        },
                        null));
    }

    /**
     * Refuses a branch/tag name that Git would parse as an option. Names come from repository data, and a
     * ref called {@code -f} turns {@code git checkout <name>} into a forced checkout that discards local
     * changes. Returns {@code true} (after reporting) when the name was refused.
     */
    boolean rejectUnsafeRevision(String name) {
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
            @Nullable Runnable afterReload) {
        aroundWorkingTreeMutation(repoRoot, operation, report, afterReload);
    }

    void aroundWorkingTreeMutation(
            @Nullable Path root,
            Consumer<Consumer<ProcessRunner.Result>> operation,
            Consumer<ProcessRunner.Result> report,
            @Nullable Runnable afterReload) {
        aroundWorkingTreeMutation(root, null, operation, report, afterReload);
    }

    /**
     * As above, superseding only the pending saves of {@code rewritable} (paths of {@code root}; null: every file of
     * it, empty: none) — the files the operation may write. A commit writes no file itself, only its hooks may (a
     * formatter run on the staged files): a save of a file it does not record, made while a slow hook ran, used to
     * be dropped.
     */
    void aroundWorkingTreeMutation(
            @Nullable Path root,
            @Nullable List<String> rewritable,
            Consumer<Consumer<ProcessRunner.Result>> operation,
            Consumer<ProcessRunner.Result> report,
            @Nullable Runnable afterReload) {
        Runnable invalidate = () -> {
            if (rewritable == null) {
                invalidatePendingWrites(root, List.of());
            } else if (!rewritable.isEmpty()) {
                invalidatePendingWrites(root, rewritable);
            }
        };
        invalidate.run();
        operation.accept(result -> {
            invalidate.run();
            report.accept(result);
            afterMutation(root);
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
    void mutateWorkingTree(
            @Nullable Path root, String successMessage, @Nullable Runnable afterCompletion, String... args) {
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
        ops.openCommitWindow();
        host.setStatus(status);
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
    private @Nullable GitOperation drivenOperation() {
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
        Path root = repoRoot;
        if (op == null || root == null) {
            return;
        }
        int conflicts = GitConflicts.unmerged(lastStatus).size();
        if (conflicts > 0) {
            host.setStatus(tr("status.git.cannotContinueConflicts", operationName(op.kind()), conflicts));
            ops.openCommitWindow();
            return;
        }
        saveUnsaved(root, List.of(), () -> {
            if (op.kind() == GitOperation.Kind.MERGE && ops.commitFromPanel()) {
                return;
            }
            runOperationStep(
                    root, op, op.continueArgs(), tr("status.git.operationContinued", operationName(op.kind())));
        });
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
        if (root == null) {
            return;
        }
        String name = operationName(op.kind());
        if (confirmDestructive(skipOperationQuestion(name))) {
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
        Path root = repoRoot;
        if (op == null || root == null) {
            return;
        }
        String name = operationName(op.kind());
        if (confirmDestructive(abortOperationQuestion(op.kind()))) {
            runOperationStep(root, op, op.abortArgs(), tr("status.git.operationAborted", name));
        }
    }

    /** One continue / skip / abort in {@code root}: pending saves superseded, buffers reloaded afterwards. */
    private void runOperationStep(Path root, GitOperation op, String[] args, String successMessage) {
        boolean abort = java.util.Arrays.equals(args, op.abortArgs());
        aroundWorkingTreeMutation(
                root,
                done -> service.runWorktreeMutation(root, running(args, done), args),
                result -> {
                    if (reportAutostashKept(result, op.kind(), abort)) {
                        return;
                    }
                    if (result.ok()) {
                        host.setStatus(successMessage);
                    } else if (!reportConflictStop(result, "status.git.stoppedOnConflicts")) {
                        gitError(tr("status.git.operationFailed", operationName(op.kind())), result.message());
                    }
                },
                null);
    }

    /**
     * Whether the command that ended a merge or rebase started with {@code --autostash} (its continue, the merge
     * commit, its abort — each applies the stashed changes back) said that applying them conflicted: git then
     * keeps them as a stash entry and exits 0, leaving conflict markers. Pure. Only git's own lines are read
     * (stderr): a commit's subject, on stdout, could hold the same words.
     */
    static boolean autostashKept(ProcessRunner.@Nullable Result result, GitOperation.Kind kind) {
        if (result == null || (kind != GitOperation.Kind.MERGE && kind != GitOperation.Kind.REBASE)) {
            return false;
        }
        return GitPullMode.autostash(new ProcessRunner.Result(result.exit(), "", result.err()))
                == GitPullMode.Autostash.KEPT_IN_STASH;
    }

    /**
     * Reports {@link #autostashKept} — the stashed changes are still in the stash and the conflicts are in the
     * Commit window — and returns true, or returns false having done nothing. The refresh that follows every
     * mutation lists the conflicts.
     */
    boolean reportAutostashKept(ProcessRunner.@Nullable Result result, GitOperation.Kind kind, boolean aborted) {
        if (!autostashKept(result, kind)) {
            return false;
        }
        String name = operationName(kind);
        conflictsNeedAttention(
                aborted ? tr("status.git.abortAutostashKept", name) : tr("status.git.opAutostashKept", name));
        return true;
    }

    /**
     * Whether {@code root}'s merge or rebase in progress holds the work tree's uncommitted changes aside: it was
     * started with {@code --autostash} ({@code MERGE_AUTOSTASH}, {@code rebase-merge/autostash},
     * {@code rebase-apply/autostash} in the work tree's git folder) and gives them back when it finishes. Reads the
     * disk.
     */
    static boolean holdsAutostash(Path root, GitOperation.Kind kind) {
        GitMetadataWatcher.GitDirs dirs = GitMetadataWatcher.dirsOf(root);
        if (dirs == null) {
            return false;
        }
        Path gitDir = dirs.gitDir();
        return switch (kind) {
            case MERGE -> Files.exists(gitDir.resolve("MERGE_AUTOSTASH"));
            case REBASE ->
                Files.exists(gitDir.resolve("rebase-merge").resolve("autostash"))
                        || Files.exists(gitDir.resolve("rebase-apply").resolve("autostash"));
            default -> false;
        };
    }

    /** Tells the Commit window whether the operation in progress keeps uncommitted changes in an autostash. */
    private void lookForAutostash(Path root, GitOperation op) {
        GitOperation.Kind kind = op.kind();
        if (!op.inProgress()
                || (kind != GitOperation.Kind.MERGE && kind != GitOperation.Kind.REBASE)
                || !com.editora.vfs.Vfs.isLocal(root)) {
            ops.setGitOperationAutostash(false);
            return;
        }
        // two stats of the git folder, off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then("git-autostash-look", () -> holdsAutostash(root, kind), held -> {
            if (root.equals(repoRoot) && operation.kind() == kind) {
                ops.setGitOperationAutostash(held.or(false));
            }
        });
    }

    // --- conflicts -----------------------------------------------------------------------------------

    /**
     * Resolves unmerged paths by taking one whole side ({@code checkout --ours/--theirs} then {@code add}, or
     * {@code rm} when that side deleted the file — {@link GitConflicts#acceptSide}). The other side's changes
     * to those files are dropped, so it is confirmed.
     */
    void acceptConflictSide(List<String> paths, boolean ours) {
        Path root = paths.isEmpty() ? null : repoRootOrReport();
        if (root == null) {
            return;
        }
        List<GitStatus.FileEntry> entries = GitConflicts.unmerged(lastStatus).stream()
                .filter(entry -> paths.contains(entry.path()))
                .toList();
        List<String[]> commands = GitConflicts.acceptSide(entries, ours);
        if (commands.isEmpty()) {
            return;
        }
        String side = tr(acceptSideKey(operation.kind(), ours));
        if (!confirmDestructive(acceptSideQuestion(
                side, entries.stream().map(GitStatus.FileEntry::path).toList()))) {
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
            afterMutation(root);
            ops.reloadAllFromDiskSilently();
        }));
    }

    /**
     * The label of "take this whole side" for a conflict of the operation in progress. Git's "ours" is the
     * side HEAD was on when the conflict arose: the checked-out branch in a merge, cherry-pick or revert —
     * but during a rebase HEAD is the branch being rebased <em>onto</em>, so "ours" is the upstream's version
     * and "theirs" the user's own commit, the opposite of what the words suggest. The labels therefore say
     * what each side is; git's term stays in the tooltip. Pure.
     */
    static String acceptSideKey(GitOperation.Kind kind, boolean ours) {
        return switch (kind) {
            case REBASE -> ours ? "gitpanel.menu.acceptOurs.rebase" : "gitpanel.menu.acceptTheirs.rebase";
            case CHERRY_PICK, REVERT -> ours ? "gitpanel.menu.acceptOurs" : "gitpanel.menu.acceptTheirs.commit";
            default -> ours ? "gitpanel.menu.acceptOurs" : "gitpanel.menu.acceptTheirs";
        };
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
                || GitConflicts.unmerged(lastStatus).stream().noneMatch(e -> relative.equals(e.path()))
                || com.editora.diff.ConflictParser.hasConflictMarkers(buffer.getContent())) {
            return;
        }
        ops.saveBeforeGitThen(buffer, saved -> {
            if (!saved) {
                host.setError(tr("status.git.unsavedFailed", buffer.getTitle()));
                return;
            }
            gitOpIn(root, tr("status.git.conflictResolved", relative), argv(List.of(relative), "add", "--"));
        });
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
        Path root = repoRootOrReport(); // the repository the prompt was opened for, not whichever is active on accept
        if (root == null) {
            return;
        }
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
                        afterMutation(root);
                    }),
                    args);
        });
    }

    void gitSync(String label, String... args) {
        Path root = repoRootOrReport();
        if (root != null) {
            syncIn(root, label, null, args);
        }
    }

    /**
     * @param onFailure given a failed result first; true means it dealt with it (a diverged pull offering the
     *     way forward) and nothing more is reported
     */
    private void syncIn(
            Path root,
            String label,
            java.util.function.@Nullable Predicate<ProcessRunner.Result> onFailure,
            String... args) {
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
            afterMutation(root);
            GitPullMode.Autostash autostash =
                    changesWorkingTree ? GitPullMode.autostash(r) : GitPullMode.Autostash.NONE;
            if (autostash == GitPullMode.Autostash.KEPT_IN_STASH) {
                // The pull itself may well have succeeded; what needs attention is the user's own changes:
                // applying them back conflicted, and git kept them as a stash entry.
                conflictsNeedAttention(tr("status.git.pullAutostashKept"));
            } else if (r.ok()) {
                host.setStatus(
                        autostash == GitPullMode.Autostash.APPLIED
                                ? tr("status.git.pullAutostashApplied")
                                : tr("status.gitDone", label));
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
        Path root = repoRootOrReport();
        if (root != null) {
            pullIn(root, pullMode(), true);
        }
    }

    /** Pulls in {@code mode} whatever the setting says ({@code git.pullRebase}, {@code git.pullMerge}). */
    void gitPull(GitPullMode mode) {
        Path root = repoRootOrReport();
        if (root != null) {
            pullIn(root, mode, false);
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

    private @Nullable GitPullMode askDivergedPull() {
        ButtonType rebase = new ButtonType(tr("dialog.pullDiverged.rebase"), ButtonBar.ButtonData.OTHER);
        ButtonType merge = new ButtonType(tr("dialog.pullDiverged.merge"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = Dialogs.cancel();
        Alert ask = Dialogs.styled(
                new Alert(Alert.AlertType.CONFIRMATION, tr("dialog.pullDiverged.message"), rebase, merge, cancel));
        ask.initOwner(host.window());
        ask.setTitle(tr("dialog.pullDiverged.title"));
        ask.setHeaderText(null);
        ButtonType chosen = Dialogs.showAndWait(ask).orElse(cancel);
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
        Path root = repoRootOrReport();
        if (root == null) {
            return;
        }
        String[] args = GitService.pushArgs(branchName, upstream);
        service.runNetwork(
                root,
                running(args, r -> {
                    if (r.ok()) {
                        ops.reloadAllFromDiskSilently();
                    }
                    afterMutation(root);
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
    private @Nullable Path activeGitFile() {
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
    GitPathScope scopeOf(@Nullable Path file) {
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
    private void inRepositoryOf(@Nullable Path file, java.util.function.BiConsumer<Path, GitStatus> action) {
        // The repository the path is in is known only once its folders have been looked at: off the FX thread,
        // for a path no menu has asked about yet (PERF-01, Pauta 6.2.0). The active repository as it was asked.
        Path root = repoRoot;
        GitStatus status = lastStatus;
        GitPathScope.whenKnown(file, root, scope -> inRepositoryOf(file, scope, root, status, action));
    }

    private void inRepositoryOf(
            @Nullable Path file,
            GitPathScope scope,
            @Nullable Path root,
            GitStatus status,
            java.util.function.BiConsumer<Path, GitStatus> action) {
        if (scope == GitPathScope.ACTIVE && root != null) { // ACTIVE needs a root, OTHER a file
            action.accept(root, status);
        } else if (scope == GitPathScope.OTHER && file != null) {
            service.status(file, state -> {
                Path other = state.root();
                if (state.isRepo() && other != null) {
                    action.accept(other, state.status());
                } else {
                    host.setStatus(tr(service.gitAvailable(file) ? "status.notARepo" : "status.gitNotInstalled"));
                }
            });
        } else {
            host.setStatus(tr(service.gitAvailable(file) ? "status.notARepo" : "status.gitNotInstalled"));
        }
    }

    /**
     * Runs an action written against {@link #repoRoot()} (compare with HEAD / a branch / a tag / a revision)
     * for a path that may belong to another repository: {@link #repoRoot()} answers that repository for the
     * duration of {@code action}, which must read it before returning — as those actions do, because the root
     * can change under any callback they register.
     */
    void withRepositoryOf(@Nullable Path file, Runnable action) {
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
    void activatingRepositoryOf(@Nullable Path file, Runnable action) {
        ifEnabled(() -> {
            // A path the tree offers history for is a file; a folder here would be opened as one. (On a
            // remote host the check would be an SFTP round trip, so it is left to the tree.)
            if (file == null || GitPathScope.of(file, repoRoot) != GitPathScope.OTHER) {
                action.run();
                return;
            }
            if (com.editora.vfs.Vfs.isLocal(file)) {
                // a folder? looked at off the FX thread (PERF-01, Pauta 6.2.0)
                com.editora.perf.OffFx.then("git-path-look", () -> Files.isDirectory(file), folder -> {
                    if (folder.or(false)) {
                        action.run();
                    } else {
                        activateRepositoryOf(file, action);
                    }
                });
                return;
            }
            activateRepositoryOf(file, action);
        });
    }

    private void activateRepositoryOf(Path file, Runnable action) {
        ops.openPath(file);
        // Queued behind the refresh the tab switch started, so normally that has been applied by now.
        service.status(file, state -> {
            if (state.isRepo() && !java.util.Objects.equals(state.root(), repoRoot)) {
                applyState(state);
            }
            action.run();
        });
    }

    /** The literal pathspec for {@code file} in {@code root} ({@code .} for the root itself), or null. */
    private static @Nullable String pathspec(Path root, @Nullable Path file) {
        // repoRelative symlink-resolves both sides (the root comes from git as a real path, the file is
        // as-opened) and forward-slash-normalizes the result (git treats "\" as an escape on Windows).
        String relative = file == null ? null : GitService.repoRelative(root, file);
        return relative != null && relative.isEmpty() ? "." : relative;
    }

    /** {@code git add -- <path>} for a file or folder (the Project-tree "Stage" action). */
    void gitStagePath(@Nullable Path file) {
        inRepositoryOf(file, (root, status) -> {
            String spec = pathspec(root, file);
            if (spec != null) {
                stageIn(root, status, List.of(spec));
            }
        });
    }

    /** {@code git reset -q HEAD -- <path>} for a file or folder (the Project-tree "Unstage" action). */
    void gitUnstagePath(@Nullable Path file) {
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
    void gitRevertPath(@Nullable Path file) {
        inRepositoryOf(file, (root, status) -> {
            GitDiscardPlan plan = GitDiscardPlan.of(status, pathspec(root, file));
            switch (plan.kind()) {
                case NOTHING ->
                    host.setStatus(tr("status.git.nothingToDiscard", file == null ? null : file.getFileName()));
                case CONFLICT -> host.setStatus(tr("status.git.discardConflict", plan.subject()));
                case UNTRACKED -> discard(root, status, new Discard(List.of(), List.of(), plan.specs()));
                case WORKTREE ->
                    discard(
                            root,
                            status,
                            new Discard(plan.specs(), List.of(), List.of(), plan.added(), false, List.of()));
                case HEAD ->
                    discard(
                            root,
                            status,
                            new Discard(List.of(), plan.specs(), List.of(), plan.added(), false, List.of()));
                case ADDED ->
                    discard(root, status, new Discard(List.of(), List.of(), List.of(), plan.added(), false, List.of()));
            }
        });
    }

    /** The Git working-tree status of {@code file} (null = clean / not tracked-as-changed), for menu
     *  enable/disable — mirrors the map the Project tree colors from. */
    com.editora.git.@Nullable GitFileStatus statusFor(@Nullable Path file) {
        return file == null ? null : lastStatusByPath.get(file.toAbsolutePath().normalize());
    }

    /**
     * Adds {@code file} (a directory gets a trailing {@code /}) to the repo-root {@code .gitignore}, creating
     * it if absent — the Project-tree "Add to .gitignore" action. A no-op when the entry is already present.
     * Writes the small file off the FX thread, then refreshes so the now-ignored file drops from the tree color.
     */
    void addToGitignore(@Nullable Path file) {
        inRepositoryOf(file, (root, status) -> addToGitignore(root, file));
    }

    private void addToGitignore(Path root, @Nullable Path file) {
        String rel = file == null ? null : GitService.repoRelative(root, file);
        if (file == null || rel == null || rel.isEmpty()) {
            return;
        }
        // The file is looked at, and .gitignore read and written, off the FX thread (PERF-01, Pauta 6.2.0).
        DebugPlanning.offFx(
                "git-ignore-write",
                () -> {
                    String entry = com.editora.git.GitIgnore.entryFor(rel, java.nio.file.Files.isDirectory(file));
                    Path ignore = root.resolve(".gitignore");
                    String existing = java.nio.file.Files.exists(ignore) ? java.nio.file.Files.readString(ignore) : "";
                    String updated = com.editora.git.GitIgnore.withEntry(existing, entry);
                    if (updated != null) {
                        java.nio.file.Files.writeString(ignore, updated);
                    }
                    return new String[] {entry, updated == null ? "" : "added"};
                },
                a -> {
                    String[] done = a.value();
                    if (a.failure() != null || done == null) {
                        gitError(tr("status.git.gitignoreFailed"), a.why());
                    } else if (done[1].isEmpty()) {
                        host.setStatus(tr("status.git.alreadyIgnored", done[0]));
                    } else {
                        host.setStatus(tr("status.git.addedToGitignore", done[0]));
                        afterMutation(root);
                    }
                });
    }

    // --- stash: see GitStashCoordinator -----------------------------------------------------------

    void gitStash() {
        stashes.promptStash();
    }

    void gitStashPop() {
        stashes.popLatest();
    }

    void gitUnstash() {
        stashes.pickToApply();
    }

    void gitStashDrop() {
        stashes.pickToDrop();
    }

    void dropStash(Path root, com.editora.git.StashParser.StashEntry entry) {
        stashes.drop(root, entry);
    }

    /** Supersedes pending saves for open files selected by the working-tree mutation. Empty means the repo. */
    private void invalidatePendingWrites(List<String> pathspecs) {
        invalidatePendingWrites(repoRoot, pathspecs);
    }

    /** As above, for the repository {@code root} captured when the action began. */
    private void invalidatePendingWrites(@Nullable Path root, List<String> pathspecs) {
        if (root == null) {
            return;
        }
        host.forEachBuffer(buffer -> {
            Path file = buffer.getPath();
            if (file == null || file.getFileSystem() != root.getFileSystem()) {
                return; // never in this repository, and paths of two filesystems cannot be compared
            }
            String relative = GitService.repoRelative(root, file);
            if (relative != null && (pathspecs.isEmpty() || pathspecs.stream().anyMatch(p -> selects(p, relative)))) {
                ops.invalidatePendingWrite(file);
            }
        });
        ops.invalidatePendingWritesInOtherWindows(root, pathspecs);
    }

    static boolean selects(String pathspec, @Nullable String relative) {
        if (".".equals(pathspec) || pathspec.equals(relative)) {
            return true;
        }
        String directory = pathspec.endsWith("/") ? pathspec : pathspec + "/";
        return relative != null && relative.startsWith(directory);
    }

    /** Stops the running clone, fetch, pull or push (the Git console's Stop button and {@code git.cancel}). */
    void cancelNetworkCommand() {
        host.setStatus(tr(service.cancelRunningCommand() ? "status.git.cancelling" : "status.git.nothingToCancel"));
    }

    /**
     * Shows a Git command's (often multi-line) error output in a readable, scrollable dialog rather than
     * cramming it into the one-line status bar. The status bar gets a short summary.
     */
    void gitError(String summary, @Nullable String detail) {
        if (ProcessRunner.CANCELLED.equals(detail)) {
            host.setStatus(tr("status.git.cancelled")); // the user stopped it: nothing to explain in a dialog
            return;
        }
        host.setStatus(summary);
        if (!host.window().isShowing()) {
            // git finished after its window closed: a dialog now would be an orphan nobody can see to close, so another
            // open window says it (HTML review 3, A204); with none, the Debug Log does
            String said = closedWindowError(summary, detail, windowName());
            if (!ops.errorInAnotherWindow(said)) {
                LOG.warning(() -> "git failed after its window closed: " + said);
            }
            return;
        }
        // A sign-in failure gets its next step appended: git's own line ("terminal prompts disabled") has none.
        String body = detail == null || detail.isBlank() ? summary : GitAuthFailure.withGuidance(detail.strip());
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.ERROR));
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.git.title"));
        alert.setHeaderText(summary);
        TextArea area = new TextArea(body);
        area.setEditable(false);
        area.setWrapText(true);
        area.setPrefColumnCount(52);
        area.setPrefRowCount(Math.min(14, (int) body.lines().count() + 1));
        area.getStyleClass().add("git-error-text");
        area.setAccessibleText(alert.getHeaderText()); // the details of what the header says failed
        alert.getDialogPane().setContent(area);
        Dialogs.showAndWait(alert);
    }

    /** What a git failure from the closed window {@code window} says in another window: {@code detail} on one line. */
    static String closedWindowError(String summary, @Nullable String detail, String window) {
        String said = detail == null ? "" : detail.strip().replaceAll("\\s*\\R\\s*", " · ");
        if (said.length() > CLOSED_WINDOW_DETAIL_MAX) {
            said = said.substring(0, CLOSED_WINDOW_DETAIL_MAX - 1) + "…";
        }
        return said.isEmpty()
                ? tr("status.git.closedWindowError", summary, window)
                : tr("status.git.closedWindowErrorDetail", summary, window, said);
    }

    /** How much of git's output a closed window's failure carries into another window's status line. */
    static final int CLOSED_WINDOW_DETAIL_MAX = 300;

    /** This window, as another window names it: its project's folder, else its title. */
    private String windowName() {
        Path root = ops.projectRoot();
        Path name = root == null ? null : root.getFileName();
        if (name != null) {
            return name.toString();
        }
        return host.window() instanceof javafx.stage.Stage stage && stage.getTitle() != null ? stage.getTitle() : "";
    }

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(GitCoordinator.class.getName());

    // --- clone (URL + destination form → git clone → open a file so Git lights up) ---

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
                    if (suggested != null && com.editora.vfs.Vfs.isRemote(suggested)) {
                        // A folder on the window's host: named as the host names it, ~ its home there, and
                        // checked over SFTP, off the FX thread.
                        Thread.ofVirtual().name("git-init-folder").start(() -> {
                            Path dir;
                            try {
                                Path home = com.editora.process.ExecTargets.forPath(suggested)
                                        .homeDir();
                                dir = com.editora.config.PathKeys.resolveUserInputOn(
                                        text.strip(), suggested, home == null ? null : home.toString());
                                dir = dir != null && java.nio.file.Files.isDirectory(dir) ? dir : null;
                            } catch (java.io.IOException | RuntimeException e) {
                                dir = null;
                            }
                            Path found = dir;
                            javafx.application.Platform.runLater(() -> initIn(found, text.strip()));
                        });
                        return;
                    }
                    Path dir = com.editora.config.PathKeys.resolveUserInput(
                            text.strip(), suggested, System.getProperty("user.home"));
                    // a folder? looked at off the FX thread (PERF-01, Pauta 6.2.0)
                    com.editora.perf.OffFx.then(
                            "git-init-folder",
                            () -> dir != null && java.nio.file.Files.isDirectory(dir),
                            folder -> initIn(folder.or(false) ? dir : null, text.strip()));
                });
    }

    /** Runs {@code git init} in {@code dir}, a folder; null when what was typed ({@code typed}) is not one. */
    private void initIn(@Nullable Path dir, String typed) {
        if (dir == null) {
            host.setError(tr("status.gitInit.notAFolder", typed));
            return;
        }
        host.setStatus(tr("status.gitInit.initializing", dir.toString()));
        // The already-a-repo check lives in the service: answering it runs git rev-parse, which must not
        // happen on the FX thread.
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
            GitPathScope.learnRemote(ops.projectRoot(), true); // a new repository in a remote project
            afterMutation(); // status bar, Commit window, gutter bars all light up
        });
    }

    /**
     * Clones a remote repository via one form asking for both the <em>URL</em> and the destination directory
     * (a Browse button + auto-fill of {@code <home>/<repo-name>} as you type the URL, until you edit it
     * yourself). Clones into that folder, then opens a file from it (its README, if any) so Git lights up.
     * Clone and Projects are independent — no project is created.
     */
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
        // The options: a branch to check out, a shallow depth, and the submodules. All optional.
        TextField branchField = new TextField();
        branchField.setPromptText(tr("dialog.clone.branchPrompt"));
        TextInputKeymap.install(branchField, keymap);
        TextField depthField = new TextField();
        depthField.setPromptText(tr("dialog.clone.depthPrompt"));
        depthField.setPrefColumnCount(8);
        TextInputKeymap.install(depthField, keymap);
        javafx.scene.control.CheckBox submodules = new javafx.scene.control.CheckBox(tr("dialog.clone.submodules"));
        urlField.setId("clone-url");
        dirField.setId("clone-directory");
        branchField.setId("clone-branch");
        depthField.setId("clone-depth");
        submodules.setId("clone-submodules");

        // In a window on a remote folder the repository is cloned on that host, by its git, next to the open
        // project: the folder is a path there, and the local folder dialog has nothing to offer.
        Path project = ops.projectRoot();
        Path remoteBase = project != null && com.editora.vfs.Vfs.isRemote(project)
                ? (project.getParent() != null ? project.getParent() : project)
                : null;
        String defaultParent = remoteBase != null ? remoteBase.toString() : System.getProperty("user.home", "");
        Label onHost = new Label(
                remoteBase == null
                        ? ""
                        : tr(
                                "dialog.clone.onHost",
                                com.editora.process.ExecTargets.forPath(remoteBase)
                                        .id()));
        onHost.getStyleClass().add("dim");
        onHost.setId("clone-on-host");
        browse.setVisible(remoteBase == null);
        browse.setManaged(remoteBase == null);
        boolean[] dirEdited = {false};
        boolean[] autoFilling = {false};
        urlField.textProperty().addListener((o, a, b) -> {
            if (!dirEdited[0]) {
                String name = repoNameFromUrl(b);
                autoFilling[0] = true;
                dirField.setText(
                        remoteBase != null
                                ? suggestedCloneDir(remoteBase, name)
                                : suggestedCloneDir(defaultParent, name));
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
        UiZoom.gaps(grid, 8, 8);
        grid.add(ControlNames.label(tr("dialog.clone.url"), urlField), 0, 0);
        grid.add(urlField, 1, 0, 2, 1);
        grid.add(ControlNames.label(tr("dialog.clone.directory"), dirField), 0, 1);
        grid.add(dirField, 1, 1);
        grid.add(browse, 2, 1);
        grid.add(ControlNames.label(tr("dialog.clone.branch"), branchField), 0, 2);
        grid.add(branchField, 1, 2, 2, 1);
        grid.add(ControlNames.label(tr("dialog.clone.depth"), depthField), 0, 3);
        grid.add(depthField, 1, 3);
        grid.add(submodules, 1, 4, 2, 1);
        if (remoteBase != null) {
            grid.add(onHost, 1, 5, 2, 1);
        }
        GridPane.setHgrow(urlField, Priority.ALWAYS);
        GridPane.setHgrow(dirField, Priority.ALWAYS);

        // Enable Clone only when both fields are filled (mirrors the old dialog's validation) and the
        // depth, when there is one, is a number of commits.
        BooleanProperty valid = new SimpleBooleanProperty(false);
        Runnable revalidate = () -> valid.set(!urlField.getText().isBlank()
                && !dirField.getText().isBlank()
                && cloneDepth(depthField.getText()) >= 0);
        urlField.textProperty().addListener((o, a, b) -> revalidate.run());
        dirField.textProperty().addListener((o, a, b) -> revalidate.run());
        depthField.textProperty().addListener((o, a, b) -> revalidate.run());
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
                    String typed = dirField.getText();
                    GitService.CloneOptions options = new GitService.CloneOptions(
                            Math.max(0, cloneDepth(depthField.getText())),
                            branchField.getText(),
                            submodules.isSelected());
                    if (remoteBase == null) {
                        // Resolved like the folder typed for git.init (~ and relative paths), validated, and
                        // its missing parent folders created — git creates the target, not what is above it. Off
                        // the FX thread (PERF-01, Pauta 6.2.0).
                        host.setStatus(tr("status.cloning", url));
                        com.editora.perf.OffFx.then(
                                "clone-destination",
                                () -> CloneDestination.prepare(
                                        typed, defaultParent.isBlank() ? null : Path.of(defaultParent), defaultParent),
                                prepared -> {
                                    CloneDestination target = prepared.value();
                                    startClone(
                                            url,
                                            target != null
                                                    ? target
                                                    : new CloneDestination(
                                                            null, "status.clone.invalidDest", typed.strip()),
                                            options);
                                });
                        return;
                    }
                    // On the host the same checks are SFTP round trips, and ~ is the home folder there.
                    host.setStatus(tr("status.cloning", url));
                    Thread.ofVirtual().name("clone-destination").start(() -> {
                        CloneDestination target;
                        try {
                            Path home = com.editora.process.ExecTargets.forPath(remoteBase)
                                    .homeDir();
                            target = CloneDestination.prepare(typed, remoteBase, home == null ? null : home.toString());
                        } catch (java.io.IOException | RuntimeException e) {
                            target = new CloneDestination(null, "status.clone.invalidDest", typed.strip());
                        }
                        CloneDestination prepared = target;
                        javafx.application.Platform.runLater(() -> startClone(url, prepared, options));
                    });
                },
                null,
                false);
    }

    private void startClone(String url, CloneDestination target, GitService.CloneOptions options) {
        Path destination = target.path();
        String errorKey = target.errorKey();
        if (destination == null) { // not ok(): errorKey says why
            if (errorKey != null) {
                host.setError(tr(errorKey, target.shown()));
            }
            return;
        }
        host.setStatus(tr("status.cloning", url));
        service.clone(url, destination, options, running(new String[] {"clone"}, r -> {
            if (r.ok()) {
                host.setStatus(tr("status.clonedInto", destination));
                GitPathScope.learnRemote(ops.projectRoot(), true); // a clone into a remote project's folder
                openClonedEntry(destination);
            } else {
                gitError(tr("status.git.cloneFailed"), r.message());
            }
        }));
    }

    /**
     * The clone form's depth field: 0 when it is blank (all history), the number of commits when it holds a
     * positive whole number, and -1 for anything else — which keeps the Clone button disabled. Pure.
     */
    static int cloneDepth(String text) {
        String typed = text == null ? "" : text.strip();
        if (typed.isEmpty()) {
            return 0;
        }
        try {
            int depth = Integer.parseInt(typed);
            return depth > 0 ? depth : -1;
        } catch (NumberFormatException notANumber) {
            return -1;
        }
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

    /** {@link #suggestedCloneDir(String, String)} on a folder of a remote host, named as the host names it. */
    static String suggestedCloneDir(Path parent, String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        try {
            return parent.resolve(name).toString();
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
        // Each look is a stat (an SFTP round trip on a host): made off the FX thread (PERF-01, Pauta 6.2.0).
        com.editora.perf.OffFx.then(
                "clone-entry",
                () -> java.util.Optional.ofNullable(clonedEntry(dir)),
                entry -> openClonedEntry(
                        dir, entry.or(java.util.Optional.<Path>empty()).orElse(null)));
    }

    private void openClonedEntry(Path dir, @Nullable Path entry) {
        if (entry != null) {
            ops.openPath(entry);
        } else {
            host.setStatus(tr("status.clonedOpen", dir));
        }
    }

    /** The clone's README, the file it is opened at, or null. */
    private static @Nullable Path clonedEntry(Path dir) {
        for (String candidate : new String[] {"README.md", "README.markdown", "README.rst", "README.txt", "README"}) {
            Path file = dir.resolve(candidate);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
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
