package com.editora.ui;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import com.editora.process.ProcessRunner;

import static com.editora.i18n.Messages.tr;

/**
 * "Fetch automatically": every few minutes, a background {@code git fetch --prune} of the active repository,
 * so the ahead/behind counts and the remote branches are current without the user asking. Off by default.
 *
 * <p>It is the one network command Editora starts on its own, so it is held to the rules of a background
 * command (ADR 0002): the hardened argv, no prompt of any kind, nothing in the Git console, silence on
 * failure apart from one quiet hint per repository. And because a fetch runs transport programs named by the
 * repository's own configuration — exactly what the ADR warns about — it only ever runs in a repository the
 * user has vouched for: a <b>trusted folder</b>, or one where they have themselves run a fetch, pull or push
 * in this session. Anywhere else it does nothing.
 *
 * <p>It also stays out of the way: not while a user network command is running or queued (and it is stopped
 * when one starts), and not for a window that has been in the background for longer than one interval.
 */
final class GitAutoFetch {

    private final CoordinatorHost host;
    private final GitCoordinator git;

    private Timeline timer;
    private Duration period;
    /** Folders the user trusts (the workspace-trust store); the registrar installs it. */
    private Predicate<Path> trusted = root -> false;
    /** Whether this window was recently in use; replaceable so a headless test need not own the focus. */
    private BooleanSupplier recentlyActive = this::windowRecentlyFocused;
    /** Overrides the interval from the settings (tests). */
    private Duration periodOverride;
    /** Repositories whose failure has already been mentioned: once per session is enough. */
    private final Set<Path> failureMentioned = new HashSet<>();
    /** When the window was last seen focused ({@link System#nanoTime()}). */
    private long lastFocusedNanos = System.nanoTime();
    /** Fetches started, for tests. */
    private int runs;

    GitAutoFetch(CoordinatorHost host, GitCoordinator git) {
        this.host = host;
        this.git = git;
    }

    void setTrust(Predicate<Path> trustedFolder) {
        trusted = trustedFolder == null ? root -> false : trustedFolder;
    }

    void setRecentlyActiveForTest(BooleanSupplier active) {
        recentlyActive = active;
    }

    void setPeriodForTest(Duration testPeriod) {
        periodOverride = testPeriod;
        period = null;
        apply();
    }

    int runsForTest() {
        return runs;
    }

    /** The interval between fetches: the setting, never under a minute. Pure. */
    static int minutes(int configured) {
        return Math.max(1, configured);
    }

    /** Starts, stops or re-times the timer from the settings; called whenever they may have changed. */
    void apply() {
        boolean on = git.isEnabled() && host.settings().isGitAutoFetch();
        if (!on) {
            stop();
            return;
        }
        Duration wanted = periodOverride != null
                ? periodOverride
                : Duration.minutes(minutes(host.settings().getGitAutoFetchMinutes()));
        if (timer != null && wanted.equals(period)) {
            return;
        }
        stop();
        period = wanted;
        timer = new Timeline(new KeyFrame(wanted, e -> tick()));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
    }

    void stop() {
        if (timer != null) {
            timer.stop();
            timer = null;
        }
    }

    /**
     * Whether an automatic fetch may run in {@code root}: the folder is trusted, or the user has run a
     * network command there themselves this session.
     */
    boolean allowedIn(Path root) {
        return root != null && (trusted.test(root) || git.service().userRanNetworkCommandIn(root));
    }

    private boolean windowRecentlyFocused() {
        javafx.stage.Window window = host.window();
        if (window != null && window.isFocused()) {
            lastFocusedNanos = System.nanoTime();
            return true;
        }
        double idle = (System.nanoTime() - lastFocusedNanos) / 1e6;
        return period != null && idle < period.toMillis();
    }

    private void tick() {
        Path root = git.repoRoot();
        if (!git.isAvailable()
                || !allowedIn(root)
                || !recentlyActive.getAsBoolean()
                || git.service().networkBusy()) {
            return;
        }
        runs++;
        git.service().autoFetch(root, result -> finished(root, result));
    }

    private void finished(Path root, ProcessRunner.Result result) {
        if (result == null || result.cancelled()) {
            return; // not run, or stopped to let a user command through
        }
        if (result.ok()) {
            failureMentioned.remove(root);
            if (root.equals(git.repoRoot())) {
                git.refresh(); // ahead/behind in the status bar and the Commit window
            }
            return;
        }
        if (failureMentioned.add(root)) {
            host.setStatus(tr("status.git.autoFetchQuiet"));
        }
    }
}
