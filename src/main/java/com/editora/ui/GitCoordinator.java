package com.editora.ui;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

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
 * <p>{@code MainController} keeps the user-facing Git <em>operations</em> (commit, branch, log, blame
 * click→commit-diff, stash, clone, diff) and reaches into this coordinator for the shared engine:
 * {@link #service()} for the CLI facade and {@link #repoRoot()} for the active repo. Everything the engine
 * needs from the window goes through the shared {@link CoordinatorHost} (settings, simple-mode gate, active
 * buffer, theme brightness, per-buffer iteration) plus a small git-specific {@link WindowOps} for the
 * surfaces it drives.
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

        /** Re-diffs any open diff tabs (a mutation moved HEAD/index/working). */
        void refreshOpenDiffs();

        /** The active project's root folder, or {@code null} when no project is open. */
        Path projectRoot();
    }

    private final CoordinatorHost host;
    private final WindowOps ops;
    private final GitService service = new GitService();

    private Path repoRoot;
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
}
