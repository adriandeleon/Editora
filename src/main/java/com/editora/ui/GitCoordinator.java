package com.editora.ui;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import com.editora.git.GitFormat;
import com.editora.git.GitService;
import com.editora.git.GitStatus;
import com.editora.git.RelativeTime;
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

        /** Pushes the per-file Git working-tree status to the Project tree for IntelliJ-style file coloring. */
        void setProjectGitStatus(java.util.Map<java.nio.file.Path, com.editora.git.GitFileStatus> byPath);

        /** Re-diffs any open diff tabs (a mutation moved HEAD/index/working). */
        void refreshOpenDiffs();

        /** The active project's root folder, or {@code null} when no project is open. */
        Path projectRoot();

        /** Re-checks the active file's on-disk stamp + reloads it if a git command changed it under us. */
        void checkExternalChanges();

        /** After a branch switch / pull, silently reload any open buffer whose file changed on disk. */
        void reloadAllFromDiskSilently();

        /** Clears the Commit tool window's message box (after a successful commit). */
        void clearCommitMessage();

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
        void openCommitFileDiff(String hash, String repoRel);
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final GitService service = new GitService();

    private Path repoRoot;
    /** The last computed per-file status (absolute normalized path → status); used by the Project-tree
     *  actions to tell whether a file is untracked (so Revert = clean vs. checkout). */
    private java.util.Map<Path, com.editora.git.GitFileStatus> lastStatusByPath = java.util.Map.of();

    private String branchName = "";
    private String upstream = "";
    private boolean supportApplied;

    GitCoordinator(CoordinatorHost host, WindowOps ops) {
        this.host = host;
        this.ops = ops;
    }

    /** The off-thread Git CLI facade (operations in {@code MainController} run their commands through it). */
    GitService service() {
        return service;
    }

    /** The active repo root, or {@code null} when the current context isn't inside a repo. */
    Path repoRoot() {
        return repoRoot;
    }

    String branchName() {
        return branchName;
    }

    String upstream() {
        return upstream;
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
        boolean on = isEnabled();
        ops.setStatusBarGitEnabled(on);
        if (!on) {
            ops.setCommitWindowAvailable(false);
            ops.setGitLogWindowAvailable(false);
            repoRoot = null;
            branchName = "";
            upstream = "";
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
            applyState(GitService.RepoState.NONE);
            return;
        }
        // Git shells out to a local process — a remote (SFTP) context has no local repo.
        if (!Vfs.isLocal(context)) {
            applyState(GitService.RepoState.NONE);
            return;
        }
        // Only diff a real, non-huge file (huge files disable the gutter anyway).
        Path diffFile = (file != null && !b.isLargeFile()) ? file : null;
        service.refresh(context, diffFile, this::applyState);
    }

    /** Applies a completed Git refresh to the status bar, tool window, and active buffer's gutter. */
    void applyState(GitService.RepoState state) {
        repoRoot = state.root();
        EditorBuffer b = host.activeBuffer();
        // The Commit / Log tool windows are only available inside a Git repo (transient, doesn't touch the
        // user's show/hide preference).
        ops.setCommitWindowAvailable(state.isRepo());
        ops.setGitLogWindowAvailable(state.isRepo());
        if (!state.isRepo()) {
            branchName = "";
            upstream = "";
            ops.setStatusBarBranch(null, 0, 0);
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
        ops.setStatusBarBranch(status.branch(), status.ahead(), status.behind());
        ops.setGitPanelStatus(status);
        lastStatusByPath = com.editora.git.GitFileStatus.byPath(status, state.root());
        ops.setProjectGitStatus(lastStatusByPath); // color the tree
        if (b != null && b.getPath() != null) {
            // An empty map still marks the buffer as tracked (reserves the slot); hunk text feeds the
            // change-bar hover tooltip.
            b.setChangeBars(GitChangeBars.cssClassesByLine(state.changes()), state.hunks());
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
        Path root = repoRoot;
        if (root == null) {
            return;
        }
        EditorBuffer active = host.activeBuffer();
        host.forEachBuffer(buf -> {
            // The active buffer is handled by refresh(); skip non-file/huge buffers + files outside this repo.
            if (!GitChangeBars.shouldRediff(buf.getPath(), root, buf == active, buf.isLargeFile())) {
                return;
            }
            service.diff(
                    root,
                    buf.getPath().toAbsolutePath(),
                    diff -> buf.setChangeBars(GitChangeBars.cssClassesByLine(diff.changes()), diff.hunks()));
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
            ops.openCommitFileDiff(hash, rel);
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
        service.blame(repoRoot, file, lines -> {
            if (host.activeBuffer() != b) {
                return; // the user switched tabs while blame ran
            }
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
        service.run(
                repoRoot,
                r -> {
                    if (r.ok()) {
                        host.setStatus(successMessage);
                    } else {
                        gitError("Git command failed", r.message());
                    }
                    afterMutation();
                },
                args);
    }

    /** Confirms then discards a file's changes (or deletes an untracked file) — destructive. */
    void discardChanges(String path, boolean untracked) {
        if (repoRoot == null) {
            return;
        }
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                untracked ? tr("dialog.discard.untracked", path) : tr("dialog.discard.tracked", path),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.discard.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        if (untracked) {
            gitOp("Deleted " + path, "clean", "-f", "--", path);
        } else {
            gitOp("Discarded changes to " + path, "checkout", "--", path);
        }
        // The on-disk file changed under any open buffer for it — re-check so it reloads if needed.
        Platform.runLater(ops::checkExternalChanges);
    }

    void gitCommit(String message) {
        if (repoRoot == null) {
            return;
        }
        service.run(
                repoRoot,
                r -> {
                    if (r.ok()) {
                        ops.clearCommitMessage();
                        host.setStatus(tr("status.committed"));
                    } else {
                        gitError("Commit failed", r.message());
                    }
                    afterMutation();
                },
                "commit",
                "-m",
                message);
    }

    void checkoutBranch(String name) {
        if (repoRoot == null || name == null || name.isBlank()) {
            return;
        }
        service.run(
                repoRoot,
                r -> {
                    if (r.ok()) {
                        host.setStatus(tr("status.switchedBranch", name));
                    } else {
                        gitError("Couldn't switch to " + name, r.message());
                    }
                    afterMutation();
                    ops.reloadAllFromDiskSilently();
                },
                "checkout",
                name);
    }

    /** Checks out a remote branch (e.g. {@code origin/foo}), creating a local tracking branch. */
    void checkoutRemoteBranch(String remote) {
        if (repoRoot == null || remote == null || remote.isBlank()) {
            return;
        }
        service.run(
                repoRoot,
                r -> {
                    if (r.ok()) {
                        host.setStatus(tr("status.checkedOut", remote));
                    } else {
                        gitError("Couldn't check out " + remote, r.message());
                    }
                    afterMutation();
                    ops.reloadAllFromDiskSilently();
                },
                "checkout",
                "--track",
                remote);
    }

    void newBranch() {
        if (reportIfNoRepo()) {
            return;
        }
        host.promptText(tr("dialog.newBranch.title"), tr("dialog.newBranch.content"), "", input -> {
            String name = input.strip();
            if (name.isEmpty()) {
                return;
            }
            service.run(
                    repoRoot,
                    r -> {
                        if (r.ok()) {
                            host.setStatus(tr("status.createdBranch", name));
                        } else {
                            gitError("Couldn't create branch " + name, r.message());
                        }
                        afterMutation();
                    },
                    "checkout",
                    "-b",
                    name);
        });
    }

    void gitSync(String label, String... args) {
        if (reportIfNoRepo()) {
            return;
        }
        host.setStatus(tr("status.gitRunning", label));
        service.runNetwork(
                repoRoot,
                r -> {
                    if (r.ok()) {
                        host.setStatus(tr("status.gitDone", label));
                        ops.reloadAllFromDiskSilently();
                    } else {
                        gitError(label + " failed", r.message());
                    }
                    afterMutation();
                },
                args);
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
        gitSync(tr("gitlabel.push"), GitService.pushArgs(branchName, upstream));
    }

    /** Opens the Git tool window and focuses the commit message box. */
    void gitCommitFocus() {
        ops.openCommitWindow();
        ops.focusCommitMessage();
    }

    /** Stages the active file (palette {@code git.stageFile}). */
    void gitStageActiveFile() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || repoRoot == null) {
            host.setStatus(tr("status.noGitFile"));
            return;
        }
        gitOp(
                "Staged " + file.getFileName(),
                "add",
                "--",
                repoRoot.relativize(file.toAbsolutePath()).toString());
    }

    /** Unstages the active file (palette {@code git.unstageFile}; mirrors {@link #gitStageActiveFile}). */
    void gitUnstageActiveFile() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || repoRoot == null) {
            host.setStatus(tr("status.noGitFile"));
            return;
        }
        gitOp(
                "Unstaged " + file.getFileName(),
                "reset",
                "-q",
                "HEAD",
                "--",
                repoRoot.relativize(file.toAbsolutePath()).toString());
    }

    /** Discards the active file's changes (palette {@code git.discardFile}; confirms first). */
    void gitDiscardActiveFile() {
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file == null || repoRoot == null) {
            host.setStatus(tr("status.noGitFile"));
            return;
        }
        discardChanges(repoRoot.relativize(file.toAbsolutePath()).toString(), false);
    }

    // --- Project-tree file/folder actions (act on a given path, not the active buffer) ------------

    /** Repo-relative (forward-slash) path for {@code file}, or {@code null} when there's no repo/path. */
    private String rel(Path file) {
        if (file == null || repoRoot == null) {
            return null;
        }
        return repoRoot.relativize(file.toAbsolutePath()).toString().replace(java.io.File.separatorChar, '/');
    }

    /** {@code git add -- <path>} for a file or folder (the Project-tree "Stage" action). */
    void gitStagePath(Path file) {
        String rel = rel(file);
        if (rel == null || reportIfNoRepo()) {
            return;
        }
        gitOp("Staged " + file.getFileName(), "add", "--", rel);
    }

    /** {@code git reset -q HEAD -- <path>} for a file or folder (the Project-tree "Unstage" action). */
    void gitUnstagePath(Path file) {
        String rel = rel(file);
        if (rel == null || reportIfNoRepo()) {
            return;
        }
        gitOp("Unstaged " + file.getFileName(), "reset", "-q", "HEAD", "--", rel);
    }

    /**
     * Reverts local changes to {@code file} (the Project-tree "Revert" action) — {@code git clean} for an
     * untracked file (delete it), else {@code git checkout} (discard worktree changes). Confirms first (via
     * {@link #discardChanges}). A directory reverts the tracked changes under it.
     */
    void gitRevertPath(Path file) {
        String rel = rel(file);
        if (rel == null || reportIfNoRepo()) {
            return;
        }
        boolean untracked =
                lastStatusByPath.get(file.toAbsolutePath().normalize()) == com.editora.git.GitFileStatus.UNTRACKED;
        discardChanges(rel, untracked);
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
        String rel = rel(file);
        if (rel == null || reportIfNoRepo()) {
            return;
        }
        String entry = com.editora.git.GitIgnore.entryFor(rel, java.nio.file.Files.isDirectory(file));
        Path ignore = repoRoot.resolve(".gitignore");
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
            gitError("Could not update .gitignore", String.valueOf(e.getMessage()));
        }
    }

    // --- stash -------------------------------------------------------------------------------------

    /** Stashes the working tree (optionally with a message). */
    void gitStash() {
        if (reportIfNoRepo()) {
            return;
        }
        host.promptText(tr("stash.prompt.title"), tr("stash.prompt.label"), "", msg -> {
            String m = msg.strip();
            String[] args = m.isEmpty() ? new String[] {"stash", "push"} : new String[] {"stash", "push", "-m", m};
            service.run(
                    repoRoot,
                    r -> {
                        if (r.ok()) {
                            host.setStatus(tr("stash.pushed"));
                        } else {
                            gitError(tr("status.git.opFailed"), r.message());
                        }
                        afterMutation();
                        Platform.runLater(ops::checkExternalChanges);
                    },
                    args);
        });
    }

    /** Pops the most recent stash. */
    void gitStashPop() {
        gitMutateStash(tr("stash.popped"), "stash", "pop");
    }

    /** Opens a picker over the stash list to apply a chosen entry. */
    void gitUnstash() {
        chooseStash(
                tr("stash.picker.applyTitle"),
                entry -> gitMutateStash(tr("stash.applied"), "stash", "apply", entry.ref()));
    }

    /** Opens a picker over the stash list to drop a chosen entry. */
    void gitStashDrop() {
        chooseStash(
                tr("stash.picker.dropTitle"),
                entry -> gitMutateStash(tr("stash.dropped"), "stash", "drop", entry.ref()));
    }

    private void chooseStash(String title, Consumer<com.editora.git.StashParser.StashEntry> onPick) {
        if (reportIfNoRepo()) {
            return;
        }
        service.stashList(repoRoot, stashes -> {
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
                    onPick);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    private void gitMutateStash(String successMessage, String... args) {
        if (reportIfNoRepo()) {
            return;
        }
        service.run(
                repoRoot,
                r -> {
                    if (r.ok()) {
                        host.setStatus(successMessage);
                    } else {
                        gitError(tr("status.git.opFailed"), r.message());
                    }
                    afterMutation();
                    Platform.runLater(ops::checkExternalChanges);
                },
                args);
    }

    /**
     * Shows a Git command's (often multi-line) error output in a readable, scrollable dialog rather than
     * cramming it into the one-line status bar. The status bar gets a short summary.
     */
    void gitError(String summary, String detail) {
        host.setStatus(summary);
        String body = detail == null || detail.isBlank() ? summary : detail.strip();
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
                dirField.setText(
                        name.isEmpty()
                                ? ""
                                : Path.of(defaultParent).resolve(name).toString());
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
                dirField.setText(parent.toPath()
                        .resolve(name.isEmpty() ? "repository" : name)
                        .toString());
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
                    Path destination = Path.of(dirField.getText().strip());
                    if (Files.exists(destination)) {
                        host.setStatus(tr("status.destExists", destination));
                        return;
                    }
                    host.setStatus(tr("status.cloning", url));
                    service.clone(url, destination, r -> {
                        if (r.ok()) {
                            host.setStatus(tr("status.clonedInto", destination));
                            openClonedEntry(destination);
                        } else {
                            gitError("Clone failed", r.message());
                        }
                    });
                },
                null,
                false);
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
