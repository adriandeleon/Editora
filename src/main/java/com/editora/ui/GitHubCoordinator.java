package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.config.Settings;
import com.editora.diff.PatchParser;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.github.GitHubRemote;
import com.editora.github.GitHubService;
import com.editora.github.PrCreateArgs;
import com.editora.github.PrListParser;
import com.editora.github.PrReviewArgs;
import com.editora.github.PrViewParser;
import com.editora.github.RunListParser;
import com.editora.vfs.Vfs;

import static com.editora.i18n.Messages.tr;

/**
 * The stateful core of the GitHub integration (native {@code gh} CLI), the {@code GitCoordinator} analogue.
 * It owns the {@link GitHubService} (off-thread {@code gh} facade) + the cached availability, and the
 * user-facing flows: checking out a PR, reviewing a PR's diff in the existing diff viewer, opening the active
 * file on GitHub, creating a PR, and (slices 2/3) the PR/issue tool window + status-bar CI checks.
 *
 * <p>It rides on the Git integration: {@code gh} commands run with a working directory inside the repo (from
 * {@link GitCoordinator#repoRoot()}, else the active file's folder), so {@code gh} resolves owner/repo from
 * the git remote itself. PR diffs reuse the diff stack — {@code gh pr diff} → the pure {@link PatchParser} →
 * read-only text/text tabs via {@link DiffCoordinator#openDiff} — so no new diff code is needed.
 *
 * <p>Security: Editora never handles a token; {@code gh} holds the user's GitHub credentials and Editora only
 * shells out, consistent with the native-CLI Git design.
 */
final class GitHubCoordinator {

    /** Window surfaces the GitHub flows drive, beyond the shared {@link CoordinatorHost}. */
    interface WindowOps {
        /** After {@code gh pr checkout}, silently reload any open buffer whose file changed on disk. */
        void reloadAllFromDiskSilently();

        /** Re-checks the active file's on-disk stamp + reloads it if a gh command changed it under us. */
        void checkExternalChanges();

        /** This window's active keymap (for installing caret navigation on the create-PR form fields). */
        KeymapManager keymap();

        /** (Slice 2) Sets whether the GitHub tool-window stripe button is available (repo is a GitHub repo). */
        void setGitHubWindowAvailable(boolean available);

        /** (Slice 2) Opens/toggles the GitHub tool window. */
        void toggleGitHubWindow();

        /** (Slice 3) Pushes the active PR's CI-check roll-up to the status bar (null = hide). */
        void setStatusBarChecks(com.editora.github.ChecksParser.ChecksSummary summary);

        /** Opens the PR review overview as a selected editor tab. */
        void addReviewTab(com.editora.editor.TabContent pane);

        /** Selects the already-open tab whose content is {@code pane}; {@code false} when it isn't open. */
        boolean selectTabOf(com.editora.editor.TabContent pane);

        /** Re-fetches the GitHub panel's current segment (after a rerun/cancel changes run state). */
        void reloadGitHubPanel();

        // --- the shared Build Output console, on its own owner-routed "CI" tab -----------------------

        /** Opens the Build Output console on the CI tab and puts it in the running state. */
        void ciLogStarted(String header, Runnable onStop);

        void ciLogAppend(String line);

        void ciLogFinished();

        void ciLogFailed(String message);
    }

    /** Above this many changed files, "Open all files" confirms first so a big PR can't spam tabs. */
    private static final int MAX_OPEN_ALL_WITHOUT_CONFIRM = 12;

    private final CoordinatorHost host;
    private final GitCoordinator git;
    private final DiffCoordinator diff;
    private final WindowOps ops;
    private final GitHubService service = new GitHubService();

    /** The repo root whose remote URL has been classified (so the async check runs once per root). */
    private Path remoteCheckedRoot;
    /** Whether {@link #remoteCheckedRoot}'s remote is a GitHub host (drives the always-on surfaces). */
    private boolean remoteIsGitHub;

    /** The repo root whose open-PR/issue activity has been probed (so the async check runs once per root). */
    private Path activityCheckedRoot;
    /** Whether {@link #activityCheckedRoot} has at least one open PR or issue (gates the tool-window stripe). */
    private boolean hasActivity;

    /** Open PR review tabs, keyed by PR number (one tab per PR; re-review re-selects + refreshes it). */
    private final java.util.Map<Integer, PrReviewPane> reviewPanes = new java.util.HashMap<>();

    GitHubCoordinator(CoordinatorHost host, GitCoordinator git, DiffCoordinator diff, WindowOps ops) {
        this.host = host;
        this.git = git;
        this.diff = diff;
        this.ops = ops;
    }

    /** The off-thread {@code gh} facade (the slice-2 panel + slice-3 checks read through it). */
    GitHubService service() {
        return service;
    }

    /** Whether the GitHub integration is enabled in Settings. Off in Simple UI mode (like Git). */
    boolean isEnabled() {
        return host.settings().isGithubSupport() && !host.simpleModeActive();
    }

    /** Runs {@code action} only when GitHub is enabled; otherwise reports it (disables the command). */
    void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.githubDisabled"));
        }
    }

    /**
     * Reconciles the GitHub UI with the setting. When on: configures the {@code gh} command + probes
     * availability, then re-gates. When off: clears the tool-window/status surfaces. Runs at startup and on
     * every settings apply (the {@code GitCoordinator.applySupport} lifecycle slot).
     */
    void applySupport() {
        boolean on = isEnabled();
        if (!on) {
            ops.setGitHubWindowAvailable(false);
            ops.setStatusBarChecks(null);
            return;
        }
        service.setCommand(host.settings().getGhPath());
        service.detect(a -> applyGating());
    }

    /** Re-derives the tool-window availability from cached state (called on tab switch, cheap/sync). */
    void refreshAvailability() {
        applyGating();
    }

    /**
     * Re-derives the always-on surfaces: the tool-window stripe shows when GitHub is enabled + {@code gh} is
     * usable + the repo is a GitHub repo <em>and has at least one open PR or issue</em> (nothing to review ⇒
     * no icon). Repo-scoped, not buffer-scoped — so it stays visible across tab switches within the same
     * project window (both the remote-host and the open-activity checks are cached per repo root).
     */
    private void applyGating() {
        boolean available = isEnabled() && ready() && repoIsGitHub() && hasOpenActivity();
        ops.setGitHubWindowAvailable(available);
        if (!available) {
            ops.setStatusBarChecks(null);
        }
    }

    /** Whether the repo has open PRs/issues, probed off-thread once per root (cached; re-gates on completion). */
    private boolean hasOpenActivity() {
        Path root = git.repoRoot();
        if (root == null) {
            return false;
        }
        if (!root.equals(activityCheckedRoot)) {
            activityCheckedRoot = root;
            hasActivity = false;
            service.hasOpenActivity(root, any -> {
                if (root.equals(activityCheckedRoot)) { // still the current root
                    hasActivity = any;
                    applyGating();
                }
            });
            return false;
        }
        return hasActivity;
    }

    /** Whether {@code gh} is present + authenticated (from the cached probe; false until probed). */
    private boolean ready() {
        GitHubService.Availability a = service.availability();
        return a != null && a.ready();
    }

    /**
     * Whether the current repo's remote is a GitHub host — gates the always-on surfaces (the tool window +
     * checks) so they stay hidden on a GitLab/Gitea repo. The remote URL is resolved off-thread once per repo
     * root (via the shared {@code GitService}) then re-gated; the palette commands still run regardless and
     * surface gh's own error. Returns false until the async classification for a new root completes.
     */
    private boolean repoIsGitHub() {
        Path root = git.repoRoot();
        if (root == null) {
            return false;
        }
        if (!root.equals(remoteCheckedRoot)) {
            remoteCheckedRoot = root;
            remoteIsGitHub = false;
            git.service().branches(root, b -> {
                remoteIsGitHub = GitHubRemote.isGitHub(b.remoteUrl());
                applyGating();
            });
            return false;
        }
        return remoteIsGitHub;
    }

    // --- readiness guard shared by every gh flow -------------------------------------------------

    /** Runs {@code then} with the repo working directory once GitHub is enabled + {@code gh} is usable + in a
     *  repo; otherwise echoes the precise reason (disabled / gh missing / not authenticated / no repo). */
    private void ready(Consumer<Path> then) {
        if (!isEnabled()) {
            host.setStatus(tr("statusbar.tip.githubDisabled"));
            return;
        }
        GitHubService.Availability a = service.availability();
        if (a == null) {
            service.detect(av -> applyGating()); // not probed yet — kick one off; the user can retry
            host.setStatus(tr("status.github.checking"));
            return;
        }
        if (!a.found()) {
            host.setStatus(tr("status.github.ghNotFound"));
            return;
        }
        if (!a.authenticated()) {
            host.setStatus(tr("status.github.notAuthenticated"));
            return;
        }
        Path dir = contextDir();
        if (dir == null) {
            host.setStatus(tr("status.github.noRepo"));
            return;
        }
        then.accept(dir);
    }

    /** The working directory for {@code gh}: the git repo root, else the active file's folder, else null. */
    private Path contextDir() {
        Path root = git.repoRoot();
        if (root != null) {
            return root;
        }
        EditorBuffer b = host.activeBuffer();
        Path file = b == null ? null : b.getPath();
        if (file != null && Vfs.isLocal(file)) {
            return Files.isDirectory(file) ? file : file.getParent();
        }
        return null;
    }

    // --- refresh / toggle ------------------------------------------------------------------------

    /** Re-detects {@code gh}, re-gates the surfaces, and refreshes the CI-checks segment (the {@code github.refresh}
     *  command). No background polling — checks refresh only here + after a PR checkout. */
    void refresh() {
        // Invalidate the per-root caches so the stripe re-evaluates (e.g. after the repo's first PR is opened).
        remoteCheckedRoot = null;
        activityCheckedRoot = null;
        ifEnabled(() -> service.detect(a -> {
            applyGating();
            host.setStatus(tr(a.ready() ? "status.github.ready" : "status.github.notReady"));
            if (a.ready()) {
                refreshChecks(contextDir());
            }
        }));
    }

    /** Flips the {@code githubSupport} setting (the {@code view.toggleGithub} palette command). */
    void toggleSupport() {
        Settings s = host.settings();
        s.setGithubSupport(!s.isGithubSupport());
        host.requestSave();
        applySupport();
        host.syncSettingsWindow();
        host.setStatus(tr("status.toggle.github", tr(s.isGithubSupport() ? "common.on" : "common.off")));
    }

    // --- PR checkout -----------------------------------------------------------------------------

    /** Picks an open PR and checks it out ({@code gh pr checkout}); refreshes Git + reloads changed buffers. */
    void checkoutPr() {
        ready(dir -> pickPr(tr("github.picker.checkoutTitle"), pr -> doCheckout(dir, pr.number())));
    }

    /** Checks out a specific PR by number (the panel's row action). */
    void checkoutNumber(int number) {
        ready(dir -> doCheckout(dir, number));
    }

    private void doCheckout(Path dir, int number) {
        host.setStatus(tr("status.github.checkingOut", number));
        service.prCheckout(dir, number, r -> {
            if (r.ok()) {
                host.setStatus(tr("status.github.checkedOut", number));
                git.afterMutation();
                ops.reloadAllFromDiskSilently();
                javafx.application.Platform.runLater(ops::checkExternalChanges);
                refreshChecks(dir); // the checked-out branch's PR may have CI checks
            } else {
                ghError(tr("status.github.checkoutFailed"), r.message());
            }
        });
    }

    // --- PR diff review --------------------------------------------------------------------------

    /** Picks an open PR and opens its changed files in a review tab (GitHub "Files changed" style). */
    void viewPrDiff() {
        ready(dir -> pickPr(tr("github.picker.diffTitle"), pr -> doPrDiff(dir, pr.number())));
    }

    /** Opens a specific PR's review tab by number (the panel's row action / double-click). */
    void reviewPrNumber(int number) {
        ready(dir -> doPrDiff(dir, number));
    }

    /**
     * Fetches the PR's diff + metadata concurrently, then opens the review tab. Both {@code gh} calls deliver
     * on the FX thread, so the two-slot join uses plain fields (no synchronization). The metadata ({@code gh pr
     * view}) is optional — if it fails, the tab still opens with a number-only header; only a failed diff aborts.
     */
    private void doPrDiff(Path dir, int number) {
        PrViewParser.PrDetail[] detailSlot = new PrViewParser.PrDetail[1];
        GitHubService.DiffResult[] diffSlot = new GitHubService.DiffResult[1];
        boolean[] detailDone = {false};
        boolean[] diffDone = {false};
        Runnable join = () -> {
            if (!detailDone[0] || !diffDone[0]) {
                return;
            }
            GitHubService.DiffResult res = diffSlot[0];
            if (!res.ok()) {
                ghError(tr("status.github.diffFailed"), res.error());
                return;
            }
            openReview(number, detailSlot[0], res.files());
        };
        service.prView(dir, number, d -> {
            detailSlot[0] = d;
            detailDone[0] = true;
            join.run();
        });
        service.prDiff(dir, number, r -> {
            diffSlot[0] = r;
            diffDone[0] = true;
            join.run();
        });
    }

    /**
     * Opens (or refreshes-in-place) the PR review tab. A tab already open for this PR is re-selected + updated;
     * otherwise a brand-new review of a single-file PR opens that file's diff directly (the overview adds
     * nothing), an empty diff reports a status, and a multi-file PR opens the {@link PrReviewPane}.
     */
    private void openReview(int number, PrViewParser.PrDetail detail, List<PatchParser.FilePatch> files) {
        PrReviewPane existing = reviewPanes.get(number);
        if (existing != null && ops.selectTabOf(existing)) {
            existing.update(detail, files); // Refresh of an already-open tab
            return;
        }
        reviewPanes.remove(number); // a stale entry whose tab was closed — rebuild
        if (files.isEmpty()) {
            host.setStatus(tr("status.github.prDiffEmpty", number));
            return;
        }
        if (files.size() == 1) {
            openFileDiff(number, files.get(0));
            return;
        }
        PrReviewPane pane = new PrReviewPane(
                number,
                fp -> openFileDiff(number, fp),
                () -> {
                    PrReviewPane p = reviewPanes.get(number);
                    openAllFiles(number, p != null ? p.files() : files);
                },
                this::openUrl,
                () -> reviewPrNumber(number),
                () -> submitReview(number));
        pane.update(detail, files);
        reviewPanes.put(number, pane);
        ops.addReviewTab(pane);
    }

    /** Drops a closed review tab from the map (called by {@code MainController} on tab disposal). */
    void onReviewPaneClosed(PrReviewPane pane) {
        reviewPanes.values().remove(pane);
    }

    // --- tool-window feeds (slice 2) -------------------------------------------------------------

    /** Fetches open PRs for the tool window; delivers an empty list (with a status) when gh isn't usable. */
    void fetchPrs(Consumer<List<PrListParser.PullRequest>> onResult) {
        ready(dir -> service.prList(dir, res -> {
            if (!res.ok()) {
                host.setStatus(tr("status.github.prListFailed"));
                onResult.accept(List.of());
            } else {
                onResult.accept(res.prs());
            }
        }));
    }

    /** Fetches open issues for the tool window. */
    void fetchIssues(Consumer<List<com.editora.github.IssueListParser.Issue>> onResult) {
        ready(dir -> service.issueList(dir, res -> onResult.accept(res.ok() ? res.issues() : List.of())));
    }

    /** Fetches recent workflow runs for the tool window. */
    void fetchRuns(Consumer<List<RunListParser.WorkflowRun>> onResult) {
        ready(dir -> service.runList(dir, res -> {
            if (!res.ok()) {
                // A repo with Actions disabled / no permission errors here — report, don't modal.
                host.setStatus(tr("status.github.runListFailed"));
                onResult.accept(List.of());
            } else {
                onResult.accept(res.runs());
            }
        }));
    }

    // --- CI failure log → the shared Build Output console -----------------------------------------

    /** Bumped per log request so a superseded (or Stopped) fetch is dropped instead of painting the console. */
    private long ciLogGen;

    /**
     * Dumps a failed run's log ({@code gh run view <id> --log-failed}) into the shared Build Output console's
     * CI tab, where {@code RunPanel.installLinkClicks} + {@code MainController.openRunLink} make its stack
     * frames clickable — the runner's paths resolve to local files via the pure {@code run/RunnerPaths}.
     */
    void viewRunLog(long runId, String workflowName) {
        ready(dir -> {
            long gen = ++ciLogGen;
            // There's no live process to kill — Stop just abandons the pending delivery.
            ops.ciLogStarted(workflowName + " · run " + runId, () -> ciLogGen++);
            service.runFailedLog(dir, runId, res -> {
                if (gen != ciLogGen) {
                    return; // superseded by another request, or stopped
                }
                if (!res.ok()) {
                    ops.ciLogFailed(res.error());
                    ghError(tr("status.github.runLogFailed"), res.error());
                    return;
                }
                if (res.truncated()) {
                    ops.ciLogAppend(tr("github.ci.truncated"));
                }
                for (String line : res.lines()) {
                    ops.ciLogAppend(line);
                }
                ops.ciLogFinished();
            });
        });
    }

    /** Palette flow: pick a run (failed ones only, or all when none failed) and show its failure log. */
    void viewRunLogPicked() {
        ready(dir -> service.runList(dir, res -> {
            if (!res.ok()) {
                ghError(tr("status.github.runListFailed"), res.error());
                return;
            }
            if (res.runs().isEmpty()) {
                host.setStatus(tr("status.github.noRuns"));
                return;
            }
            List<RunListParser.WorkflowRun> failed =
                    res.runs().stream().filter(r -> r.state().failed()).toList();
            List<RunListParser.WorkflowRun> choices = failed.isEmpty() ? res.runs() : failed;
            QuickOpen<RunListParser.WorkflowRun> picker = new QuickOpen<>(
                    tr("github.picker.runTitle"),
                    tr("github.picker.runPrompt"),
                    () -> choices,
                    r -> r.state().glyph() + "  " + r.workflowName() + "  " + r.displayTitle(),
                    r -> r.headBranch() + " · " + r.event(),
                    r -> r.workflowName() + " " + r.displayTitle() + " " + r.headBranch(),
                    r -> viewRunLog(r.databaseId(), r.workflowName()));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        }));
    }

    /** Re-runs a workflow run (optionally only its failed jobs), then refreshes the panel. */
    void rerunRun(long runId, boolean failedOnly) {
        ready(dir -> {
            host.setStatus(tr("status.github.rerunning", runId));
            service.runRerun(dir, runId, failedOnly, r -> {
                if (r.ok()) {
                    host.setStatus(tr("status.github.rerunStarted", runId));
                    ops.reloadGitHubPanel();
                } else {
                    ghError(tr("status.github.rerunFailed"), r.message());
                }
            });
        });
    }

    /** Cancels an in-flight workflow run, then refreshes the panel. */
    void cancelRun(long runId) {
        ready(dir -> {
            host.setStatus(tr("status.github.cancelling", runId));
            service.runCancel(dir, runId, r -> {
                if (r.ok()) {
                    host.setStatus(tr("status.github.runCancelled", runId));
                    ops.reloadGitHubPanel();
                } else {
                    ghError(tr("status.github.cancelFailed"), r.message());
                }
            });
        });
    }

    /** Opens a GitHub URL in the browser (a panel row's "Open on GitHub"). */
    void openUrl(String url) {
        if (url == null || url.isBlank()) {
            host.setStatus(tr("status.github.noFile"));
            return;
        }
        host.openExternalUrl(url);
    }

    /** Copies a GitHub URL to the clipboard (a panel row's "Copy URL"). */
    void copyUrl(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(url);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        host.setStatus(tr("status.github.copiedUrl"));
    }

    // --- CI checks status-bar roll-up (slice 3) --------------------------------------------------

    /** Fetches the current branch's PR checks and pushes the roll-up to the status bar (null hides it). */
    private void refreshChecks(Path dir) {
        if (dir == null) {
            ops.setStatusBarChecks(null);
            return;
        }
        service.prChecks(dir, 0, res -> {
            if (res.ok() && !res.runs().isEmpty()) {
                ops.setStatusBarChecks(com.editora.github.ChecksParser.ChecksSummary.of(res.runs()));
            } else {
                ops.setStatusBarChecks(null); // no PR for this branch, or no checks
            }
        });
    }

    /** Opens every changed file of a PR as its own diff tab — confirming first past {@link #MAX_OPEN_ALL_WITHOUT_CONFIRM}. */
    private void openAllFiles(int prNumber, List<PatchParser.FilePatch> files) {
        if (files.size() > MAX_OPEN_ALL_WITHOUT_CONFIRM && !confirmOpenAll(files.size())) {
            return;
        }
        for (PatchParser.FilePatch fp : files) {
            openFileDiff(prNumber, fp);
        }
    }

    private boolean confirmOpenAll(int count) {
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.github.openAllConfirm", count),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.github.title"));
        confirm.setHeaderText(null);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** Opens one PR file as a read-only side-by-side diff tab (base ↔ head), reusing {@link DiffCoordinator}. */
    private void openFileDiff(int prNumber, PatchParser.FilePatch fp) {
        String oldName = cleanLabel(fp.oldPath());
        String newName = cleanLabel(fp.newPath());
        String name = !newName.isEmpty() ? newName : (!oldName.isEmpty() ? oldName : tr("github.pr.file"));
        String leftName = !oldName.isEmpty() ? oldName : name;
        String rightName = !newName.isEmpty() ? newName : name;
        String leftText = String.join("\n", fp.oldLines());
        String rightText = String.join("\n", fp.newLines());
        diff.openDiff(
                tr("diff.title.prFile", name, prNumber),
                tr("diff.side.prBase"),
                tr("diff.side.prHead"),
                leftName,
                rightName,
                cb -> cb.accept(leftText),
                cb -> cb.accept(rightText),
                DiffViewerPane.EditableSide.NONE,
                null);
    }

    // --- open on GitHub --------------------------------------------------------------------------

    /** Opens the active file (at the caret line, on the current branch) on GitHub in the browser. */
    void openOnGitHub() {
        ready(dir -> {
            EditorBuffer b = host.activeBuffer();
            Path file = b == null ? null : b.getPath();
            if (file == null || !Vfs.isLocal(file)) {
                host.setStatus(tr("status.github.noFile"));
                return;
            }
            Path root = git.repoRoot();
            String rel = root == null ? null : GitService.repoRelative(root, file);
            if (rel == null) {
                host.setStatus(tr("status.github.noRepo"));
                return;
            }
            int line = b.getArea().getCurrentParagraph() + 1; // 1-based
            String branch = git.branchName();
            service.browse(dir, rel + ":" + line, branch, r -> {
                String url = r.out() == null ? "" : r.out().strip();
                if (r.ok() && !url.isEmpty()) {
                    host.openExternalUrl(url);
                    host.setStatus(tr("status.github.opened"));
                } else {
                    ghError(tr("status.github.browseFailed"), r.message());
                }
            });
        });
    }

    // --- create PR (slice 3) ---------------------------------------------------------------------

    /** Opens the create-PR form (title / body / base / draft) and runs {@code gh pr create}. */
    void createPr() {
        ready(dir -> {
            KeymapManager keymap = ops.keymap();
            TextField titleField = new TextField();
            titleField.setPromptText(tr("dialog.createPr.titlePrompt"));
            titleField.setPrefColumnCount(34);
            TextInputKeymap.install(titleField, keymap);
            TextArea bodyField = new TextArea();
            bodyField.setPromptText(tr("dialog.createPr.bodyPrompt"));
            bodyField.setPrefRowCount(5);
            bodyField.setWrapText(true);
            TextInputKeymap.install(bodyField, keymap);
            TextField baseField = new TextField(); // blank ⇒ gh uses the repo's default base branch
            baseField.setPromptText(tr("dialog.createPr.basePrompt"));
            TextInputKeymap.install(baseField, keymap);
            CheckBox draft = new CheckBox(tr("dialog.createPr.draft"));

            GridPane grid = new GridPane();
            grid.setHgap(8);
            grid.setVgap(8);
            grid.add(new Label(tr("dialog.createPr.titleLabel")), 0, 0);
            grid.add(titleField, 1, 0);
            grid.add(new Label(tr("dialog.createPr.bodyLabel")), 0, 1);
            grid.add(bodyField, 1, 1);
            grid.add(new Label(tr("dialog.createPr.baseLabel")), 0, 2);
            grid.add(baseField, 1, 2);
            grid.add(draft, 1, 3);
            GridPane.setHgrow(titleField, Priority.ALWAYS);
            GridPane.setHgrow(bodyField, Priority.ALWAYS);
            GridPane.setHgrow(baseField, Priority.ALWAYS);

            BooleanProperty valid =
                    new SimpleBooleanProperty(!titleField.getText().isBlank());
            titleField.textProperty().addListener((o, a, b) -> valid.set(!b.isBlank()));

            OverlayInput.show(
                    host.overlayHost(),
                    tr("dialog.createPr.title"),
                    grid,
                    titleField,
                    tr("dialog.createPr.button"),
                    valid,
                    () -> {
                        List<String> args = PrCreateArgs.build(
                                titleField.getText(), bodyField.getText(), baseField.getText(), draft.isSelected());
                        host.setStatus(tr("status.github.creatingPr"));
                        service.prCreate(dir, args, r -> {
                            String url = r.out() == null ? "" : r.out().strip();
                            if (r.ok()) {
                                host.setStatus(tr("status.github.createdPr"));
                                if (!url.isEmpty()) {
                                    host.openExternalUrl(lastUrl(url));
                                }
                            } else {
                                ghError(tr("status.github.createFailed"), r.message());
                            }
                        });
                    },
                    null,
                    true);
        });
    }

    // --- submit review (approve / request changes / comment) -------------------------------------

    /** Picks a PR then opens the submit-review form (the {@code github.submitReview} palette command). */
    void submitReviewPicked() {
        ready(dir -> pickPr(tr("github.picker.reviewTitle"), pr -> submitReviewForm(dir, pr.number())));
    }

    /** Opens the submit-review form for a specific PR (the review pane's "Submit review" link). */
    void submitReview(int number) {
        ready(dir -> submitReviewForm(dir, number));
    }

    private void submitReviewForm(Path dir, int number) {
        KeymapManager keymap = ops.keymap();
        javafx.scene.control.ComboBox<PrReviewArgs.ReviewAction> actionBox = new javafx.scene.control.ComboBox<>();
        actionBox.getItems().setAll(PrReviewArgs.ReviewAction.values());
        actionBox.getSelectionModel().select(PrReviewArgs.ReviewAction.APPROVE);
        actionBox.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(PrReviewArgs.ReviewAction a) {
                return a == null
                        ? ""
                        : tr(
                                switch (a) {
                                    case APPROVE -> "dialog.review.approve";
                                    case REQUEST_CHANGES -> "dialog.review.requestChanges";
                                    case COMMENT -> "dialog.review.comment";
                                });
            }

            @Override
            public PrReviewArgs.ReviewAction fromString(String s) {
                return null;
            }
        });

        TextArea body = new TextArea();
        body.setPromptText(tr("dialog.review.bodyPrompt"));
        body.setPrefRowCount(5);
        body.setWrapText(true);
        TextInputKeymap.install(body, keymap);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("dialog.review.action")), 0, 0);
        grid.add(actionBox, 1, 0);
        grid.add(new Label(tr("dialog.review.body")), 0, 1);
        grid.add(body, 1, 1);
        GridPane.setHgrow(body, Priority.ALWAYS);

        // Approve may be submitted with no comment; request-changes / comment require a body (gh rejects empty).
        BooleanProperty valid = new SimpleBooleanProperty(false);
        Runnable revalidate = () -> {
            PrReviewArgs.ReviewAction a = actionBox.getValue();
            valid.set(a != null
                    && (!PrReviewArgs.bodyRequired(a) || !body.getText().isBlank()));
        };
        actionBox.valueProperty().addListener((o, x, y) -> revalidate.run());
        body.textProperty().addListener((o, x, y) -> revalidate.run());
        revalidate.run();

        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.review.title", number),
                grid,
                body,
                tr("dialog.review.button"),
                valid,
                () -> {
                    List<String> args = PrReviewArgs.build(number, actionBox.getValue(), body.getText());
                    host.setStatus(tr("status.github.reviewing", number));
                    service.prReview(dir, args, r -> {
                        if (r.ok()) {
                            host.setStatus(tr("status.github.reviewed", number));
                        } else {
                            ghError(tr("status.github.reviewFailed"), r.message());
                        }
                    });
                },
                null,
                true);
    }

    // --- shared helpers --------------------------------------------------------------------------

    /** Fetches open PRs and shows a picker; reports the failure / empty cases distinctly. */
    private void pickPr(String title, Consumer<PrListParser.PullRequest> onPick) {
        host.setStatus(tr("status.github.loadingPrs"));
        service.prList(contextDir(), res -> {
            if (!res.ok()) {
                ghError(tr("status.github.prListFailed"), res.error());
                return;
            }
            if (res.prs().isEmpty()) {
                host.setStatus(tr("status.github.noPrs"));
                return;
            }
            QuickOpen<PrListParser.PullRequest> picker = new QuickOpen<>(
                    title,
                    tr("github.picker.prPrompt"),
                    res::prs,
                    pr -> "#" + pr.number() + "  " + pr.title() + (pr.draft() ? "  " + tr("github.draft") : ""),
                    pr -> pr.authorLogin() + " · " + pr.headRefName(),
                    pr -> "#" + pr.number() + " " + pr.title() + " " + pr.authorLogin() + " " + pr.headRefName(),
                    onPick);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** {@code ""} for a missing / {@code /dev/null} patch label, else the label unchanged. */
    private static String cleanLabel(String path) {
        return path == null || path.isBlank() || "/dev/null".equals(path) ? "" : path;
    }

    /** The last whitespace-separated token of {@code gh}'s stdout — the created PR URL is the last line. */
    private static String lastUrl(String out) {
        String[] lines = out.strip().split("\\s+");
        return lines.length == 0 ? out.strip() : lines[lines.length - 1];
    }

    /** A scrollable error dialog for a {@code gh} command's (often multi-line) output — mirrors gitError. */
    private void ghError(String summary, String detail) {
        host.setStatus(summary);
        String body = detail == null || detail.isBlank() ? summary : detail.strip();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(host.window());
        alert.setTitle(tr("dialog.github.title"));
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

    void shutdown() {
        service.shutdown();
    }
}
