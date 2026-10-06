package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import javafx.animation.PauseTransition;
import javafx.application.HostServices;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.config.WorkspaceState;

/**
 * Owns the set of top-level editor windows and the shared config behind them. In single-window terms
 * this replaces the per-window construction that used to live inline in {@code App.start}.
 *
 * <p>Multi-window behaviour is gated on the <em>Projects</em> feature: with projects disabled (the
 * default) the app opens exactly one global window, just like before. With projects enabled, each
 * project gets its own window — {@link #openOrFocus(Project)} focuses an already-open project window or
 * builds a new one — the no-project "global" session is itself a window, and the set of open windows is
 * restored on the next launch (persisted in {@code projects.json} as {@code openProjectIds}).
 */
public class WindowManager {

    /** The two paths requested by {@code editora --diff-ui LEFT RIGHT}. */
    public record DiffUiRequest(Path left, Path right) {
        public DiffUiRequest {
            java.util.Objects.requireNonNull(left, "left");
            java.util.Objects.requireNonNull(right, "right");
        }
    }

    private final SharedConfig shared;
    private final KeymapManager keymap; // shared, read-only across windows
    private final HostServices hostServices;
    /** Shared plugin manager: classes load once here; each window builds its own plugin instances/nodes. */
    private final com.editora.plugin.PluginManager pluginManager;

    private final List<Holder> windows = new ArrayList<>();
    /** The JavaFX primary stage, reused for the first window built (then null — others get a new Stage). */
    private Stage primaryStage;

    /** Set by {@code --single-window}: freezes the persisted open-window set this session (so quitting a
     *  one-window run never shrinks the saved multi-window layout). See {@link #reconcileOpenSet()}. */
    private boolean singleWindowSession;

    /**
     * Windows opened by an externally-delivered launch (a file-manager click routed here by
     * {@code ipc.SingleInstance}). They are live windows in every other respect, but are deliberately kept
     * out of the persisted restore set: before the single-instance handoff existed, such a launch was its own
     * {@code --single-window --no-session} process, which never touched the saved layout. Without this, every
     * file ever opened from the file manager would come back as a window on the next launch — and, since a
     * {@code --no-session} window never writes a session file, come back empty.
     */
    private final java.util.Set<String> transientWindows = new java.util.HashSet<>();

    /** Set by {@code --no-session}: open only the command line's files, skipping the saved session's.
     *  Session-only — the saved session is never rewritten from a {@code --no-session} run. */
    private boolean noSession;

    /**
     * Debounce for persisting the open-window restore set after a close. Each close calls {@code
     * playFromStart}, so a run of closes ending in the app quitting (an OS/Cmd-Q burst <em>or</em> the user
     * clicking each window's close button one after another) keeps resetting the timer; the last close
     * empties the live set and the JVM exits before it fires, so {@link #reconcileOpenSet()} never persists
     * a drained set and every window restores next launch. The delay must therefore comfortably exceed the
     * gap between successive manual window closes — 350 ms was shorter than a human's click-to-click cadence,
     * so closing two windows one at a time wrongly forgot the first. A genuine single close (the app keeps
     * running and the user works on past this delay) still persists the reduced set and forgets that window.
     */
    private final PauseTransition openSetReconcile = new PauseTransition(Duration.seconds(3));

    /**
     * Coalesces cross-window re-applies of a preference changed through one window's command (a palette
     * toggle, a key binding, a text zoom). The windows it reaches are not the one being used, so a short
     * delay is invisible there, and it turns a burst — a Ctrl+wheel zoom fires a save per pulse — into one
     * full re-apply per window instead of one per notch.
     */
    private final PauseTransition settingsRebroadcast = new PauseTransition(Duration.millis(200));
    /** True from a reported change until the windows it must reach have re-applied. */
    private boolean settingsChangePending;
    /**
     * The window that made the pending change; it applied the change itself and is skipped. Known only when
     * its own save carried the change with no other window's save waiting — see {@link #changerOf}.
     */
    private ConfigManager settingsChangeOrigin;
    /** Set when the pending changes came from different (or unknown) windows: then none can be skipped. */
    private boolean settingsChangeFromSeveral;

    /** True while a refresh of the windows' recent-files menus / search dropdowns is queued for this pulse. */
    private boolean sharedHistoryBroadcastQueued;

    /** A live window: its project key ({@code ""} = the global session), its stage, controller and config. */
    private record Holder(String key, Stage stage, MainController controller, ConfigManager config) {}

    public WindowManager(SharedConfig shared, KeymapManager keymap, HostServices hostServices) {
        this.shared = shared;
        this.keymap = keymap;
        this.hostServices = hostServices;
        // Make the (single, shared) keymap available to plain text fields and consoles, so they can install
        // the configured caret/editing chords without threading it through their constructors (see
        // TextInputKeymap). Done here, by the keymap's owner, so every window this manager builds — including
        // the headless test fixture's — gets them, not only one launched through App.
        com.editora.command.TextInputKeymap.setShared(keymap);
        // Discover plugins once (startup I/O + class loaders). The predicate factors the master gate, so
        // no untrusted code loads unless plugins are enabled; the Settings page still lists all of them.
        this.pluginManager = new com.editora.plugin.PluginManager(
                shared.getPluginsDir(),
                id -> shared.getSettings().isPluginSupport()
                        && shared.getPluginStore().isEnabled(id));
        this.pluginManager.discover();
        openSetReconcile.setOnFinished(e -> reconcileOpenSet());
        // Surface durable config-write failures (full disk, read-only ~/.editora) instead of silently
        // swallowing them — a setting/session change would otherwise vanish next launch with no sign (#418).
        shared.setOnWriteError((file, err) -> javafx.application.Platform.runLater(() -> notifyConfigWriteError(file)));
        // A second Editora process on this config dir (a launch that was not forwarded to the running editor)
        // works from its own in-memory copy of settings, notes, bookmarks, breakpoints, projects and recents.
        // Say so once, after the first window exists, instead of letting one editor silently undo the other.
        if (!shared.isPrimaryInstance()) {
            javafx.application.Platform.runLater(this::warnSecondaryInstance);
        }
        // Preferences are one object shared by every window, but each window applies them to its own buffers
        // and services. A save that carries a change no other window has applied yet re-applies it there.
        shared.setOnSettingsChanged(this::onSharedSettingsChanged);
        shared.setOnStoreChanged(this::onSharedStoreChanged);
        settingsRebroadcast.setOnFinished(e -> flushPendingSettingsBroadcast());
        // The recent-files and search-history lists are single shared instances; a change made through any
        // window refreshes what every window shows. Registered once here (not per window) so a closed window
        // is never kept alive by — or called back from — a list that outlives it.
        shared.recentFiles().getList().addListener((javafx.collections.ListChangeListener<Path>)
                c -> scheduleSharedHistoryBroadcast());
        shared.searchHistory().getList().addListener((javafx.collections.ListChangeListener<String>)
                c -> scheduleSharedHistoryBroadcast());
    }

    /** One-time notice, in the focused window, that another Editora process shares this configuration. Shown
     *  as an error so it stays flagged in the message log after routine startup messages replace the echo. */
    private void warnSecondaryInstance() {
        java.util.logging.Logger.getLogger(WindowManager.class.getName())
                .warning("Another Editora instance is already using " + shared.getConfigDir());
        Holder h = focusedHolder();
        if (h != null && h.controller() != null) {
            h.controller().setError(com.editora.i18n.Messages.tr("status.config.secondaryInstance"));
        }
    }

    /**
     * Shows, once, what the shared config could not read as written when it loaded: values that were reset,
     * files that fell back to defaults, and files that will not be saved this session. Reported as errors so
     * they stay flagged in the message log after routine startup messages replace the status line.
     */
    private void reportConfigLoadProblems(MainController controller) {
        List<com.editora.config.migration.ConfigLoadProblem> problems = shared.takeLoadProblems();
        if (problems.isEmpty() && unshownWriteErrors.isEmpty()) {
            return;
        }
        // Deferred past startup's own status messages, so the report is the line left showing.
        javafx.application.Platform.runLater(() -> {
            for (com.editora.config.migration.ConfigLoadProblem problem : problems) {
                controller.setError(ConfigLoadMessages.describe(problem, shared.isWriteProtected(problem.file())));
            }
            List<Path> failed = new ArrayList<>(unshownWriteErrors);
            unshownWriteErrors.clear();
            for (Path file : failed) {
                controller.setError(com.editora.i18n.Messages.tr(
                        "status.config.saveFailed", file.getFileName().toString()));
            }
        });
    }

    /**
     * Config files whose write failed before any window existed to say so (FX thread only). A read-only or
     * full config folder fails its first write while the config is still loading; the first window reports
     * these with the load problems.
     */
    private final java.util.Set<Path> unshownWriteErrors = new java.util.LinkedHashSet<>();

    /** Shows a config-write failure in the focused window's status bar (best-effort; logged regardless). */
    private void notifyConfigWriteError(Path file) {
        Holder h = focusedHolder();
        if (h != null && h.controller() != null) {
            // A lost config write is exactly the failure that used to vanish: it happens in the background,
            // and the next routine status message replaced it a moment later (#418, #770).
            h.controller()
                    .setError(com.editora.i18n.Messages.tr(
                            "status.config.saveFailed", file.getFileName().toString()));
        } else {
            unshownWriteErrors.add(file); // no window yet: the first one reports it
        }
    }

    /**
     * The abbreviations or the saved SFTP sites changed: every open Settings window re-reads them now. Each
     * edits them as a whole list, so one left showing the old list would write it back over the change.
     */
    private void onSharedStoreChanged() {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            javafx.application.Platform.runLater(this::onSharedStoreChanged);
            return;
        }
        for (Holder h : new ArrayList<>(windows)) {
            h.controller().settingsWindow().syncStoreBackedEditors();
        }
    }

    /** The shared plugin manager (also read by the Settings → Plugins page to list installed plugins). */
    public com.editora.plugin.PluginManager pluginManager() {
        return pluginManager;
    }

    private ProjectManager projects() {
        return shared.projects();
    }

    // --- startup ---

    /**
     * Opens the initial window(s) at launch. With projects disabled, opens one global window (carrying any
     * CLI targets). With projects enabled, restores every window that was open at last quit (plus a CLI
     * {@code --project}), routing the CLI targets to the primary window.
     */
    public void launch(
            Stage primaryStage,
            String projectArg,
            List<MainController.OpenTarget> targets,
            boolean zen,
            boolean expert,
            String newFile,
            boolean simple,
            String singleWindow,
            boolean noSession) {
        this.primaryStage = primaryStage; // reused for the first window built
        this.noSession = noSession;
        // --single-window[=name]: open exactly one window (the named project, else the no-project window)
        // instead of restoring the whole saved set. Session-only — we don't touch the persisted open set.
        if (singleWindow != null) {
            launchSingleWindow(singleWindow, targets, zen, expert, newFile, simple);
            return;
        }
        Settings settings = shared.getSettings();
        boolean projectsOn = settings.isProjectSupport();
        ProjectManager pm = projects();
        Project cli = null;
        if (projectsOn && projectArg != null) {
            Path root = Path.of(projectArg).toAbsolutePath().normalize();
            String name = root.getFileName() == null
                    ? root.toString()
                    : root.getFileName().toString();
            cli = pm.createOrGet(name, root);
            pm.save();
        }
        String cliId = cli == null ? null : cli.id();
        // The no-project windows (global "" + untitled "New Window"s) always restore; project windows only
        // when Projects are enabled. The restore set + primary-window choice are the pure, unit-tested
        // WindowKeys helpers (a quit-vs-close drain bug once lived in this logic).
        LinkedHashSet<String> toOpen = WindowKeys.restoreKeys(pm.openProjectIds(), projectsOn, cliId);
        String primary = WindowKeys.primaryKey(
                cliId, toOpen, pm.active() == null ? null : pm.active().id());

        // The window the user will be looking at is built first and alone: every window used to be built
        // in this one FX turn, so with several restored the first frame waited for all of them. The rest
        // follow one per painted frame (see buildNextRestoredWindow).
        List<String> order = new ArrayList<>(toOpen);
        if (order.remove(primary)) {
            order.add(0, primary);
        }
        for (String key : order) {
            boolean untitled = WindowKeys.isUntitled(key);
            Project project = (key.isEmpty() || untitled) ? null : findProject(key);
            if (!key.isEmpty() && !untitled && project == null) {
                continue; // a stale id (project deleted out from under the open set)
            }
            Path stateFile = untitled ? untitledStateFile(key) : (project == null ? null : pm.stateFile(project));
            pendingRestore.add(new PendingWindow(key, project, stateFile));
        }
        restoreFocusKey = primary;
        // Until one window is on screen: normally just the primary, the next one if it failed to build.
        while (windows.isEmpty() && !pendingRestore.isEmpty()) {
            PendingWindow next = pendingRestore.poll();
            boolean isPrimary = next.key().equals(primary);
            buildRestoredWindow(
                    next,
                    isPrimary ? targets : List.of(),
                    isPrimary && zen,
                    isPrimary && expert,
                    isPrimary ? newFile : null,
                    isPrimary && simple);
        }
        pm.save();
        // Against the whole restore set, and now rather than when the last window exists: a window the user
        // opens while the rest are still being built has a session file this set does not know about.
        gcOrphanWindowSessions(toOpen);
        if (pendingRestore.isEmpty()) {
            finishRestore();
        } else {
            focusKey(primary);
            restoreScheduler.accept(this::buildNextRestoredWindow);
        }
    }

    /**
     * The launch's bootstrap config: it already read {@code workspace-state.json} (off the FX thread, while
     * the toolkit started), so the default window built during this launch adopts it instead of parsing the
     * same file again on the FX thread. Only for the launch itself — a default window opened later must read
     * what is on disk then.
     */
    private ConfigManager bootstrapConfig;

    /** Offers {@code bootstrap} to the default window of the launch that follows. */
    public void adoptBootstrapConfig(ConfigManager bootstrap) {
        if (bootstrap != null && bootstrap.shared() == shared) {
            bootstrapConfig = bootstrap;
        }
    }

    private ConfigManager takeBootstrapConfig() {
        ConfigManager boot = bootstrapConfig;
        bootstrapConfig = null;
        return boot;
    }

    /** A restored window waiting for its turn to be built; see {@link #launch}. */
    private record PendingWindow(String key, Project project, Path stateFile) {}

    /** Restored windows not built yet, in build order. Their saved sessions are untouched until they are. */
    private final java.util.ArrayDeque<PendingWindow> pendingRestore = new java.util.ArrayDeque<>();

    /** External launches that arrived while windows were still pending; run once every window exists. */
    private final List<Runnable> deferredExternalLaunches = new ArrayList<>();

    /** The window that ends up focused once the restore completes. */
    private String restoreFocusKey;

    /** Set while a quit is asking its questions, so no window is built underneath the prompts. */
    private boolean restorePaused;

    /** How the next pending window's build is scheduled (a seam: tests step the queue by hand). */
    java.util.function.Consumer<Runnable> restoreScheduler = WindowSessionCoordinator::afterNextPaint;

    /** True while restored windows are still waiting to be built. */
    boolean restorePending() {
        return !pendingRestore.isEmpty();
    }

    private void buildRestoredWindow(
            PendingWindow w,
            List<MainController.OpenTarget> targets,
            boolean zen,
            boolean expert,
            String newFile,
            boolean simple) {
        try {
            buildWindow(w.key(), w.project(), w.stateFile(), targets, zen, expert, newFile, simple);
            projects().markOpen(w.key());
        } catch (RuntimeException | Error t) {
            // One window failing to build (corrupt session, etc.) must NOT abort restoring the rest —
            // launch runs in App.start with no catch, so an uncaught throw here would silently leave only
            // the windows built before it. Log and continue with the remaining windows.
            java.util.logging.Logger.getLogger(WindowManager.class.getName())
                    .log(java.util.logging.Level.WARNING, "Failed to build window for key '" + w.key() + "'", t);
        }
    }

    /**
     * Builds one pending restored window, then yields to the event loop until a frame has painted before the
     * next. Showing a window gives it the focus, so focus is handed back each time — to the window the user
     * has focused meanwhile, else to the one the restore is meant to end on.
     */
    private void buildNextRestoredWindow() {
        if (restorePaused || pendingRestore.isEmpty()) {
            return; // a cancelled quit reschedules; an emptied queue was finished by whoever emptied it
        }
        PendingWindow next = pendingRestore.poll();
        if (findHolder(next.key()) == null) { // else opened by hand while it was waiting
            Holder focused = actuallyFocusedHolder();
            buildRestoredWindow(next, List.of(), false, false, null, false);
            Holder back = focused != null ? focused : findHolder(restoreFocusKey);
            if (back != null) {
                focus(back.stage());
            }
        }
        if (pendingRestore.isEmpty()) {
            finishRestore();
        } else {
            restoreScheduler.accept(this::buildNextRestoredWindow);
        }
    }

    /** Runs what waits for the whole restore set to exist: the persisted open set, focus, queued launches. */
    private void finishRestore() {
        bootstrapConfig = null; // the launch is over; see the field
        if (findHolder(restoreFocusKey) == null && !windows.isEmpty()) {
            restoreFocusKey = windows.get(0).key(); // the chosen primary failed to build — focus whatever opened
        }
        projects().save();
        if (actuallyFocusedHolder() == null) {
            focusKey(restoreFocusKey);
        }
        List<Runnable> deferred = new ArrayList<>(deferredExternalLaunches);
        deferredExternalLaunches.clear();
        deferred.forEach(Runnable::run);
    }

    /** The window that really has the focus, or {@code null} — unlike {@link #focusedHolder()}, no fallback. */
    private Holder actuallyFocusedHolder() {
        for (Holder h : windows) {
            if (h.stage() != null && h.stage().isFocused()) {
                return h;
            }
        }
        return null;
    }

    /**
     * Opens one isolated, session-free window containing only a two-file diff. A fresh untitled-window key
     * keeps the normal no-project session (including its saved Zen/Expert state) out of this transient view.
     */
    public void launchDiffUi(Stage primaryStage, DiffUiRequest request) {
        this.primaryStage = primaryStage;
        this.noSession = true;
        this.singleWindowSession = true;
        String key = WindowKeys.UNTITLED_PREFIX + "diff-"
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        try {
            buildWindow(key, null, untitledStateFile(key), List.of(), false, false, null, false, request);
            transientWindows.add(key);
            focusKey(key);
        } catch (RuntimeException | Error t) {
            java.util.logging.Logger.getLogger(WindowManager.class.getName())
                    .log(java.util.logging.Level.WARNING, "Failed to build standalone diff window", t);
            throw t;
        }
    }

    /**
     * {@code --single-window[=name]}: open exactly one window and stop. {@code name} (blank ⇒ the no-project
     * window) selects the window: a matching project name opens that project's window, an unknown name (or
     * Projects disabled) falls back to the no-project window. Session-only — it deliberately does <b>not</b>
     * {@code markOpen}/{@code save}/GC the persisted open-window set (and {@link #reconcileOpenSet()} is
     * suppressed for the session), so quitting a one-window run never shrinks the saved multi-window layout.
     * The CLI targets / {@code --zen} / {@code --expert} / {@code --new-file} / {@code --simple} all apply to
     * this one window.
     */
    private void launchSingleWindow(
            String name,
            List<MainController.OpenTarget> targets,
            boolean zen,
            boolean expert,
            String newFile,
            boolean simple) {
        singleWindowSession = true;
        boolean projectsOn = shared.getSettings().isProjectSupport();
        String key = "";
        Project project = null;
        Path stateFile = null;
        if (projectsOn && name != null && !name.isBlank()) {
            project = findProjectByName(name.trim());
            if (project == null) {
                java.util.logging.Logger.getLogger(WindowManager.class.getName())
                        .warning("--single-window: no project named '" + name.trim()
                                + "'; opening the no-project window");
            } else {
                key = project.id();
                stateFile = projects().stateFile(project);
            }
        }
        try {
            buildWindow(key, project, stateFile, targets, zen, expert, newFile, simple);
        } catch (RuntimeException | Error t) {
            java.util.logging.Logger.getLogger(WindowManager.class.getName())
                    .log(java.util.logging.Level.WARNING, "Failed to build single window for key '" + key + "'", t);
        }
        focusKey(key);
        bootstrapConfig = null; // the launch is over; see the field
    }

    /** Finds a project by display name (exact first, then case-insensitive), or {@code null} if none match. */
    private Project findProjectByName(String name) {
        for (Project p : projects().list()) {
            if (p.name().equals(name)) {
                return p;
            }
        }
        for (Project p : projects().list()) {
            if (p.name().equalsIgnoreCase(name)) {
                return p;
            }
        }
        return null;
    }

    // --- open / focus ---

    /** Opens (or focuses) the global, no-project window. */
    public Stage openOrFocusGlobal() {
        Holder existing = findHolder("");
        if (existing != null) {
            focus(existing.stage);
            setActiveAndSave("");
            return existing.stage;
        }
        Stage stage = buildWindow("", null, null, List.of(), false, false, null, false);
        projects().markOpen("");
        setActiveAndSave("");
        return stage;
    }

    /**
     * Opens a brand-new editor window not tied to any project (the palette "New Window" command). Unlike
     * {@link #openOrFocusGlobal()} (which focuses the single global window), this always <em>builds</em> a
     * new window, so the user can have several side-by-side without loading a project. Each gets a unique
     * {@code untitled:<uuid>} key and its <b>own</b> session file ({@code windows/<uuid>.json}) — so windows
     * never clobber each other's open files/layout — and is restored on the next launch like a project
     * window (when Projects are enabled). Works whether or not Projects are enabled.
     */
    public Stage newWindow() {
        String uuid = java.util.UUID.randomUUID().toString().substring(0, 8);
        String key = WindowKeys.UNTITLED_PREFIX + uuid;
        Stage stage = buildWindow(key, null, untitledStateFile(key), List.of(), false, false, null, false);
        projects().markOpen(key);
        setActiveAndSave(key);
        return stage;
    }

    /**
     * Opens OS-delivered files (macOS Finder "Open With" → the AppKit {@code openFiles} Apple Event) into the
     * focused window, building the no-project window if none is open. On macOS the path arrives via an Apple
     * Event rather than a command-line argument, so {@code App.installMacOpenFilesHandler} routes it here.
     */
    public void openExternalFiles(List<MainController.OpenTarget> files) {
        openExternalFiles(files, false, false, false);
    }

    /**
     * As above, additionally applying a focus mode the delivering launch asked for — the single-instance path
     * ({@code App.openForwardedLaunch}), where the user clicked a specific launcher entry such as "Editora
     * Expert Mode" and a window already exists to receive it.
     *
     * <p>The mode is applied rather than ignored because the user picked that entry deliberately; a launcher
     * named "Expert Mode" that silently opens a normal window is the more surprising outcome, and it is a
     * no-op in the common case where the running window is already in that mode. All three flags false (the
     * plain overload, and every OS-delivered event) leaves the window's chrome exactly as it was.
     */
    /**
     * Opens an externally-delivered launch — a file-manager "Open With" click that {@code ipc.SingleInstance}
     * handed to this already-running process — in a <b>new window of its own</b>.
     *
     * <p>A new window, not a tab in whatever the user was working in. Before the single-instance handoff, such
     * a click started its own process and therefore its own window; the handoff was meant to stop duplicating
     * the <em>process</em> (a second JVM, a second set of language servers), not to change what the click
     * does. Landing the file as a tab in the current window silently took over the window the user was in the
     * middle of using — and, with a launcher entry like "Editora Expert Mode", restyled its chrome as well.
     * Giving the launch its own window also makes the requested focus mode unambiguous: it applies to the new
     * window, at build time (so the first frame is already correct), instead of being imposed on an existing one.
     *
     * <p><b>Unless the file is already open somewhere</b>, in which case that window is focused instead. Two
     * independent buffers over one file is an edit-loss hazard, not just clutter: save one and the other is
     * silently stale behind an external-change prompt. Re-clicking a file you already have open is a normal
     * thing to do, so this is the common case rather than a corner one.
     *
     * <p>The macOS Apple-Event path keeps the existing-window behaviour ({@link #openExternalFiles(List)}):
     * that platform never duplicated a process, so nothing about it regressed and there is nothing to restore.
     */
    public void openExternalLaunchInNewWindow(
            List<MainController.OpenTarget> files, boolean zen, boolean expert, boolean simple) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (restorePending()) {
            // A window still to be built may be the one that has this file open; deciding now would open a
            // second buffer over it in a new window.
            deferredExternalLaunches.add(() -> openExternalLaunchInNewWindow(files, zen, expert, simple));
            return;
        }
        Holder holding = holderWithAnyOf(files);
        if (holding != null) {
            presentForExternalLaunch(holding.stage());
            holding.controller().openExternalFiles(files); // focuses the tab, honours a :line
            return;
        }
        String key = WindowKeys.UNTITLED_PREFIX
                + java.util.UUID.randomUUID().toString().substring(0, 8);
        boolean restoreNoSession = noSession;
        // This window shows the requested file and nothing else — restoring the saved session into it would
        // reproduce exactly the tab-pile the user is trying to get away from. It also keeps the window from
        // writing a session file of its own (persistSession returns early under --no-session).
        noSession = true;
        try {
            Stage stage = buildWindow(key, null, untitledStateFile(key), files, zen, expert, null, simple);
            transientWindows.add(key);
            presentForExternalLaunch(stage);
        } catch (RuntimeException | Error t) {
            java.util.logging.Logger.getLogger(WindowManager.class.getName())
                    .log(java.util.logging.Level.WARNING, "Failed to open a window for an external launch", t);
            // Fall back to the old behaviour rather than dropping the user's file on the floor.
            openExternalFiles(files, zen, expert, simple);
        } finally {
            noSession = restoreNoSession;
        }
    }

    /** The live window that already has any of {@code files} open, or {@code null} if none does. */
    private Holder holderWithAnyOf(List<MainController.OpenTarget> files) {
        for (Holder h : windows) {
            if (h.controller() == null) {
                continue;
            }
            for (MainController.OpenTarget t : files) {
                if (t != null && h.controller().hasFileOpen(t.file())) {
                    return h;
                }
            }
        }
        return null;
    }

    public void openExternalFiles(List<MainController.OpenTarget> files, boolean zen, boolean expert, boolean simple) {
        if (files == null || files.isEmpty()) {
            return;
        }
        if (restorePending()) {
            deferredExternalLaunches.add(() -> openExternalFiles(files, zen, expert, simple));
            return;
        }
        Holder target = focusedHolder();
        if (target == null) {
            openOrFocusGlobal(); // nothing open (or nothing focused) — land the files in the global window
            target = focusedHolder();
        }
        if (target == null) {
            return;
        }
        // Raise BEFORE opening, not after. Two reasons: the window should come forward the instant the click
        // is delivered rather than after a file read, tab build and first highlight (on a busy FX thread that
        // is visibly late); and if opening throws, the user would otherwise be left with a window that never
        // came forward at all.
        presentForExternalLaunch(target.stage());
        if (zen || expert || simple) {
            target.controller().applyStartupChrome(zen, expert, simple);
        }
        target.controller().openExternalFiles(files);
    }

    /** The currently-focused window, else the most recently built one (null when no window is open). */
    private Holder focusedHolder() {
        for (Holder h : windows) {
            if (h.stage() != null && h.stage().isFocused()) {
                return h;
            }
        }
        return windows.isEmpty() ? null : windows.get(windows.size() - 1);
    }

    /**
     * Any live window's controller other than {@code exclude}, or {@code null} if none — used to move MCP
     * server ownership to a surviving window when its owner closes (#463). Called from {@code disposeWindow},
     * which runs <em>before</em> {@link #onWindowClosed} removes the closing window, so {@code exclude} is
     * still in the list and must be skipped.
     */
    MainController otherLiveController(MainController exclude) {
        for (Holder h : windows) {
            if (h.controller() != null && h.controller() != exclude) {
                return h.controller();
            }
        }
        return null;
    }

    /** All open document owners at or below a resource path, across every live window. */
    List<com.editora.editor.EditorBuffer> buffersAtOrUnder(Path path) {
        List<com.editora.editor.EditorBuffer> out = new ArrayList<>();
        for (Holder holder : windows) {
            out.addAll(holder.controller().buffersAtOrUnderLocal(path));
        }
        return List.copyOf(out);
    }

    /** Whether a window other than {@code asking} has {@code file} open in a tab. */
    boolean openInAnotherWindow(MainController asking, Path file) {
        for (Holder holder : windows) {
            if (holder.controller() != asking
                    && !holder.controller().buffersAtOrUnderLocal(file).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** A saved {@code .editorconfig} governs files in every window, not only the one it was saved from. */
    void editorConfigSavedAcrossWindows() {
        for (Holder holder : List.copyOf(windows)) {
            holder.controller().applyEditorConfigLocal();
        }
    }

    void fileRenamedAcrossWindows(MainController initiator, Path from, Path to, boolean initiatorAlreadyClosed) {
        for (Holder holder : List.copyOf(windows)) {
            holder.controller()
                    .remapProjectFileLocal(from, to, holder.controller() == initiator && initiatorAlreadyClosed);
        }
    }

    void fileDeletedAcrossWindows(Path path) {
        for (Holder holder : List.copyOf(windows)) {
            holder.controller().removeProjectFileLocal(path);
        }
    }

    void invalidatePendingGitWrites(MainController initiator, Path root, List<String> pathspecs) {
        for (Holder holder : List.copyOf(windows)) {
            if (holder.controller() != initiator) {
                holder.controller().invalidatePendingGitWritesLocal(root, pathspecs);
            }
        }
    }

    void reloadAllFromDiskSilentlyAcrossWindows() {
        for (Holder holder : List.copyOf(windows)) {
            holder.controller().reloadAllFromDiskSilently();
        }
    }

    /** Directory holding per-window session files for untitled no-project windows ({@code windows/<uuid>.json}). */
    private Path windowsDir() {
        return shared.getConfigDir().resolve("windows");
    }

    /** The session file for an {@code untitled:<uuid>} window key. */
    private Path untitledStateFile(String key) {
        return windowsDir().resolve(WindowKeys.untitledSessionFileName(key));
    }

    /** Opens (or focuses) {@code project}'s window. A null/empty-id project routes to the global window. */
    public Stage openOrFocus(Project project) {
        if (project == null || project.id().isEmpty()) {
            return openOrFocusGlobal();
        }
        Holder existing = findHolder(project.id());
        if (existing != null) {
            focus(existing.stage);
            setActiveAndSave(project.id());
            return existing.stage;
        }
        Stage stage =
                buildWindow(project.id(), project, projects().stateFile(project), List.of(), false, false, null, false);
        projects().markOpen(project.id());
        setActiveAndSave(project.id());
        return stage;
    }

    /**
     * Opens {@code file} at 0-based {@code line} in the window for {@code projectKey} ({@code ""} = the global
     * no-project window): focuses the window if it's already open and opens the file there now, else builds the
     * window and opens the file after its session restores (passing it as a startup target, like a CLI file).
     * Used when a bookmark/note belonging to another project is activated, so the user lands in that project's
     * window rather than having the file opened out of context in the current one. A deleted project falls back
     * to the global window.
     */
    public void openInWindow(String projectKey, Path file, int line) {
        String key = projectKey == null ? "" : projectKey;
        Project project = key.isEmpty() ? null : findProject(key);
        if (!key.isEmpty() && project == null) {
            key = ""; // the project was deleted — fall back to the global window
        }
        Holder existing = findHolder(key);
        if (existing != null && existing.controller() != null) {
            focus(existing.stage());
            setActiveAndSave(key);
            existing.controller().openAndNavigate(file, line);
            return;
        }
        if (line < 0) {
            Path stateFile = key.isEmpty() ? null : projects().stateFile(project);
            buildWindow(key, project, stateFile, List.of(), false, false, null, false);
            projects().markOpen(key);
            setActiveAndSave(key);
            Holder created = findHolder(key);
            if (created != null && created.controller() != null) {
                javafx.application.Platform.runLater(() -> created.controller().openAndNavigate(file, line));
            }
            return;
        }
        // Not open — build it, passing the file as a startup target (1-based line) so it opens + jumps after
        // this window's own session restore, exactly like a command-line FILE:line argument.
        List<MainController.OpenTarget> targets = List.of(new MainController.OpenTarget(file, line + 1, 1));
        Path stateFile = key.isEmpty() ? null : projects().stateFile(project);
        buildWindow(key, project, stateFile, targets, false, false, null, false);
        projects().markOpen(key);
        setActiveAndSave(key);
    }

    private void setActiveAndSave(String key) {
        projects().setActive(key);
        projects().save();
    }

    // --- lifecycle ---

    /**
     * Called from a window's close handler after its session has been persisted. Drops the window from the
     * live set and (re)starts the {@linkplain #openSetReconcile debounced reconcile} of the persisted restore
     * set. The reconcile — not an eager {@code markClosed} here — is what makes a quit restore every window:
     * a Cmd-Q / OS quit fires this for each window in one burst, the debounce timer can't elapse inside that
     * burst (the JVM exits first), so it never persists a half-drained set; only a genuine single close (the
     * app keeps running) lives long enough for the timer to fire and forget that one window.
     */
    public void onWindowClosed(MainController controller) {
        Holder holder = null;
        for (Holder h : windows) {
            if (h.controller == controller) {
                holder = h;
                break;
            }
        }
        if (holder == null) {
            return;
        }
        controller.disposePlugins(); // stop() the window's plugins
        windows.remove(holder);
        transientWindows.remove(holder.key);
        if (windows.isEmpty()) {
            pluginManager.closeAll(); // last window gone — close every plugin class loader, freeing jar handles (#442)
            // Closed before the rest of the restore set was built: that is a quit, not a request to see the
            // other windows. They stay in the saved open set (reconcileOpenSet never writes an empty one)
            // with their sessions untouched, and come back next launch.
            pendingRestore.clear();
        }
        openSetReconcile.playFromStart();
    }

    /**
     * The in-app quit path ({@code app.quit} / the toolbar Quit button / {@code C-x C-c}) ends in
     * {@code Platform.exit()} — which fires <b>no</b> {@code Stage.onCloseRequest}, so the per-window close
     * handler that prompts for unsaved buffers and persists the window's session never runs. Quitting
     * therefore used to silently discard the dirty buffers <em>and</em> the whole session (tabs, carets,
     * bounds) of every window except the one quit from.
     *
     * <p>So walk them all here: bring each to the front, let it prompt for its own dirty buffers and persist
     * its session, then dispose its services (which also kills that window's Run/build subprocesses). Any
     * window's prompt can cancel the quit.
     *
     * <p>Deliberately does <b>not</b> call {@link #onWindowClosed} — that would drain the persisted
     * open-window set (see {@link #reconcileOpenSet}), and a quit must leave every window to reopen.
     *
     * @return false if the user cancelled at some window's save prompt (the quit is off).
     */
    boolean confirmCloseAllWindows() {
        // The prompts below run nested event loops; a pending restored window must not be built under them.
        // Left paused when the quit goes ahead: the unbuilt windows keep their saved sessions and their
        // place in the open set, exactly as if they had been open.
        restorePaused = true;
        boolean confirmed = confirmAndDisposeAllWindows();
        if (!confirmed) {
            restorePaused = false;
            if (restorePending()) {
                restoreScheduler.accept(this::buildNextRestoredWindow);
            }
        }
        return confirmed;
    }

    private boolean confirmAndDisposeAllWindows() {
        List<Holder> closing = new ArrayList<>(windows);
        java.util.IdentityHashMap<MainController, CloseCoordinator.ApprovalState> approvals =
                new java.util.IdentityHashMap<>();
        for (Holder holder : closing) {
            approvals.put(holder.controller(), new CloseCoordinator.ApprovalState());
        }
        // A prompt runs a nested FX event loop. While a later window is asking, an earlier window can receive
        // another edit/callback, so repeat until one complete cross-window pass required no prompts.
        while (true) {
            boolean prompted = false;
            for (Holder h : closing) {
                h.stage().toFront(); // make it obvious which window is asking about unsaved changes
                h.stage().requestFocus();
                CloseCoordinator.Sweep sweep = h.controller().closes.confirmSweep(approvals.get(h.controller()));
                if (!sweep.allowed()) {
                    return false; // cancelled — the app keeps running and nothing was disposed
                }
                prompted |= sweep.prompted();
            }
            if (!prompted) {
                break;
            }
        }
        for (Holder h : closing) {
            h.controller().persistSessionForClose();
        }
        for (Holder h : closing) {
            try {
                h.controller().disposePlugins();
            } catch (RuntimeException | Error t) {
                java.util.logging.Logger.getLogger(WindowManager.class.getName())
                        .log(java.util.logging.Level.WARNING, "plugin disposal failed during quit", t);
            }
            try {
                h.controller().disposeWindow(); // shut this window's services + kill its subprocesses
            } catch (RuntimeException | Error t) {
                java.util.logging.Logger.getLogger(WindowManager.class.getName())
                        .log(java.util.logging.Level.WARNING, "disposeWindow failed during quit", t);
            }
        }
        pluginManager.closeAll();
        return true;
    }

    /**
     * Persists the set of currently-open windows as the restore set. Debounced (see {@link #openSetReconcile})
     * so a burst of closes settles to a single write, and — crucially — so it cannot run <em>during</em> a
     * quit. The two ends:
     *
     * <ul>
     *   <li><b>A genuine single-window close</b> (the app keeps running) outlives the debounce, which then
     *       persists the reduced live set, so the closed window is forgotten and won't reopen.
     *   <li><b>A quit</b> (Cmd-Q / OS quit fires every window's close handler in one fast burst; {@code
     *       app.quit} uses {@code Platform.exit()} and fires none) terminates the JVM before the debounce
     *       timer elapses — so the reduced set is never written and the full pre-quit set stays on disk,
     *       reopening every window next launch.
     * </ul>
     *
     * The guard against an empty live set is belt-and-suspenders for the unlikely case the timer does fire
     * after the last window closed: we persist nothing rather than an empty set.
     */
    private void reconcileOpenSet() {
        if (singleWindowSession) {
            return; // --single-window: never rewrite the saved set from this one-window session
        }
        if (windows.isEmpty()) {
            return; // quitting — keep the pre-quit open set so it's all restored next launch
        }
        if (restorePending()) {
            // The live set is not the open set yet: writing it now would forget every window still waiting
            // to be built. Try again once they exist.
            openSetReconcile.playFromStart();
            return;
        }
        List<String> keys = new ArrayList<>();
        for (Holder h : windows) {
            if (transientWindows.contains(h.key)) {
                continue; // a file-manager launch's window; see transientWindows
            }
            keys.add(h.key);
        }
        if (keys.isEmpty()) {
            return; // only transient windows are live — leave the saved layout as it is
        }
        projects().setOpenWindows(keys);
        projects().save();
    }

    /**
     * Programmatically closes {@code controller}'s window (the "Close Project" command). Returns
     * {@code true} if it closed (or wasn't tracked), {@code false} if the user cancelled the save prompt.
     */
    public boolean requestClose(MainController controller) {
        for (Holder h : windows) {
            if (h.controller == controller) {
                if (!controller.closeWindowProgrammatically()) {
                    return false; // user cancelled
                }
                onWindowClosed(controller);
                h.stage.close();
                return true;
            }
        }
        return true;
    }

    /**
     * Closes the window for {@code projectKey} if one is open (used before deleting a project). Returns
     * {@code true} if there was no open window or it closed; {@code false} if the user cancelled.
     */
    public boolean closeWindowForKey(String projectKey) {
        Holder h = findHolder(projectKey == null ? "" : projectKey);
        return h == null || requestClose(h.controller);
    }

    /** Re-applies view settings + the editor theme to every open window (after a Settings change). */
    public void broadcastSettingsApplied() {
        broadcastSettingsApplied(null);
    }

    /**
     * As {@link #broadcastSettingsApplied()}, for a change made in {@code origin}'s Settings window: every
     * other window's Settings window, if open, re-reads its controls too. Left showing the old values, its
     * next edit of any stale control would write that value back over this change.
     */
    public void broadcastSettingsApplied(MainController origin) {
        settingsRebroadcast.stop(); // every window is about to apply everything, pending changes included
        settingsChangePending = false;
        Settings settings = shared.getSettings();
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.reapplyAfterSharedSettingsChange(settings);
            if (origin != null && h.controller != origin) {
                h.controller.settingsWindow().syncAll();
            }
        }
        shared.markSettingsApplied();
    }

    /**
     * A window saved preferences that the other windows have not applied — the change came from a palette
     * command or key binding, which applies it only in its own window. Schedules the others to catch up.
     */
    private void onSharedSettingsChanged(ConfigManager saver) {
        if (!javafx.application.Platform.isFxApplicationThread()) {
            // By the time this runs the saves around the change are over, so who made it can't be told.
            javafx.application.Platform.runLater(() -> onSharedSettingsChanged(null));
            return;
        }
        ConfigManager origin = changerOf(saver);
        if (!settingsChangePending) {
            settingsChangePending = true;
            settingsChangeOrigin = origin;
            settingsChangeFromSeveral = origin == null;
        } else if (origin != settingsChangeOrigin) {
            settingsChangeFromSeveral = true;
        }
        settingsRebroadcast.playFromStart();
    }

    /**
     * The window that made the change a save by {@code saver} just carried, or {@code null} when that can't
     * be told. Preferences are one shared object serialized at save time, so the first save after a change
     * carries it <em>whichever</em> window saves. The window that made the change always requests a save in
     * the same step, so the saver is that window only if no other window has a save waiting: with one
     * waiting, the saver may merely have saved first (its own save was queued before the command ran in the
     * other window), and skipping it would leave it on the old preferences for good.
     */
    private ConfigManager changerOf(ConfigManager saver) {
        if (saver == null) {
            return null;
        }
        for (Holder h : windows) {
            if (h.config() != saver && h.controller().saveRequestPending()) {
                return null;
            }
        }
        return saver;
    }

    /**
     * Runs a pending cross-window re-apply now: every window except the one that made the change re-applies
     * the shared preferences and refreshes its Settings window. A no-op when nothing is pending. Also the
     * seam tests use instead of waiting out the delay.
     */
    void flushPendingSettingsBroadcast() {
        settingsRebroadcast.stop();
        if (!settingsChangePending) {
            return;
        }
        settingsChangePending = false;
        ConfigManager skip = settingsChangeFromSeveral ? null : settingsChangeOrigin;
        settingsChangeOrigin = null;
        Settings settings = shared.getSettings();
        for (Holder h : new ArrayList<>(windows)) {
            if (h.config() != skip && h.stage().isShowing()) { // a window closed while this was pending is left alone
                h.controller().reapplyAfterSharedSettingsChange(settings);
                h.controller().settingsWindow().syncAll();
            }
        }
    }

    /**
     * Refreshes every window's recent-files menu and search-history dropdown from the shared lists, once per
     * pulse: restoring a session adds a recent file per opened tab, and each refresh rebuilds a menu in every
     * window.
     */
    private void scheduleSharedHistoryBroadcast() {
        if (sharedHistoryBroadcastQueued) {
            return;
        }
        sharedHistoryBroadcastQueued = true;
        javafx.application.Platform.runLater(() -> {
            sharedHistoryBroadcastQueued = false;
            for (Holder h : new ArrayList<>(windows)) {
                if (h.stage().isShowing()) {
                    h.controller().sharedHistoryChanged();
                }
            }
        });
    }

    /** Re-registers the synthetic {@code macro.run.*} commands in every window after the saved set changed. */
    public void broadcastMacrosChanged() {
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.refreshSavedMacroCommands();
        }
    }

    /** Re-runs the spell pass over every window's tabs after the shared user dictionary changed (a word added
     *  via "Add to Dictionary"), so another window's stale squiggles on that word clear immediately (#443). */
    public void broadcastUserDictionaryChanged() {
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.refreshSpellAllTabs();
        }
    }

    /**
     * A folder-trust decision changed (granted or revoked, here or in Settings). Each window re-resolves the
     * project overrides it may honour: revoking only edited the trust store, so a manager configured while
     * the folder was trusted kept launching the project-supplied command.
     */
    public void broadcastTrustChanged() {
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.trustChanged();
        }
    }

    /** Re-registers the synthetic {@code externalTool.run.*} commands in every window after the set changed. */
    public void broadcastExternalToolsChanged() {
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.refreshExternalToolCommands();
        }
    }

    /**
     * Rebuilds the shared keymap from scratch — base keymap ({@code Settings.keymap}, with the macOS
     * {@code .mac} variant when present) → user overrides → enabled plugins' keymap overrides → the user's
     * own bindings again, so a plugin cannot take back a chord the user bound (see {@link KeymapLayers}) —
     * the same result the startup order in {@code PluginCoordinator.applyPlugins} produces. The {@code KeymapManager} is a
     * single instance shared by every window's {@link KeyDispatcher}, so this switches the active keymap
     * live across all windows with no restart; a broadcast refreshes keymap-derived UI. A stale mid-chord
     * prefix in any dispatcher self-cancels on the next key, so no explicit reset is needed.
     */
    public void reloadSharedKeymap() {
        Settings settings = shared.getSettings();
        List<java.util.Map<String, String>> pluginKeymaps = new ArrayList<>();
        if (settings.isPluginSupport()) {
            for (com.editora.plugin.PluginDescriptor d : pluginManager.descriptors()) {
                if (d.enabled() && d.loadError() == null && d.manifest().keymap != null) {
                    pluginKeymaps.add(d.manifest().keymap);
                }
            }
        }
        KeymapLayers.rebuild(
                keymap,
                settings.getKeymap(),
                pluginKeymaps,
                settings.keybindingsFor(com.editora.command.KeymapManager.isMac()));
        broadcastSettingsApplied();
        // Every open Settings window shows the keymap: its combo, the shortcut list, the chord chips and the
        // Macros key-binding row. The window that made the change refreshes itself; the others are told here.
        for (Holder h : new ArrayList<>(windows)) {
            h.controller().settingsWindow().syncKeymap();
        }
        Holder focused = focusedHolder();
        reportUnknownKeymap(focused != null ? focused.controller() : null);
    }

    /**
     * Tells the user — once per bad value — that {@code Settings.keymap} names no bundled keymap and the
     * default is in use instead. The setting itself is left as written: it may be a typo the user will fix,
     * or a keymap a newer build provides. Reported as an error so it stays in the message log, since the
     * startup status echo is overwritten almost immediately.
     */
    private void reportUnknownKeymap(MainController controller) {
        if (controller == null) {
            return; // no window to tell yet; the name stays pending for the next one
        }
        String unknown = keymap.takeUnknownName();
        if (unknown != null) {
            controller.setError(com.editora.i18n.Messages.tr(
                    "status.keymap.unknown", unknown, KeymapManager.displayName(keymap.activeName())));
        }
    }

    // --- window construction (extracted from App.start) ---

    private Stage buildWindow(
            String key,
            Project project,
            Path stateFile,
            List<MainController.OpenTarget> targets,
            boolean zen,
            boolean expert,
            String newFile,
            boolean simple) {
        return buildWindow(key, project, stateFile, targets, zen, expert, newFile, simple, null);
    }

    private Stage buildWindow(
            String key,
            Project project,
            Path stateFile,
            List<MainController.OpenTarget> targets,
            boolean zen,
            boolean expert,
            String newFile,
            boolean simple,
            DiffUiRequest diffUi) {
        try {
            Stage stage = primaryStage != null ? primaryStage : new Stage();
            primaryStage = null; // only the first window reuses the JavaFX primary stage
            // Before anything else touches the stage: initStyle throws once a stage has been shown, and
            // this is read per window at construction, which is why the setting applies on restart.
            if (ExtendedWindow.enabled(shared.getSettings().isExtendedWindow(), System.getProperty("os.name"))) {
                try {
                    stage.initStyle(StageStyle.EXTENDED);
                } catch (RuntimeException e) {
                    // A window is not something a chrome preference gets to prevent. Fall back to a
                    // decorated one if the platform rejects extended decorations despite the OS gate.
                    java.util.logging.Logger.getLogger(WindowManager.class.getName())
                            .log(
                                    java.util.logging.Level.WARNING,
                                    "Extended window unavailable; using a decorated window",
                                    e);
                }
            }
            CommandRegistry registry = new CommandRegistry();
            // A no-project window normally uses the default workspace-state.json (stateFile null); a project
            // OR an extra "untitled" no-project window passes its own session file so windows never clobber.
            ConfigManager config = stateFile == null ? takeBootstrapConfig() : null;
            if (config == null) {
                config = stateFile == null ? new ConfigManager(shared) : new ConfigManager(shared, stateFile);
                config.setWorkspaceStateFile(config.getWorkspaceStateFile()); // load this window's session
            }

            FXMLLoader loader = new FXMLLoader(WindowManager.class.getResource("main.fxml"));
            // Set the classloader explicitly: FXMLLoader otherwise falls back to the FX thread's context
            // classloader, which is non-null at launch but can become null later — so building a window at
            // runtime (every project window past the first) would fail with a null-classloader NPE.
            loader.setClassLoader(WindowManager.class.getClassLoader());
            BorderPane root = loader.load();
            MainController controller = loader.getController();
            com.editora.perf.Startup.mark(com.editora.perf.Startup.FXML_LOADED);
            controller.setPluginManager(pluginManager); // before init: applyPlugins() runs inside init
            controller.init(stage, config, registry, keymap);
            com.editora.perf.Startup.mark(com.editora.perf.Startup.CONTROLLER_INIT);
            controller.setHostServices(hostServices);
            controller.setWindowContext(this, project);

            StackPane sceneRoot = new StackPane(root);
            controller.installZenOverlay(sceneRoot);
            Scene scene = new Scene(sceneRoot, 1000, 700);
            Settings settings = shared.getSettings();
            scene.setFill(Themes.backgroundFor(settings.getTheme()));
            scene.getStylesheets()
                    .addAll(
                            WindowManager.class
                                    .getResource("/com/editora/styles/app.css")
                                    .toExternalForm(),
                            WindowManager.class
                                    .getResource("/com/editora/styles/syntax.css")
                                    .toExternalForm());

            KeyDispatcher keyDispatcher = new KeyDispatcher(registry, keymap, controller::setStatus);
            keyDispatcher.install(scene);
            controller.setKeyDispatcher(keyDispatcher);

            scene.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
                if (e.isControlDown() && e.getDeltaY() != 0) {
                    controller.zoomFromWheel(e);
                    e.consume();
                }
            });

            boolean secondary = !windows.isEmpty(); // another window is already open
            stage.setScene(scene);
            stage.setTitle("Editora");
            loadAppIcons(stage);
            controller.applyEditorTheme(settings.getEditorTheme());
            // --simple / --zen / --expert BEFORE show(): these only shape chrome, so applying them here makes
            // the very first frame correct. They used to ride the deferred CLI-target runnable, which fires
            // only after the pulse-paced session restore — so the window appeared in full chrome and then
            // visibly stripped itself, a flash that grew with the number of restored files. Must stay after
            // init() (which runs toolWindows.restore(), whose state a focus mode stashes) and after
            // setWindowContext (which selects this window's session).
            controller.applyStartupChrome(zen, expert, simple, diffUi != null);
            restoreWindowBounds(stage, config.getWorkspaceState());
            // Don't bury a secondary window full-screen on top of an existing one: drop maximized so it
            // falls back to normal (cascadable) bounds. (Projects carried identical saved bounds — incl.
            // maximized — from the single-window era, so otherwise every project window stacks exactly.)
            if (secondary && stage.isMaximized()) {
                stage.setMaximized(false);
            }
            com.editora.perf.Startup.mark(com.editora.perf.Startup.WINDOW_BUILT);
            stage.show();
            com.editora.perf.Startup.mark(com.editora.perf.Startup.WINDOW_SHOWN);
            // AOT-cache training hook (build-time only; see the dist profile in pom.xml). When
            // -Deditora.aotTrainExit is set, render the first window then exit, so a -XX:AOTCacheOutput
            // training run captures the real GUI startup classes (JavaFX scene/controls/CSS, the editor,
            // highlighting) — which a headless run can't — then terminates cleanly. The short settle
            // lets the initial layout + syntax highlight run so those classes are archived too. Inert
            // (zero cost) in every normal launch since the property is never set at runtime.
            if (System.getProperty("editora.aotTrainExit") != null) {
                Thread t = new Thread(
                        () -> {
                            try {
                                Thread.sleep(2500);
                            } catch (InterruptedException ignored) {
                            }
                            System.exit(0);
                        },
                        "aot-train-exit");
                t.setDaemon(true);
                t.start();
            }
            // Offset a new window so it doesn't land exactly on top of an existing one, then bring it
            // to the front so it's clearly a separate window rather than an in-place swap.
            cascadeIfOverlapping(stage);
            focus(stage);

            windows.add(new Holder(key, stage, controller, config));
            if (windows.size() == 2) {
                // From here a preference changed in one window has another window to reach. Both reflect the
                // current preferences right now (this one was just built from them), so this is the baseline
                // later saves are compared against. A single window needs no detection at all.
                shared.markSettingsApplied();
            }
            if (diffUi != null) {
                controller.startupDiffUi(diffUi.left(), diffUi.right());
            } else {
                controller.startup(null, targets, newFile, noSession); // chrome flags already applied, pre-show()
            }
            reportUnknownKeymap(controller);
            reportConfigLoadProblems(controller);
            return stage;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to build a window for project '" + key + "'", e);
        }
    }

    /**
     * Visible for tests (the headless-FX harness): build a real, no-project ("global") window through the
     * normal {@link #buildWindow} path and return its controller, so {@code ui/} tests can assert real
     * controller behaviour (Zen/chrome/tab lifecycle) without an {@code Application} launch. Mirrors a
     * default global window: no project, default session file, no Zen/Simple/new-file. See {@code
     * FxWindowFixture} and the Tests note in CLAUDE.md.
     */
    MainController buildWindowForTest() {
        return buildWindowForTest(false, false, false);
    }

    /** As {@link #buildWindowForTest()}, with the session-only CLI chrome flags ({@code --zen}/{@code --expert}/
     *  {@code --simple}) — so a test can check the window's <em>first frame</em>, not its settled state. */
    MainController buildWindowForTest(boolean zen, boolean expert, boolean simple) {
        return buildWindowForTest(zen, expert, simple, List.of());
    }

    /** As above, with command-line {@code FILE} targets — so a test can check that the requested file is the
     *  one on screen from the first frame, ahead of whatever the session restores. */
    MainController buildWindowForTest(
            boolean zen, boolean expert, boolean simple, List<MainController.OpenTarget> targets) {
        return buildWindowForTest(zen, expert, simple, targets, false);
    }

    /** As above, with {@code --no-session} — the saved session's files are not restored. */
    MainController buildWindowForTest(
            boolean zen, boolean expert, boolean simple, List<MainController.OpenTarget> targets, boolean noSession) {
        this.noSession = noSession;
        buildWindow("", null, null, targets, zen, expert, null, simple);
        return windows.get(windows.size() - 1).controller();
    }

    /** Builds the production standalone-diff path for headless JavaFX tests. */
    MainController buildDiffWindowForTest(DiffUiRequest request) {
        this.noSession = true;
        buildWindow("", null, null, List.of(), false, false, null, false, request);
        return windows.get(windows.size() - 1).controller();
    }

    // --- helpers ---

    /**
     * Nudges {@code stage} down-right by a cascade step while its top-left would land on an already-open
     * window, so a newly-opened project window is visibly separate instead of stacking exactly on top.
     * Runs after {@code show()} so the bounds are realized. No-op for the first window / a maximized one.
     */
    private void cascadeIfOverlapping(Stage stage) {
        if (windows.isEmpty() || stage.isMaximized()) {
            return;
        }
        final double step = 32;
        double x = stage.getX();
        double y = stage.getY();
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return;
        }
        int guard = 0;
        while (guard++ < 25 && overlapsExisting(stage, x, y, step)) {
            x += step;
            y += step;
        }
        javafx.geometry.Rectangle2D vis = visualBoundsFor(x, y);
        if (x + stage.getWidth() > vis.getMaxX() || y + stage.getHeight() > vis.getMaxY()) {
            x = vis.getMinX() + step; // cascaded off-screen — wrap back near the top-left
            y = vis.getMinY() + step;
        }
        stage.setX(x);
        stage.setY(y);
    }

    private boolean overlapsExisting(Stage stage, double x, double y, double step) {
        for (Holder h : windows) {
            if (h.stage == stage || !h.stage.isShowing()) {
                continue;
            }
            if (Math.abs(h.stage.getX() - x) < step && Math.abs(h.stage.getY() - y) < step) {
                return true;
            }
        }
        return false;
    }

    private static javafx.geometry.Rectangle2D visualBoundsFor(double x, double y) {
        var screens = Screen.getScreensForRectangle(x, y, 1, 1);
        Screen screen = screens.isEmpty() ? Screen.getPrimary() : screens.get(0);
        return screen.getVisualBounds();
    }

    /**
     * Deletes {@code windows/<uuid>.json} session files for untitled windows that are no longer in the
     * open set (i.e. closed in a previous session) — so they don't accumulate. Best-effort; a missing dir
     * or an unreadable file is ignored. Run once at launch against the set being restored.
     */
    private void gcOrphanWindowSessions(java.util.Collection<String> openKeys) {
        Path dir = windowsDir();
        if (!java.nio.file.Files.isDirectory(dir)) {
            return;
        }
        try (var stream = java.nio.file.Files.list(dir)) {
            java.util.List<Path> files = stream.toList();
            java.util.List<String> names =
                    files.stream().map(p -> p.getFileName().toString()).toList();
            java.util.Set<String> orphans = WindowKeys.orphanSessionFiles(openKeys, names);
            for (Path p : files) {
                if (orphans.contains(p.getFileName().toString())) {
                    try {
                        java.nio.file.Files.deleteIfExists(p);
                    } catch (java.io.IOException ignored) {
                        // leave it; a later launch retries
                    }
                }
            }
        } catch (java.io.IOException ignored) {
            // best-effort cleanup
        }
    }

    private Project findProject(String id) {
        for (Project p : projects().list()) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    private Holder findHolder(String key) {
        for (Holder h : windows) {
            if (h.key.equals(key)) {
                return h;
            }
        }
        return null;
    }

    private void focusKey(String key) {
        Holder h = findHolder(key);
        if (h != null) {
            focus(h.stage);
        }
    }

    private static void focus(Stage stage) {
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
        stage.requestFocus();
    }

    /** How long the window is pinned above others while the compositor settles the raise; see below. */
    private static final Duration EXTERNAL_RAISE_PIN = Duration.millis(300);

    /**
     * Brings a window forward for a launch that arrived from <em>outside</em> the app — a file-manager click
     * handed over by {@code ipc.SingleInstance}, or an OS open-files event.
     *
     * <p>{@link #focus} alone is not enough here, and the reason is the compositor rather than JavaFX. When a
     * file manager launches a <b>new</b> process it hands it an activation token
     * ({@code XDG_ACTIVATION_TOKEN} / {@code DESKTOP_STARTUP_ID}), so the window that process maps counts as
     * user-initiated and is focused. A forwarded launch breaks that chain: the token goes to the forwarder,
     * which delivers its files and exits <em>without ever mapping a window</em>, while the process that does
     * own the window is an older one the user has not touched recently. Its {@code toFront()} is then an
     * unsolicited focus request from a background application, and GNOME/Mutter refuses it — raising
     * {@code _NET_WM_STATE_DEMANDS_ATTENTION} instead, which is the "click to bring it forward" notification,
     * with the window still sitting under the file manager.
     *
     * <p>The workaround asks for something the compositor does not arbitrate: {@code alwaysOnTop} maps to
     * {@code _NET_WM_STATE_ABOVE}, a window <em>state</em> rather than a focus request, so it is honoured from
     * a background app. Pinning briefly puts the window physically in front, then it is released so it does
     * not stay above everything else. {@code toFront}/{@code requestFocus} are still issued, because where
     * they are permitted (macOS, and X11 sessions that allow it) they additionally give keyboard focus, which
     * the ABOVE state alone does not.
     *
     * <p>Scoped deliberately to externally-delivered launches. Internal callers (switching project windows,
     * {@code focusKey}) run while Editora is already the active application, where a plain focus request is
     * granted and pinning a window above every other app would be an unpleasant surprise.
     */
    private static void presentForExternalLaunch(Stage stage) {
        focus(stage);
        try {
            stage.setAlwaysOnTop(true);
            PauseTransition release = new PauseTransition(EXTERNAL_RAISE_PIN);
            // Unconditionally cleared: a window left permanently above every other application would be a
            // far worse bug than the one this fixes.
            release.setOnFinished(e -> stage.setAlwaysOnTop(false));
            release.play();
        } catch (RuntimeException e) {
            stage.setAlwaysOnTop(false); // never leave it pinned
        }
    }

    /** Restores a window's size/position/maximized state from its session (see {@code App} original). */
    private static void restoreWindowBounds(Stage stage, WorkspaceState state) {
        double w = state.getWindowWidth();
        double h = state.getWindowHeight();
        if (w > 0 && h > 0) {
            double x = state.getWindowX();
            double y = state.getWindowY();
            if (!Screen.getScreensForRectangle(x, y, w, h).isEmpty()) {
                stage.setX(x);
                stage.setY(y);
            }
            stage.setWidth(w);
            stage.setHeight(h);
        }
        if (state.isWindowMaximized()) {
            stage.setMaximized(true);
        }
    }

    /** Adds the app icon (multiple sizes) so it shows in the title bar, dock, and taskbar. */
    private static void loadAppIcons(Stage stage) {
        for (int size : new int[] {16, 32, 48, 128, 256, 512}) {
            var in = WindowManager.class.getResourceAsStream("/com/editora/icons/icon-" + size + ".png");
            if (in != null) {
                stage.getIcons().add(new javafx.scene.image.Image(in));
            }
        }
    }
}
