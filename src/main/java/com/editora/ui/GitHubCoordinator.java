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
import com.editora.github.ChecksParser;
import com.editora.github.ChecksPoll;
import com.editora.github.GitHubListQuery;
import com.editora.github.GitHubRemote;
import com.editora.github.GitHubService;
import com.editora.github.PrCreateArgs;
import com.editora.github.PrDraft;
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

        /** Makes the GitHub stripe visible for a first-time user, preserving any explicit show/hide choice. */
        void enableGitHubWindowByDefault();

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

        // --- the shared Output console, on its own owner-routed "CI" tab -----------------------

        /** Opens the Output console on the CI tab and puts it in the running state. */
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

    /** The repo root whose remotes have been read (so the async check runs once per root). */
    private Path remoteCheckedRoot;
    /**
     * The URLs of <em>all</em> of {@link #remoteCheckedRoot}'s remotes. Whether one of them is a GitHub host
     * (which drives the always-on surfaces) is decided against the hosts {@code gh} is signed in to, so it is
     * derived on demand ({@link #repoIsGitHub}) rather than cached as a boolean.
     */
    private List<String> remoteUrls = List.of();

    /** The repo root whose open-PR/issue activity has been probed (so the async check runs once per root). */
    private Path activityCheckedRoot;
    /** Whether {@link #activityCheckedRoot} has at least one open PR or issue (gates the tool-window stripe). */
    private boolean hasActivity;
    /** An activity probe is running: do not start another for the same root. */
    private boolean activityProbing;
    /**
     * The last activity probe could not find out (offline, rate limit): that is not "no activity", so it is
     * asked again — at the earliest at {@link #activityRetryAtNanos}, by {@link #activityRetry} or the next
     * gating, and automatically at most {@link #MAX_ACTIVITY_RETRIES} times.
     */
    private boolean activityUnknown;

    private long activityRetryAtNanos;
    private int activityRetries;
    private final javafx.animation.PauseTransition activityRetry = new javafx.animation.PauseTransition();

    private static final int MAX_ACTIVITY_RETRIES = 4;
    /** The first automatic retry's delay; each later one doubles it. (A field so a test need not wait.) */
    java.time.Duration activityRetryAfter = java.time.Duration.ofSeconds(15);

    /**
     * A command that meets a cached "gh missing / not signed in" re-probes — but not more often than this.
     * (A field so a test need not wait.)
     */
    java.time.Duration negativeReprobeAfter = java.time.Duration.ofSeconds(5);

    /** A pull request is a number <em>in a repository</em>: #7 of two repositories are two pull requests. */
    record ReviewKey(Path dir, int number) {}

    /** Open PR review tabs, keyed by repository + PR number (one tab per PR; re-review refreshes it). */
    private final java.util.Map<ReviewKey, PrReviewPane> reviewPanes = new java.util.HashMap<>();

    /**
     * The repository the tool window's rows were listed from. A row carries only a PR number / run id, so its
     * actions must run there — not in whichever repository happens to be active when the row is clicked.
     */
    private Path panelDir;

    /** Bumped per picker request, so only the latest picker opens (pickers are not generation-guarded by the service). */
    private long pickGen;

    /** The repository + branch the Git state was last seen with (see {@link #repositoryChanged}). */
    private Path seenRoot;

    private String seenBranch = "";
    /** Whether the user has asked for the CI-checks roll-up (so a branch switch re-derives it). */
    private boolean checksWanted;
    /** Bumped per checks request and on every repository/branch change: a stale answer is dropped. */
    private long checksGen;
    /** Set by {@code github.refresh}: re-run the per-root probes, but keep their last answer meanwhile. */
    private boolean remoteStale;

    private boolean activityStale;

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

    /**
     * Runs {@code action} only when GitHub is enabled <em>and Git support is on</em>; otherwise reports which
     * of the two is off (disables the command).
     */
    void ifEnabled(Runnable action) {
        String off = disabledReason();
        if (off == null) {
            action.run();
        } else {
            host.setStatus(off);
        }
    }

    /**
     * Why no GitHub command can run at all, or {@code null}: the integration is switched off, or Git support
     * is — GitHub rides on the Git integration (the repository, its branch, its remotes), so with Git off
     * the tool window never appears and no command has a repository. Said in so many words, with the command
     * that turns Git on, instead of "open a file inside a GitHub repository".
     */
    private String disabledReason() {
        if (!isEnabled()) {
            return tr("statusbar.tip.githubDisabled");
        }
        if (!git.isEnabled()) {
            return tr("status.github.gitDisabled", tr("command.view.toggleGit"));
        }
        return null;
    }

    /** How many {@code gh} calls are queued or running — for a busy indicator ({@code greaterThan(0)}). */
    javafx.beans.property.ReadOnlyIntegerProperty callsInFlightProperty() {
        return service.activeCallsProperty();
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
        // This runs on every settings save. The cached answer is dropped only when the gh command actually
        // changed; a good answer for the same command is simply re-applied (no gh process per save), and
        // anything else is re-probed with the last answer left standing meanwhile — so an open tool window
        // is not closed, nor a command answered "Checking for the gh CLI…", by an unrelated setting.
        boolean commandChanged = service.setCommand(host.settings().getGhPath());
        GitHubService.Availability cached = service.availability();
        Consumer<GitHubService.Availability> apply = a -> {
            // Installation, not authentication, determines the first-run visibility default. Availability
            // still requires authentication + a GitHub repo with activity, so this never exposes a dead stripe.
            if (a.found()) {
                ops.enableGitHubWindowByDefault();
            }
            applyGating();
        };
        if (!commandChanged && cached != null && cached.authenticated()) {
            apply.accept(cached);
        } else {
            service.detect(apply);
        }
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
        autoFetchChecks(available); // G12 hook (flows region): the roll-up needs no manual refresh
    }

    /**
     * Whether the repo has open PRs/issues, probed off-thread once per root (cached; re-gates on completion).
     * Only a real answer is cached: a probe that could not find out keeps the previous answer and is retried.
     */
    private boolean hasOpenActivity() {
        Path root = git.repoRoot();
        if (root == null) {
            return false;
        }
        boolean sameRoot = root.equals(activityCheckedRoot);
        boolean retryDue = activityUnknown && System.nanoTime() - activityRetryAtNanos >= 0;
        if (!sameRoot || activityStale || (retryDue && !activityProbing)) {
            // A re-probe of the SAME root keeps its last answer while it runs: forcing "no activity" would
            // close the open tool window on every github.refresh. A new root is unknown until probed.
            boolean previous = sameRoot && hasActivity;
            if (!sameRoot || activityStale) {
                activityRetries = 0;
            }
            activityCheckedRoot = root;
            activityStale = false;
            activityUnknown = false;
            activityProbing = true;
            activityRetry.stop();
            hasActivity = previous;
            service.openActivity(root, activity -> {
                if (!root.equals(activityCheckedRoot)) {
                    return; // no longer the current root (its own probe is running)
                }
                activityProbing = false;
                if (activity == GitHubService.Activity.UNKNOWN) {
                    activityUnknown = true;
                    activityRetryAtNanos = System.nanoTime() + activityRetryAfter.toNanos();
                    if (activityRetries++ < MAX_ACTIVITY_RETRIES) {
                        activityRetry.setDuration(javafx.util.Duration.millis(
                                activityRetryAfter.toMillis() * (double) (1L << (activityRetries - 1)) + 50));
                        activityRetry.setOnFinished(e -> applyGating());
                        activityRetry.playFromStart();
                    }
                    return;
                }
                hasActivity = activity == GitHubService.Activity.YES;
                applyGating();
            });
        }
        return hasActivity;
    }

    /** Whether {@code gh} is present + has a sign-in (from the cached probe; false until probed). */
    private boolean ready() {
        GitHubService.Availability a = service.availability();
        return a != null && a.ready();
    }

    /**
     * Whether one of the current repo's remotes is a GitHub host — gates the always-on surfaces (the tool
     * window + checks) so they stay hidden on a GitLab/Gitea repo. Every remote counts, not only
     * {@code origin} (gh works from a fork whose GitHub remote is {@code upstream}), and "a GitHub host" is
     * one {@code gh} is signed in to when that is known ({@link GitHubRemote#anyGitHub}). The remotes are
     * read off-thread once per repo root (via the shared {@code GitService}) then re-gated; the palette
     * commands still run regardless and surface gh's own error. Returns false until the remotes of a new
     * root have been read.
     */
    private boolean repoIsGitHub() {
        Path root = git.repoRoot();
        if (root == null) {
            return false;
        }
        if (!root.equals(remoteCheckedRoot) || remoteStale) {
            List<String> previous = root.equals(remoteCheckedRoot) ? remoteUrls : List.of(); // see hasOpenActivity()
            remoteCheckedRoot = root;
            remoteStale = false;
            remoteUrls = previous;
            git.service().remotes(root, remotes -> {
                if (root.equals(remoteCheckedRoot)) { // still the current root
                    List<String> urls = new java.util.ArrayList<>();
                    for (com.editora.git.GitRemotes.Remote remote : remotes) {
                        urls.add(remote.fetchUrl());
                        urls.add(remote.pushUrl());
                    }
                    remoteUrls = urls;
                    applyGating();
                }
            });
        }
        GitHubService.Availability a = service.availability();
        return GitHubRemote.anyGitHub(remoteUrls, a == null ? List.of() : a.hosts());
    }

    // --- readiness guard shared by every gh flow -------------------------------------------------

    /** Runs {@code then} with the repo working directory once GitHub is enabled + {@code gh} is usable + in a
     *  repo; otherwise echoes the precise reason (disabled / Git off / gh missing / not signed in / no repo). */
    private void ready(Consumer<Path> then) {
        readyIn(null, then);
    }

    /**
     * {@link #ready(Consumer)} for an action that already belongs to a repository — a tool-window row, a PR
     * review tab: {@code captured} (when non-null) is used instead of the active tab's repository, which is a
     * different one, or none at all, by the time the row or the tab's own link is clicked. Returns the reason
     * the action could not run (already echoed), or {@code null} when {@code then} ran.
     *
     * <p>A cached "gh missing / not signed in" is not final: meeting it starts a fresh probe (bounded by
     * {@link #negativeReprobeAfter}), so installing gh or running {@code gh auth login} takes effect
     * without {@code github.refresh}. An unverified sign-in (GitHub was unreachable when probed) lets the
     * command run — if GitHub still cannot be reached, gh says exactly that.
     */
    private String readyIn(Path captured, Consumer<Path> then) {
        String reason = disabledReason();
        Path dir = null;
        GitHubService.Availability a = service.availability();
        if (reason != null) {
            // disabled, or Git support is off
        } else if (a == null) {
            service.detect(av -> applyGating()); // not probed yet — kick one off; the user can retry
            reason = tr("status.github.checking");
        } else if (!a.ready()) {
            reason = notReadyReason(a);
            reprobe(a);
        } else {
            if (a.unverified()) {
                reprobe(a);
            }
            dir = captured != null ? captured : contextDir();
            if (dir == null) {
                reason = tr("status.github.noRepo");
            }
        }
        if (reason != null) {
            host.setStatus(reason);
            return reason;
        }
        then.accept(dir);
        return null;
    }

    /** Why {@code gh} cannot be used, for an availability that is not {@link GitHubService.Availability#ready()}. */
    private static String notReadyReason(GitHubService.Availability a) {
        if (!a.found()) {
            return tr("status.github.ghNotFound");
        }
        return tr(
                a.auth() == GitHubService.AuthState.REJECTED
                        ? "status.github.tokenRejected"
                        : "status.github.notAuthenticated");
    }

    /**
     * Asks {@code gh} again after a command met the cached answer {@code was} (see {@link #readyIn}). When
     * that turns "not usable" into "usable", the surfaces are re-gated, the tool window reloads, and the
     * status bar says so — the command the user just ran can simply be run again.
     */
    private void reprobe(GitHubService.Availability was) {
        service.redetectIfOlderThan(negativeReprobeAfter, now -> {
            applyGating();
            if (now.ready() && !was.ready() && isEnabled()) {
                host.setStatus(tr("status.github.ready"));
                ops.reloadGitHubPanel();
            }
        });
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
     *  command). The checks are also fetched on their own per branch and polled while pending (see
     *  {@link #autoFetchChecks}); this is the manual "ask again now". */
    void refresh() {
        // Invalidate the per-root caches so the stripe re-evaluates (e.g. after the repo's first PR is opened).
        // Their last answers stand until the new ones arrive, so an open tool window is not closed meanwhile.
        remoteStale = true;
        activityStale = true;
        ifEnabled(() -> service.detect(a -> {
            applyGating();
            host.setStatus(
                    !a.ready()
                            ? notReadyReason(a)
                            : tr(a.unverified() ? "status.github.unverified" : "status.github.ready"));
            if (a.ready()) {
                refreshChecks(contextDir());
            }
        }));
    }

    /**
     * The active repository or its branch changed (told by {@code GitCoordinator.applyState} through the
     * window). The tool-window gating is re-derived at once — a tab switch evaluates it before the
     * asynchronous Git refresh has landed, so the stripe used to appear only on the <em>next</em> switch —
     * and the CI roll-up, which describes one branch's pull request, is dropped rather than left showing the
     * previous branch's result. After a branch change inside the same repository it is fetched again when the
     * user has asked for it before; switching repositories only clears it (no {@code gh} call per tab switch).
     */
    void repositoryChanged(Path root, String branch) {
        String now = branch == null ? "" : branch;
        boolean sameRoot = java.util.Objects.equals(root, seenRoot);
        if (sameRoot && now.equals(seenBranch)) {
            return;
        }
        boolean branchSwitch = sameRoot && root != null && !seenBranch.isEmpty();
        seenRoot = root;
        seenBranch = now;
        if (!isEnabled()) {
            return;
        }
        checksGen++;
        forgetChecks(branchSwitch ? root : null);
        ops.setStatusBarChecks(null);
        if (root != null) {
            applyGating(); // losing the repository (a non-file tab) is still left to the tab-switch path
        }
        if (branchSwitch && checksWanted && ready() && !checksAskedFor(root, now)) {
            refreshChecks(root);
        }
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
        readyIn(panelDir, dir -> doCheckout(dir, number));
    }

    private void doCheckout(Path dir, int number) {
        host.setStatus(tr("status.github.checkingOut", number));
        // `gh pr checkout` switches branches just as `git checkout` does, so it goes through the same
        // boundary: saves queued for files in the repository are superseded before and after it (a pending
        // save must not write the old branch's text over the checked-out file), then the Git UI refreshes
        // and clean buffers reload — on failure too, since a refused checkout can still have moved files.
        // In `dir`'s repository, not the active tab's: a row of the tool window (or a review tab's link)
        // checks out in the repository it was listed from, and it is that repository's pending saves that
        // must not land on the switched files.
        git.aroundWorkingTreeMutation(
                mutationRoot(dir, git.repoRoot()),
                done -> service.prCheckout(dir, number, done),
                r -> {
                    if (r.ok()) {
                        host.setStatus(tr("status.github.checkedOut", number));
                        javafx.application.Platform.runLater(ops::checkExternalChanges);
                        refreshChecks(dir); // the checked-out branch's PR may have CI checks
                    } else {
                        ghError(tr("status.github.checkoutFailed"), r.message());
                    }
                },
                null);
    }

    /**
     * The repository a {@code gh} working-tree mutation run in {@code dir} rewrites: {@code dir} itself when
     * it was captured from a listing (a repository root), and the active repository only when {@code dir} lies
     * inside it (the palette flow, where {@code dir} may be the active file's folder). Pure.
     */
    static Path mutationRoot(Path dir, Path activeRoot) {
        return activeRoot != null && dir != null && dir.startsWith(activeRoot) ? activeRoot : dir;
    }

    // --- PR diff review --------------------------------------------------------------------------

    /** Picks an open PR and opens its changed files in a review tab (GitHub "Files changed" style). */
    void viewPrDiff() {
        ready(dir -> pickPr(tr("github.picker.diffTitle"), pr -> doPrDiff(dir, pr.number())));
    }

    /** Opens a specific PR's review tab by number (the panel's row action / double-click). */
    void reviewPrNumber(int number) {
        readyIn(panelDir, dir -> doPrDiff(dir, number));
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
            if (res.ok()) {
                // A diff cut off at the capture limit lost its last files: the tab and the status bar say so.
                String cut = res.truncated() ? tr("status.github.diffCannotShowAll") : "";
                openReview(dir, number, detailSlot[0], res.files(), cut);
                if (res.truncated()) {
                    host.setStatus(cut);
                }
            } else if (com.editora.github.PrFilesParser.diffTooLarge(res.error())) {
                // GitHub refuses the whole diff above 300 files / 20,000 lines; the files API still lists
                // every file with its own hunks, so the review opens from that and says so.
                host.setStatus(tr("status.github.prDiffFallback", number));
                service.prFiles(dir, number, files -> {
                    if (!files.ok()) {
                        ghError(tr("status.github.diffFailed"), files.error());
                        return;
                    }
                    String notice = fallbackNotice(files.files());
                    if (files.truncated()) { // the API's answer itself passed the capture limit
                        notice += " " + tr("status.github.diffCannotShowAll");
                    }
                    openReview(dir, number, detailSlot[0], files.files().files(), notice);
                });
            } else {
                ghError(tr("status.github.diffFailed"), res.error());
            }
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

    /** What the review tab says when its files came from the files API instead of {@code gh pr diff}. */
    static String fallbackNotice(com.editora.github.PrFilesParser.Result files) {
        String notice = tr("github.review.fallback");
        if (files.withoutPatch() > 0) {
            notice += " " + tr("github.review.fallbackNoPatch", files.withoutPatch());
        }
        if (files.capped()) {
            notice += " " + tr("github.review.fallbackCapped", com.editora.github.PrFilesParser.API_FILE_LIMIT);
        }
        return notice;
    }

    /**
     * Opens (or refreshes-in-place) the PR review tab. A tab already open for this PR is re-selected + updated;
     * an empty diff reports a status; anything else opens the {@link PrReviewPane} — a single-file pull
     * request too (it used to open the bare file diff, from which the description, Open on GitHub, Submit
     * review and Refresh could not be reached). {@code notice}
     * (may be blank) is shown in the tab — the files-API fallback explains itself there.
     */
    private void openReview(
            Path dir, int number, PrViewParser.PrDetail detail, List<PatchParser.FilePatch> files, String notice) {
        ReviewKey key = new ReviewKey(dir, number);
        PrReviewPane existing = reviewPanes.get(key);
        if (existing != null && ops.selectTabOf(existing)) {
            existing.update(detail, files, notice); // Refresh of an already-open tab
            return;
        }
        reviewPanes.remove(key); // a stale entry whose tab was closed — rebuild
        if (files.isEmpty()) {
            host.setStatus(tr("status.github.prDiffEmpty", number));
            return;
        }
        PrReviewPane pane = new PrReviewPane(
                number,
                fp -> openFileDiff(number, fp),
                () -> {
                    PrReviewPane p = reviewPanes.get(key);
                    openAllFiles(number, p != null ? p.files() : files);
                },
                this::openUrl,
                // The tab's own links act on the repository the pull request was opened from: with this tab
                // active the Git context is the project's repository, or none at all in a No-Project window.
                () -> readyIn(dir, d -> doPrDiff(d, number)),
                () -> readyIn(dir, d -> submitReviewForm(d, number)));
        pane.update(detail, files, notice);
        reviewPanes.put(key, pane);
        ops.addReviewTab(pane);
    }

    /** Drops a closed review tab from the map (called by {@code MainController} on tab disposal). */
    void onReviewPaneClosed(PrReviewPane pane) {
        reviewPanes.values().remove(pane);
    }

    // --- tool-window feeds (slice 2) -------------------------------------------------------------

    /**
     * Fetches pull requests for the tool window — the state, "mine" and row limit {@code query} asks for.
     * Exactly one of the two callbacks always runs: {@code onError} gets the reason when {@code gh} isn't
     * usable or the call failed (with gh's own message), so the window neither keeps its spinner nor passes
     * a failure off as "no open pull requests". The page says whether {@code gh} had more rows than shown.
     */
    void fetchPrs(
            GitHubListQuery query,
            Consumer<GitHubListQuery.Page<PrListParser.PullRequest>> onResult,
            Consumer<String> onError) {
        String refused = readyIn(null, dir -> {
            panelDir = dir;
            service.listPrs(dir, query, res -> {
                if (!res.ok()) {
                    listFailed(tr("status.github.prListFailed"), res.error(), onError);
                } else {
                    onResult.accept(query.page(res.prs()));
                }
            });
        });
        if (refused != null) {
            onError.accept(refused);
        }
    }

    /** Fetches issues for the tool window; see {@link #fetchPrs}. */
    void fetchIssues(
            GitHubListQuery query,
            Consumer<GitHubListQuery.Page<com.editora.github.IssueListParser.Issue>> onResult,
            Consumer<String> onError) {
        String refused = readyIn(null, dir -> {
            panelDir = dir;
            service.listIssues(dir, query, res -> {
                if (!res.ok()) {
                    listFailed(tr("status.github.issueListFailed"), res.error(), onError);
                } else {
                    onResult.accept(query.page(res.issues()));
                }
            });
        });
        if (refused != null) {
            onError.accept(refused);
        }
    }

    /** Fetches recent workflow runs for the tool window; see {@link #fetchPrs}. */
    void fetchRuns(
            GitHubListQuery query,
            Consumer<GitHubListQuery.Page<RunListParser.WorkflowRun>> onResult,
            Consumer<String> onError) {
        String refused = readyIn(null, dir -> {
            panelDir = dir;
            service.listRuns(dir, query, res -> {
                if (!res.ok()) {
                    // A repo with Actions disabled / no permission errors here — report, don't modal.
                    listFailed(tr("status.github.runListFailed"), res.error(), onError);
                } else {
                    onResult.accept(query.page(res.runs()));
                }
            });
        });
        if (refused != null) {
            onError.accept(refused);
        }
    }

    /** The repository {@code gh} resolved, per working directory — one {@code gh repo view} each. */
    private final java.util.Map<Path, com.editora.github.RepoViewParser.RepoInfo> repoInfos = new java.util.HashMap<>();

    /**
     * Tells {@code onName} which repository the tool window's rows belong to ({@code owner/name}; {@code ""}
     * while unknown). With a fork's {@code origin} and an {@code upstream} remote {@code gh} lists — and
     * reruns and cancels in — the upstream repository, so the window names it rather than leave it implied.
     */
    void resolvedRepository(Consumer<String> onName) {
        Path dir = panelDir;
        com.editora.github.RepoViewParser.RepoInfo known = dir == null ? null : repoInfos.get(dir);
        onName.accept(known == null ? "" : known.nameWithOwner());
        if (dir == null || known != null || !isEnabled() || !ready()) {
            return;
        }
        service.repoInfo(dir, info -> {
            if (info != null) {
                repoInfos.put(dir, info);
                if (dir.equals(panelDir)) {
                    onName.accept(info.nameWithOwner());
                }
            }
        });
    }

    private void listFailed(String summary, String detail, Consumer<String> onError) {
        String message = failureLine(summary, detail);
        host.setStatus(message);
        onError.accept(message);
    }

    /** {@code summary}, followed by the first non-blank line of {@code gh}'s own error text when there is one. */
    static String failureLine(String summary, String detail) {
        String first = detail == null
                ? ""
                : detail.lines()
                        .map(String::strip)
                        .filter(l -> !l.isEmpty())
                        .findFirst()
                        .orElse("");
        return first.isEmpty() ? summary : summary + ": " + first;
    }

    // --- CI failure log → the shared Output console -----------------------------------------

    /** Bumped per log request so a superseded (or Stopped) fetch is dropped instead of painting the console. */
    private long ciLogGen;

    /** The log fetch in flight: Stop, or asking for another log, kills its {@code gh} rather than abandon it. */
    private com.editora.process.ProcessRunner.Cancellation ciLogCall;

    private void cancelCiLogFetch() {
        ciLogGen++;
        if (ciLogCall != null) {
            ciLogCall.cancel();
            ciLogCall = null;
        }
    }

    /**
     * Dumps a failed run's log ({@code gh run view <id> --log-failed}) into the shared Output console's
     * CI tab, where {@code RunPanel.installLinkClicks} + {@code MainController.openRunLink} make its stack
     * frames clickable — the runner's paths resolve to local files via the pure {@code run/RunnerPaths}.
     */
    void viewRunLog(long runId, String workflowName) {
        viewRunLog(panelDir, runId, workflowName);
    }

    private void viewRunLog(Path listedIn, long runId, String workflowName) {
        readyIn(listedIn, dir -> {
            cancelCiLogFetch(); // a log still downloading is superseded: stop its gh
            long gen = ciLogGen;
            // Stop kills gh (a large log takes a while to download) and drops the pending delivery.
            ops.ciLogStarted(tr("github.ci.header", workflowName, runId), this::cancelCiLogFetch);
            ciLogCall = service.runFailedLog(dir, runId, res -> {
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
        long pick = ++pickGen;
        GitHubListQuery query = GitHubListQuery.open(GitHubListQuery.PICKER_LIMIT);
        ready(dir -> service.listRunsOnce(dir, query, res -> {
            if (pick != pickGen) {
                return; // a newer picker was asked for
            }
            if (!res.ok()) {
                ghError(tr("status.github.runListFailed"), res.error());
                return;
            }
            if (res.runs().isEmpty()) {
                host.setStatus(tr("status.github.noRuns"));
                return;
            }
            GitHubListQuery.Page<RunListParser.WorkflowRun> page = query.page(res.runs());
            if (page.more()) {
                host.setStatus(tr("status.github.pickerTruncated", query.limit()));
            }
            List<RunListParser.WorkflowRun> failed =
                    page.items().stream().filter(r -> r.state().failed()).toList();
            List<RunListParser.WorkflowRun> choices = failed.isEmpty() ? page.items() : failed;
            QuickOpen<RunListParser.WorkflowRun> picker = new QuickOpen<>(
                    tr("github.picker.runTitle"),
                    tr("github.picker.runPrompt"),
                    () -> choices,
                    r -> r.state().glyph() + "  " + r.workflowName() + "  " + r.displayTitle(),
                    r -> r.headBranch() + " · " + r.event(),
                    r -> r.workflowName() + " " + r.displayTitle() + " " + r.headBranch(),
                    r -> viewRunLog(dir, r.databaseId(), r.workflowName()));
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        }));
    }

    /** Re-runs a workflow run (optionally only its failed jobs), then refreshes the panel. */
    void rerunRun(long runId, boolean failedOnly) {
        readyIn(panelDir, dir -> {
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
        readyIn(panelDir, dir -> {
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
            host.setStatus(tr("status.github.noUrl"));
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

    /** The repository + branch a roll-up was last asked for — each is fetched once, then only polled. */
    private record ChecksKey(Path root, String branch) {}

    /** The key of the fetch in flight, or of the last one made. */
    private ChecksKey checksAsked;

    private boolean checksInFlight;
    /**
     * The last answer per repository + branch ({@link GitHubService.BranchChecks#NONE} for a branch without a
     * pull request), so coming back to a repository's tab shows its roll-up again without another {@code gh}
     * call. A branch switch inside a repository drops that repository's entries: that is fetched anew.
     */
    private final java.util.Map<ChecksKey, GitHubService.BranchChecks> checksCache = new java.util.HashMap<>();
    /** The roll-up on screen and the directory {@code gh} answered in ({@code null}: none shown). */
    private GitHubService.BranchChecks shownChecks;

    private Path shownChecksDir;
    /** Polls made since the last manual / automatic fetch (drives the back-off and the cap). */
    private int pollAttempt;

    private javafx.animation.PauseTransition pollTimer;
    /** A poll came due while the window was not focused; it runs when the focus returns. */
    private boolean pollDeferred;

    private boolean focusWatched;
    /** Test seam: the wait before a poll, in milliseconds, instead of {@link ChecksPoll#delaySeconds}. */
    long pollDelayMillisForTest = -1;
    /** Test seam: whether the window counts as focused, instead of asking the window (a headless stage never is). */
    java.util.function.BooleanSupplier windowFocusedForTest;

    private boolean windowFocused() {
        if (windowFocusedForTest != null) {
            return windowFocusedForTest.getAsBoolean();
        }
        javafx.stage.Window w = host.window();
        return w == null || w.isFocused();
    }

    /** The pull request the status-bar roll-up describes; 0 when none is shown (read by the status bar). */
    int checksPrNumber() {
        return shownChecks == null || shownChecks.pr() == null
                ? 0
                : shownChecks.pr().number();
    }

    private boolean checksAskedFor(Path root, String branch) {
        return new ChecksKey(root, branch).equals(checksAsked);
    }

    /**
     * Shows the roll-up of the repository + branch on screen whenever the always-on surfaces are available
     * for it — at startup, on opening a repository, on switching to a branch — so it no longer waits for a
     * manual {@code github.refresh}. Called from {@link #applyGating}, which runs on every tab switch: the
     * cache makes that one {@code gh} call per (repository, branch), and a pending roll-up whose polling was
     * stopped by leaving its tab is picked up again.
     */
    private void autoFetchChecks(boolean available) {
        if (!available || seenRoot == null || seenBranch.isEmpty() || GitService.isDetached(seenBranch)) {
            return;
        }
        ChecksKey key = new ChecksKey(seenRoot, seenBranch);
        GitHubService.BranchChecks cached = checksCache.get(key);
        if (cached == null) {
            if (!key.equals(checksAsked)) {
                refreshChecks(seenRoot);
            }
            return;
        }
        ChecksParser.Overall overall = showChecksResult(seenRoot, cached);
        boolean underWay = key.equals(checksAsked)
                && (checksInFlight
                        || pollDeferred
                        || (pollTimer != null && pollTimer.getStatus() == javafx.animation.Animation.Status.RUNNING));
        if (!underWay && ChecksPoll.after(overall, pollAttempt) == ChecksPoll.Next.WAIT) {
            fetchChecks(seenRoot);
        }
    }

    /**
     * Drops the roll-up on screen when its repository or branch is left (the caller clears the status bar).
     * {@code switchedIn} is the repository whose branch changed, or {@code null} for a change of repository.
     */
    private void forgetChecks(Path switchedIn) {
        stopPolling();
        checksInFlight = false;
        shownChecks = null;
        shownChecksDir = null;
        checksAsked = null;
        if (switchedIn != null) {
            checksCache.keySet().removeIf(k -> k.root().equals(switchedIn));
        }
    }

    private void stopPolling() {
        if (pollTimer != null) {
            pollTimer.stop();
        }
        pollDeferred = false;
    }

    /** Fetches the current branch's PR checks and pushes the roll-up to the status bar (null hides it). */
    private void refreshChecks(Path dir) {
        pollAttempt = 0;
        fetchChecks(dir);
    }

    private void fetchChecks(Path dir) {
        long gen = ++checksGen;
        stopPolling();
        checksInFlight = false;
        if (dir == null) {
            shownChecks = null;
            ops.setStatusBarChecks(null);
            return;
        }
        GitHubService.Availability gh = service.availability();
        if (gh != null && !gh.supportsChecks()) {
            // `gh pr checks --json` arrived in gh 2.50: an older gh is neither asked nor polled, and the
            // command that wanted the checks is told which gh it needs instead of "no checks".
            shownChecks = null;
            ops.setStatusBarChecks(null);
            if (checksAnnounce) {
                checksAnnounce = false;
                host.setStatus(tr(
                        "status.github.checksCannotUseGh",
                        com.editora.github.GhVersion.number(gh.version()),
                        com.editora.github.GhVersion.MINIMUM));
            }
            return;
        }
        checksWanted = true;
        ChecksKey key = new ChecksKey(dir, seenBranch);
        checksAsked = key;
        checksInFlight = true;
        service.branchChecks(dir, res -> {
            if (gen != checksGen) {
                return; // superseded, or the repository / branch changed while gh ran
            }
            checksInFlight = false;
            GitHubService.BranchChecks kept = res.ok() && !res.runs().isEmpty() ? res : GitHubService.BranchChecks.NONE;
            checksCache.put(key, kept);
            schedulePoll(dir, showChecksResult(dir, kept));
            if (checksAnnounce) {
                checksAnnounce = false;
                if (shownChecks == null) {
                    host.setStatus(tr("github.checks.none"));
                } else {
                    host.setStatus("");
                    showChecks();
                }
            }
        });
    }

    /** Puts {@code res} (or nothing, for a branch without a pull request / checks) in the status bar. */
    private ChecksParser.Overall showChecksResult(Path dir, GitHubService.BranchChecks res) {
        ChecksParser.ChecksSummary summary = res.ok() ? ChecksParser.ChecksSummary.of(res.runs()) : null;
        shownChecks = summary == null ? null : res;
        shownChecksDir = summary == null ? null : dir;
        ops.setStatusBarChecks(summary);
        if (checksCard != null) {
            checksCard.update(shownChecks);
        }
        return summary == null ? ChecksParser.Overall.NONE : summary.overall();
    }

    /**
     * A roll-up with a check still pending is asked for again — after 15 s, 30 s, 1 min, 2 min, then every
     * 5 min, {@link ChecksPoll#MAX_ATTEMPTS} times at most — so "○ Checks" turns into ✓ or ✗ on its own. A
     * poll that comes due while the window is not focused waits for the focus to return: nobody is looking,
     * and an editor left open overnight must not keep calling GitHub.
     */
    private void schedulePoll(Path dir, ChecksParser.Overall overall) {
        if (ChecksPoll.after(overall, pollAttempt) == ChecksPoll.Next.STOP) {
            return;
        }
        long millis =
                pollDelayMillisForTest >= 0 ? pollDelayMillisForTest : ChecksPoll.delaySeconds(pollAttempt) * 1000;
        long gen = checksGen;
        watchFocus();
        pollTimer = new javafx.animation.PauseTransition(javafx.util.Duration.millis(Math.max(1, millis)));
        pollTimer.setOnFinished(e -> {
            if (gen != checksGen || !isEnabled()) {
                return;
            }
            if (windowFocused()) {
                pollAttempt++;
                fetchChecks(dir);
            } else {
                pollDeferred = true;
            }
        });
        pollTimer.play();
    }

    private void watchFocus() {
        javafx.stage.Window w = host.window();
        if (focusWatched || w == null) {
            return;
        }
        focusWatched = true;
        w.focusedProperty().addListener((o, was, focused) -> {
            if (focused) {
                resumeDeferredPoll();
            }
        });
    }

    /** Runs the poll that came due while the window was unfocused (also the test's "focus returned"). */
    void resumeDeferredPoll() {
        if (pollDeferred && shownChecksDir != null && isEnabled()) {
            pollDeferred = false;
            pollAttempt++;
            fetchChecks(shownChecksDir);
        }
    }

    /** The open checks list ({@code null} when closed), kept so a poll updates it in place. */
    private ChecksListCard checksCard;

    /** {@code github.showChecks} was run with no roll-up on screen: report the answer of the fetch it started. */
    private boolean checksAnnounce;

    /**
     * Lists the checks behind the status-bar roll-up — name, state, a link to each on GitHub, and the failure
     * log for a failed Actions job — in a card above the segment (the {@code github.showChecks} command, and
     * the segment's click). With no roll-up on screen it asks for one instead.
     */
    void showChecks() {
        ifEnabled(() -> {
            if (shownChecks == null) {
                readyIn(null, dir -> {
                    host.setStatus(tr("status.github.checksLoading"));
                    checksAnnounce = true; // say what came back: the list, or that there is none
                    refreshChecks(dir);
                });
                return;
            }
            OverlayHost overlay = host.overlayHost();
            if (overlay == null) {
                return;
            }
            Path dir = shownChecksDir;
            ChecksListCard card = new ChecksListCard(new ChecksListCard.Actions() {
                @Override
                public void open(String url) {
                    openUrl(url);
                }

                @Override
                public void viewFailedLog(long runId, String name) {
                    overlay.hide();
                    viewRunLog(dir, runId, name);
                }

                @Override
                public void refresh() {
                    readyIn(dir, GitHubCoordinator.this::refreshChecks);
                }

                @Override
                public void close() {
                    overlay.hide();
                }
            });
            card.update(shownChecks);
            checksCard = card;
            javafx.scene.Scene scene =
                    host.window() == null ? null : host.window().getScene();
            javafx.scene.Node anchor = scene == null ? null : scene.lookup("#" + StatusBar.GITHUB_CHECKS_ID);
            Runnable hidden = () -> {
                if (checksCard == card) {
                    checksCard = null;
                }
            };
            if (anchor != null && anchor.isVisible()) {
                overlay.show(card, anchor, card::focusFirst, hidden);
            } else {
                overlay.show(card, true, card::focusFirst, hidden);
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
        if (fp.oldLines().isEmpty() && fp.newLines().isEmpty()) {
            // A pure rename, a mode-only change or a binary file: listed, but there is no text to compare.
            host.setStatus(tr("status.github.prFileNoText", com.editora.github.PrReviewSummary.displayPath(fp)));
            return;
        }
        // The patch-file path: every hunk keeps the line numbers its header states (the lines of separate
        // hunks are held back to back, so numbering them 1, 2, 3… made them read as one block at the top of
        // the file) and a side without a final newline is shown as such.
        diff.openPatchFileDiff(
                tr("diff.title.prFile", name, prNumber), tr("diff.side.prBase"), tr("diff.side.prHead"), name, fp);
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

    /** Bumped per create-PR request, so only the latest one's lookups open a form. */
    private long createGen;

    /** How many of the branch's commits the form's description lists. */
    private static final int MAX_DRAFT_COMMITS = 50;

    /**
     * Opens the create-PR form and runs {@code gh pr create}. Before the form: the branch must be one a pull
     * request can come from (not detached, not the repository's default branch), and a branch that already
     * has an open pull request is offered that pull request instead of a form {@code gh} would reject after
     * the user filled it in. The form then starts from the branch's commits and the repository's template.
     */
    void createPr() {
        ready(dir -> {
            Path root = git.repoRoot();
            String branch = git.branchName();
            if (root == null || branch.isBlank() || GitService.isDetached(branch)) {
                host.setStatus(tr("status.github.prNoBranch"));
                return;
            }
            long asked = ++createGen;
            host.setStatus(tr("status.github.preparingPr"));
            service.prCreateContext(dir, root, ctx -> {
                if (asked != createGen || !root.equals(git.repoRoot()) || !branch.equals(git.branchName())) {
                    return; // asked again, or the repository / branch changed while gh ran
                }
                String defaultBranch = ctx.repo() == null ? "" : ctx.repo().defaultBranch();
                if (PrDraft.onDefaultBranch(branch, defaultBranch)) {
                    host.setStatus(tr("status.github.prOnDefaultBranch", branch));
                    return;
                }
                PrViewParser.PrDetail existing = ctx.existing();
                if (existing != null && existing.number() > 0 && "OPEN".equalsIgnoreCase(existing.state())) {
                    offerExistingPr(dir, existing);
                    return;
                }
                git.service().branches(root, branches -> {
                    GitService.BranchInfo current = branches.local().stream()
                            .filter(b -> b.name().equals(branch))
                            .findFirst()
                            .orElse(new GitService.BranchInfo(branch, "", 0, 0, false));
                    List<String> locals = branches.local().stream()
                            .map(GitService.BranchInfo::name)
                            .toList();
                    String baseRef = PrDraft.baseRef(defaultBranch, branches.remote(), locals, current.upstream());
                    git.service().commitMessagesSince(root, baseRef, MAX_DRAFT_COMMITS, commits -> {
                        if (asked != createGen) {
                            return;
                        }
                        host.setStatus("");
                        List<PrDraft.CommitMessage> messages = commits.stream()
                                .map(c -> new PrDraft.CommitMessage(c[0], c[1]))
                                .toList();
                        showCreateForm(
                                dir,
                                current,
                                defaultBranch,
                                PrDraft.baseChoices(branches.remote(), defaultBranch),
                                PrDraft.of(branch, messages, ctx.template()));
                    });
                });
            });
        });
    }

    /** A branch that already has an open pull request: say so and offer to open it (its review tab). */
    private void offerExistingPr(Path dir, PrViewParser.PrDetail pr) {
        host.setStatus(tr("status.github.prExists", pr.number()));
        Label message = new Label(tr("dialog.createPr.exists", pr.number(), pr.title()));
        message.setWrapText(true);
        message.setMaxWidth(420);
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.createPr.title"),
                message,
                null,
                tr("dialog.createPr.openExisting"),
                null,
                () -> doPrDiff(dir, pr.number()),
                null,
                false);
    }

    private void showCreateForm(
            Path dir, GitService.BranchInfo head, String defaultBranch, List<String> bases, PrDraft.Draft draft) {
        KeymapManager keymap = ops.keymap();
        TextField titleField = new TextField(draft.title());
        titleField.setPromptText(tr("dialog.createPr.titlePrompt"));
        titleField.setPrefColumnCount(34);
        TextInputKeymap.install(titleField, keymap);
        TextArea bodyField = new TextArea(draft.body());
        bodyField.setPromptText(tr("dialog.createPr.bodyPrompt"));
        bodyField.setPrefRowCount(7);
        bodyField.setWrapText(true);
        TextInputKeymap.install(bodyField, keymap);
        // The remote's branches, the default one first and selected; editable, so a base that is not
        // fetched yet can still be typed. Blank ⇒ gh uses the repository's default branch.
        javafx.scene.control.ComboBox<String> baseBox = new javafx.scene.control.ComboBox<>();
        baseBox.getItems().setAll(bases);
        baseBox.setEditable(true);
        baseBox.setMaxWidth(Double.MAX_VALUE);
        baseBox.getEditor().setPromptText(tr("dialog.createPr.basePrompt"));
        baseBox.getEditor().setText(defaultBranch);
        TextInputKeymap.install(baseBox.getEditor(), keymap);
        Label headLabel = new Label(head.name());
        headLabel.getStyleClass().add("git-log-hash");
        // Comma-separated; parsed + normalized by the pure PrCreateArgs.
        TextField reviewersField = new TextField();
        reviewersField.setPromptText(tr("dialog.createPr.reviewersPrompt"));
        TextInputKeymap.install(reviewersField, keymap);
        TextField assigneesField = new TextField();
        assigneesField.setPromptText(tr("dialog.createPr.assigneesPrompt"));
        TextInputKeymap.install(assigneesField, keymap);
        TextField labelsField = new TextField();
        labelsField.setPromptText(tr("dialog.createPr.labelsPrompt"));
        TextInputKeymap.install(labelsField, keymap);

        CheckBox draftBox = new CheckBox(tr("dialog.createPr.draft"));
        // A branch with no upstream isn't on the remote yet, and one that is ahead of its upstream would
        // open a pull request without the newest commits; `gh pr create` cannot ask where to push (it runs
        // with prompting disabled and stdin closed). Offer the push here.
        boolean unpushed = PrDraft.needsPush(head.upstream(), head.ahead());
        CheckBox push = new CheckBox(tr("dialog.createPr.push", head.name()));
        push.setSelected(true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(new Label(tr("dialog.createPr.titleLabel")), 0, 0);
        grid.add(titleField, 1, 0);
        grid.add(new Label(tr("dialog.createPr.bodyLabel")), 0, 1);
        grid.add(bodyField, 1, 1);
        grid.add(new Label(tr("dialog.createPr.headLabel")), 0, 2);
        grid.add(headLabel, 1, 2);
        grid.add(new Label(tr("dialog.createPr.baseLabel")), 0, 3);
        grid.add(baseBox, 1, 3);
        grid.add(new Label(tr("dialog.createPr.reviewersLabel")), 0, 4);
        grid.add(reviewersField, 1, 4);
        grid.add(new Label(tr("dialog.createPr.assigneesLabel")), 0, 5);
        grid.add(assigneesField, 1, 5);
        grid.add(new Label(tr("dialog.createPr.labelsLabel")), 0, 6);
        grid.add(labelsField, 1, 6);
        grid.add(draftBox, 1, 7);
        if (unpushed) {
            grid.add(push, 1, 8);
        }
        GridPane.setHgrow(titleField, Priority.ALWAYS);
        GridPane.setHgrow(bodyField, Priority.ALWAYS);
        GridPane.setHgrow(baseBox, Priority.ALWAYS);
        GridPane.setHgrow(reviewersField, Priority.ALWAYS);
        GridPane.setHgrow(assigneesField, Priority.ALWAYS);
        GridPane.setHgrow(labelsField, Priority.ALWAYS);

        BooleanProperty valid = new SimpleBooleanProperty(!titleField.getText().isBlank());
        titleField.textProperty().addListener((o, a, b) -> valid.set(!b.isBlank()));

        // The form stays open while the push and gh run: a failure leaves the title and description in
        // place with the reason, instead of discarding them with the card.
        OverlayInput.showSubmitting(
                host.overlayHost(),
                tr("dialog.createPr.title"),
                grid,
                titleField,
                tr("dialog.createPr.button"),
                valid,
                submission -> {
                    List<String> args = PrCreateArgs.build(
                            titleField.getText(),
                            bodyField.getText(),
                            baseBox.getEditor().getText(),
                            draftBox.isSelected(),
                            reviewersField.getText(),
                            assigneesField.getText(),
                            labelsField.getText());
                    if (unpushed && push.isSelected()) {
                        host.setStatus(tr("status.github.pushingBranch", head.name()));
                        git.pushCurrentBranch(r -> {
                            if (r.ok()) {
                                runPrCreate(dir, args, submission);
                            } else if (r.cancelled()) {
                                submission.failed("");
                            } else {
                                formFailed(submission, tr("status.github.pushFailed"), r.message());
                            }
                        });
                    } else {
                        runPrCreate(dir, args, submission);
                    }
                },
                true);
    }

    /** Runs {@code gh pr create} with the form's argv and opens the created PR; the form waits for the result. */
    private void runPrCreate(Path dir, List<String> args, OverlayInput.Submission submission) {
        host.setStatus(tr("status.github.creatingPr"));
        service.prCreate(dir, args, r -> {
            String url = r.out() == null ? "" : r.out().strip();
            if (r.ok()) {
                submission.done();
                host.setStatus(tr("status.github.createdPr"));
                // The new PR belongs in the tool window's list — it's the surface the button was clicked from.
                ops.reloadGitHubPanel();
                if (!url.isEmpty()) {
                    host.openExternalUrl(lastUrl(url));
                }
            } else {
                formFailed(submission, tr("status.github.createFailed"), r.message());
            }
        });
    }

    /**
     * A form's action failed: the reason goes into the still-open form (and the status bar) rather than into
     * a modal dialog on top of it. The whole of {@code gh}'s output is in the Output console's GitHub tab.
     */
    private void formFailed(OverlayInput.Submission submission, String summary, String detail) {
        host.setStatus(summary);
        String text = detail == null
                ? ""
                : String.join("\n", detail.strip().lines().limit(6).toList());
        submission.failed(text.isEmpty() ? summary : summary + "\n" + text);
    }

    // --- submit review (approve / request changes / comment) -------------------------------------

    /** Picks a PR then opens the submit-review form (the {@code github.submitReview} palette command). */
    void submitReviewPicked() {
        ready(dir -> pickPr(tr("github.picker.reviewTitle"), pr -> submitReviewForm(dir, pr.number())));
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

        OverlayInput.showSubmitting(
                host.overlayHost(),
                tr("dialog.review.title", number),
                grid,
                body,
                tr("dialog.review.button"),
                valid,
                submission -> {
                    List<String> args = PrReviewArgs.build(number, actionBox.getValue(), body.getText());
                    host.setStatus(tr("status.github.reviewing", number));
                    service.prReview(dir, args, r -> {
                        if (r.ok()) {
                            submission.done();
                            host.setStatus(tr("status.github.reviewed", number));
                        } else {
                            formFailed(submission, tr("status.github.reviewFailed"), r.message());
                        }
                    });
                },
                true);
    }

    // --- shared helpers --------------------------------------------------------------------------

    /** Fetches open PRs and shows a picker; reports the failure / empty cases distinctly. */
    private void pickPr(String title, Consumer<PrListParser.PullRequest> onPick) {
        host.setStatus(tr("status.github.loadingPrs"));
        long pick = ++pickGen;
        GitHubListQuery query = GitHubListQuery.open(GitHubListQuery.PICKER_LIMIT);
        service.listPrsOnce(contextDir(), query, res -> {
            if (pick != pickGen) {
                return; // a newer picker was asked for
            }
            if (!res.ok()) {
                ghError(tr("status.github.prListFailed"), res.error());
                return;
            }
            if (res.prs().isEmpty()) {
                host.setStatus(tr("status.github.noPrs"));
                return;
            }
            GitHubListQuery.Page<PrListParser.PullRequest> page = query.page(res.prs());
            // The picker has no "load more" row: say so when gh had more than it lists.
            host.setStatus(page.more() ? tr("status.github.pickerTruncated", query.limit()) : "");
            QuickOpen<PrListParser.PullRequest> picker = new QuickOpen<>(
                    title,
                    tr("github.picker.prPrompt"),
                    page::items,
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
        activityRetry.stop();
        stopPolling(); // G12: no checks poll may fire for a closed window
        service.shutdown();
    }
}
