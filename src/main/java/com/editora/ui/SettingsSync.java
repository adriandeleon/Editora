package com.editora.ui;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;

import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.git.QuietGit;
import com.editora.sync.FileSyncTarget;
import com.editora.sync.SyncCategory;
import com.editora.sync.SyncEngine;
import com.editora.sync.SyncReport;

import static com.editora.i18n.Messages.tr;

/**
 * Settings sync for the whole application: decides when a {@link SyncEngine} run happens, runs it off the FX
 * thread, writes what it brings into the live stores of every window, and says what happened.
 *
 * <p>One instance, owned by {@link WindowManager}; every method is called on the FX thread. Only the primary
 * Editora process on a config directory syncs — a second one works from its own in-memory copy of the stores
 * and would push that.
 *
 * <p>Automatic runs (after startup, {@value #DIRTY_DELAY_SECONDS} s after a local change, and on the interval
 * timer) never ask for anything: git runs without a terminal, an askpass program or a credential window, and
 * a failure is one status line and a status-bar marker, not a dialog. A run the user asked for may use their
 * credential helper.
 */
final class SettingsSync {

    static final int DIRTY_DELAY_SECONDS = 30;
    private static final int STARTUP_DELAY_SECONDS = 5;

    enum Phase {
        /** Not connected (or this is not the primary instance). */
        OFF,
        /** Connected; the last run, if any, went well. */
        IDLE,
        SYNCING,
        /** The last run failed or stopped for a decision. */
        PROBLEM
    }

    /**
     * @param report the last finished run, or null
     * @param at when it finished, or null
     */
    record State(Phase phase, SyncReport report, LocalTime at) {}

    private final SharedConfig shared;
    private final WindowManager windows;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "settings-sync");
        t.setDaemon(true);
        return t;
    });
    private final PauseTransition dirty = new PauseTransition(Duration.seconds(DIRTY_DELAY_SECONDS));
    private final PauseTransition startup = new PauseTransition(Duration.seconds(STARTUP_DELAY_SECONDS));
    private final List<Runnable> listeners = new ArrayList<>();
    private Timeline timer;
    /** The interval {@link #timer} runs at; 0 when it is not running. */
    private int timerMinutes;

    private State state = new State(Phase.OFF, null, null);
    private boolean running;
    private boolean again;
    /** True while a run's result is being written into the stores: those writes are not "the user changed something". */
    private boolean applying;
    /** The failure already reported, so an automatic run that fails the same way every interval says it once. */
    private SyncReport.Status reportedFailure;

    private boolean startupDone;
    /** This computer's name for commit messages; looked up off the FX thread (it can be a DNS query). */
    private volatile String machine = "";

    SettingsSync(SharedConfig shared, WindowManager windows) {
        this.shared = shared;
        this.windows = windows;
        dirty.setOnFinished(e -> request(false, false));
        startup.setOnFinished(e -> request(false, false));
        worker.execute(() -> machine = machineName());
        settingsChanged();
    }

    // --- state ---------------------------------------------------------------------------------------------

    State state() {
        return state;
    }

    /** Runs {@code listener} on the FX thread after every state change. */
    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Whether this process may sync at all. */
    boolean primaryInstance() {
        return shared.isPrimaryInstance();
    }

    /** Connected: switched on, a usable repository, and the primary instance. */
    boolean active() {
        Settings s = shared.getSettings();
        return s.isSyncEnabled() && SyncEngine.isUsableUrl(s.getSyncRepoUrl()) && shared.isPrimaryInstance();
    }

    /** The folder that holds the clone and the backups. */
    Path syncDir() {
        return shared.getConfigDir().resolve("sync");
    }

    private void setState(State next) {
        state = next;
        windows.showSyncProblem(next.phase() == Phase.PROBLEM);
        for (Runnable listener : new ArrayList<>(listeners)) {
            listener.run();
        }
    }

    // --- triggers ------------------------------------------------------------------------------------------

    /** Re-reads the sync settings: starts or stops the timers. Call after any {@code sync*} setting changed. */
    void settingsChanged() {
        Settings s = shared.getSettings();
        boolean auto = active() && s.isSyncAuto();
        int minutes = auto ? s.getSyncIntervalMinutes() : 0;
        if (minutes != timerMinutes) { // an unrelated settings change must not push the next sync back
            timerMinutes = minutes;
            if (timer != null) {
                timer.stop();
                timer = null;
            }
            if (auto) {
                timer = new Timeline(new KeyFrame(Duration.minutes(minutes), e -> tick()));
                timer.setCycleCount(Animation.INDEFINITE);
                timer.play();
            }
        }
        if (auto) {
            if (!startupDone) {
                startupDone = true;
                startup.playFromStart();
            }
        } else {
            dirty.stop();
            startup.stop();
        }
        if (!active()) {
            reportedFailure = null;
            setState(new State(Phase.OFF, null, null));
        } else if (state.phase() == Phase.OFF) {
            setState(new State(Phase.IDLE, null, null));
        }
    }

    private void tick() {
        // Like the automatic fetch: an editor nobody is looking at does not talk to the network.
        if (windows.anyWindowFocused()) {
            request(false, false);
        }
    }

    /** The user changed a snippet, an abbreviation, a template or a dictionary word: sync soon. */
    void markDirty() {
        if (!applying && active() && shared.getSettings().isSyncAuto()) {
            dirty.playFromStart();
        }
    }

    /** "Sync Now". */
    void syncNow() {
        if (!active()) {
            windows.syncStatus(
                    tr(shared.isPrimaryInstance() ? "status.sync.notSetUp" : "status.sync.secondaryInstance"), false);
            return;
        }
        request(true, false);
    }

    /** Repeats a run that stopped at {@link SyncReport.Status#NEEDS_CONFIRMATION}, allowing the removal. */
    void syncAllowingLargeRemoval() {
        if (active()) {
            request(true, true);
        }
    }

    private Set<SyncCategory> categories() {
        Settings s = shared.getSettings();
        Set<SyncCategory> out = EnumSet.noneOf(SyncCategory.class);
        if (s.isSyncSnippets()) {
            out.add(SyncCategory.SNIPPETS);
        }
        if (s.isSyncAbbreviations()) {
            out.add(SyncCategory.ABBREVIATIONS);
        }
        if (s.isSyncTemplates()) {
            out.add(SyncCategory.TEMPLATES);
        }
        if (s.isSyncDictionary()) {
            out.add(SyncCategory.DICTIONARY);
        }
        return out;
    }

    private SyncEngine engine(String url, String branch, boolean interactive) {
        QuietGit git = new QuietGit(syncDir().resolve("repo"), interactive);
        return new SyncEngine(git, url, branch, new LiveTarget(), machine);
    }

    private void request(boolean userAsked, boolean allowLargeRemoval) {
        if (!active()) {
            return;
        }
        if (running) {
            again = true;
            return;
        }
        running = true;
        dirty.stop();
        setState(new State(Phase.SYNCING, state.report(), state.at()));
        Settings s = shared.getSettings();
        SyncEngine engine = engine(s.getSyncRepoUrl(), s.getSyncBranch(), userAsked);
        Set<SyncCategory> categories = categories();
        BackgroundTasks.Handle task = windows.startSyncTask(tr("sync.task"));
        worker.execute(() -> {
            SyncReport report = engine.run(categories, allowLargeRemoval);
            Platform.runLater(() -> finished(report, userAsked, task));
        });
    }

    private void finished(SyncReport report, boolean userAsked, BackgroundTasks.Handle task) {
        running = false;
        if (task != null) {
            task.done();
        }
        if (!active()) { // disconnected while it ran
            again = false;
            setState(new State(Phase.OFF, null, null));
            return;
        }
        // "The files changed while I was syncing" is not a problem to show: the change itself scheduled a run.
        boolean busy = report.status() == SyncReport.Status.LOCAL_BUSY;
        boolean problem = !report.ok() && !busy;
        setState(new State(problem ? Phase.PROBLEM : Phase.IDLE, report, LocalTime.now()));
        announce(report, userAsked);
        if (again || busy) {
            again = false;
            dirty.playFromStart();
        }
    }

    private void announce(SyncReport report, boolean userAsked) {
        if (report.ok()) {
            reportedFailure = null;
            if (userAsked || report.changedAnything() || !report.skipped().isEmpty()) {
                windows.syncStatus(tr("status.sync.done", summary(report)), false);
            }
            return;
        }
        if (report.status() == SyncReport.Status.LOCAL_BUSY) {
            return;
        }
        if (userAsked || reportedFailure != report.status()) {
            windows.syncStatus(problemText(report), true);
        }
        reportedFailure = report.status();
    }

    // --- connecting ----------------------------------------------------------------------------------------

    /**
     * Looks at what connecting to {@code url} would do — the repository is fetched, nothing is written — and
     * hands the result to {@code onPreview} on the FX thread. The user is waiting, so git may ask their
     * credential helper.
     */
    void preview(String url, String branch, Consumer<SyncReport> onPreview) {
        SyncEngine engine = engine(url, branch, true);
        Set<SyncCategory> categories = categories();
        BackgroundTasks.Handle task = windows.startSyncTask(tr("sync.task"));
        Path configDir = shared.getConfigDir();
        worker.execute(() -> {
            SyncReport report = insideAnotherRepository(configDir)
                    ? new SyncReport(
                            SyncReport.Status.FAILED,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            tr("status.sync.insideRepository", configDir))
                    : engine.preview(categories);
            Platform.runLater(() -> {
                if (task != null) {
                    task.done();
                }
                onPreview.accept(report);
            });
        });
    }

    /**
     * Whether the config directory is tracked by a repository of the user's (a dotfiles setup) that does not
     * ignore {@code sync/}: the clone would then sit inside that repository's work tree as an embedded one.
     */
    static boolean insideAnotherRepository(Path configDir) {
        if (!Files.isDirectory(configDir) || !QuietGit.available()) {
            return false;
        }
        QuietGit git = new QuietGit(configDir, false);
        return git.run("rev-parse", "--show-toplevel").ok()
                && !git.run("check-ignore", "--quiet", "sync/repo").ok();
    }

    /** Sync was just switched on in the settings: runs the first sync. */
    void connected() {
        startupDone = true; // the run below is the startup run
        settingsChanged();
        request(true, false);
    }

    /**
     * Sync was just switched off in the settings: forgets the clone. The data on this computer and in the
     * repository stays; connecting again starts as a first sync, which keeps everything on both sides.
     */
    void disconnected() {
        settingsChanged();
        Path repo = syncDir().resolve("repo");
        worker.execute(() -> {
            try {
                FileSyncTarget.deleteTree(repo);
            } catch (IOException ignored) {
                // a leftover clone is reused or replaced by the next connect
            }
        });
    }

    // --- writing a run's result into the live stores -------------------------------------------------------

    /** The config directory's files, written on the FX thread so no store is saving at the same moment. */
    private final class LiveTarget extends FileSyncTarget {

        LiveTarget() {
            super(shared.getConfigDir());
        }

        @Override
        public boolean apply(Map<String, String> expected, Map<String, String> changes) throws IOException {
            CompletableFuture<Boolean> done = new CompletableFuture<>();
            Platform.runLater(() -> {
                applying = true;
                try {
                    boolean written = writeFiles(expected, changes);
                    if (written) {
                        reload(changes.keySet());
                    }
                    done.complete(written);
                } catch (IOException | RuntimeException e) {
                    done.completeExceptionally(e);
                } finally {
                    applying = false;
                }
            });
            try {
                return done.get(1, TimeUnit.MINUTES);
            } catch (ExecutionException e) {
                throw new IOException(e.getCause());
            } catch (TimeoutException e) {
                throw new IOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }

        private boolean writeFiles(Map<String, String> expected, Map<String, String> changes) throws IOException {
            return super.apply(expected, changes);
        }
    }

    /** Makes every window show the files a run just wrote. */
    private void reload(Set<String> paths) {
        Set<SyncCategory> changed = EnumSet.noneOf(SyncCategory.class);
        for (String path : paths) {
            SyncCategory category = SyncCategory.of(path);
            if (category != null) {
                changed.add(category);
            }
        }
        if (changed.contains(SyncCategory.SNIPPETS)) {
            windows.broadcastSnippetsChanged(null);
        }
        if (changed.contains(SyncCategory.DICTIONARY) && shared.reloadUserDictionary()) {
            windows.broadcastUserDictionaryChanged();
        }
        if (changed.contains(SyncCategory.ABBREVIATIONS)) {
            shared.reloadAbbreviations();
            windows.broadcastSettingsApplied(); // each buffer holds its own copy of the abbreviation map
        }
        if (changed.contains(SyncCategory.SNIPPETS) || changed.contains(SyncCategory.TEMPLATES)) {
            windows.refreshFileBackedSettings();
        }
    }

    // --- words ---------------------------------------------------------------------------------------------

    /** "3 received, 1 sent" and, when there are any, the conflicts and the files left alone. Pure. */
    static String summary(SyncReport report) {
        StringBuilder sb = new StringBuilder();
        sb.append(
                report.changedAnything()
                        ? tr(
                                "sync.result.counts",
                                report.received().size(),
                                report.sent().size())
                        : tr("sync.result.upToDate"));
        if (!report.conflicts().isEmpty()) {
            sb.append(". ").append(tr("sync.result.conflicts", labels(report.conflicts())));
        }
        if (!report.skipped().isEmpty()) {
            sb.append(". ")
                    .append(tr(
                            "sync.result.skipped",
                            report.skipped().stream()
                                    .map(SyncReport.Skipped::path)
                                    .collect(Collectors.joining(", "))));
        }
        return sb.toString();
    }

    private static String labels(List<SyncReport.Change> changes) {
        int shown = Math.min(5, changes.size());
        String text =
                changes.subList(0, shown).stream().map(SyncReport.Change::label).collect(Collectors.joining(", "));
        return shown < changes.size() ? text + ", …" : text;
    }

    /** One line saying why a run did not finish and, where there is one, the next step. Pure. */
    static String problemText(SyncReport report) {
        return switch (report.status()) {
            case NO_GIT -> tr("status.sync.noGit");
            case FETCH_FAILED -> tr("status.sync.fetchFailed", GitAuthFailure.withGuidance(report.detail()));
            case PUSH_FAILED -> tr("status.sync.pushFailed", GitAuthFailure.withGuidance(report.detail()));
            case NEWER_FORMAT -> tr("status.sync.newerFormat");
            case NEEDS_CONFIRMATION -> tr("status.sync.needsConfirmation", categoryName(report.detail()));
            default -> tr("status.sync.failed", report.detail());
        };
    }

    /** A category as the Settings page names it; {@code name} is a {@link SyncCategory} constant's name. */
    static String categoryName(String name) {
        try {
            return switch (SyncCategory.valueOf(name)) {
                case SNIPPETS -> tr("settings.sync.snippets");
                case ABBREVIATIONS -> tr("settings.sync.abbreviations");
                case TEMPLATES -> tr("settings.sync.templates");
                case DICTIONARY -> tr("settings.sync.dictionary");
            };
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    /** What the Settings page shows next to "Status". */
    String stateText() {
        if (!shared.isPrimaryInstance()) {
            return tr("status.sync.secondaryInstance");
        }
        SyncReport report = state.report();
        return switch (state.phase()) {
            case OFF -> tr("sync.state.off");
            case SYNCING -> tr("sync.state.syncing");
            case PROBLEM -> problemText(report);
            case IDLE ->
                report == null || !report.ok()
                        ? tr("sync.state.never")
                        : tr(
                                "sync.state.ok",
                                state.at().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)),
                                summary(report));
        };
    }

    private static String machineName() {
        String name = System.getenv("COMPUTERNAME");
        if (name == null || name.isBlank()) {
            name = System.getenv("HOSTNAME");
        }
        if (name == null || name.isBlank()) {
            try {
                name = InetAddress.getLocalHost().getHostName();
            } catch (IOException | RuntimeException e) {
                name = "";
            }
        }
        return name;
    }
}
