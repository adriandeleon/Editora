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

    /** A live window: its project key ({@code ""} = the global session), its stage and controller. */
    private record Holder(String key, Stage stage, MainController controller) {}

    public WindowManager(SharedConfig shared, KeymapManager keymap, HostServices hostServices) {
        this.shared = shared;
        this.keymap = keymap;
        this.hostServices = hostServices;
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
    }

    /** Shows a config-write failure in the focused window's status bar (best-effort; logged regardless). */
    private void notifyConfigWriteError(Path file) {
        Holder h = focusedHolder();
        if (h != null && h.controller() != null) {
            h.controller()
                    .setStatus(com.editora.i18n.Messages.tr(
                            "status.config.saveFailed", file.getFileName().toString()));
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

        for (String key : toOpen) {
            boolean untitled = WindowKeys.isUntitled(key);
            Project project = (key.isEmpty() || untitled) ? null : findProject(key);
            if (!key.isEmpty() && !untitled && project == null) {
                continue; // a stale id (project deleted out from under the open set)
            }
            Path stateFile = untitled ? untitledStateFile(key) : (project == null ? null : pm.stateFile(project));
            boolean isPrimary = key.equals(primary);
            try {
                buildWindow(
                        key,
                        project,
                        stateFile,
                        isPrimary ? targets : List.of(),
                        isPrimary && zen,
                        isPrimary && expert,
                        isPrimary ? newFile : null,
                        isPrimary && simple);
                pm.markOpen(key);
            } catch (RuntimeException | Error t) {
                // One window failing to build (corrupt session, etc.) must NOT abort restoring the rest —
                // launch runs in App.start with no catch, so an uncaught throw here would silently leave only
                // the windows built before it. Log and continue with the remaining windows.
                java.util.logging.Logger.getLogger(WindowManager.class.getName())
                        .log(java.util.logging.Level.WARNING, "Failed to build window for key '" + key + "'", t);
            }
        }
        if (findHolder(primary) == null && !windows.isEmpty()) {
            primary = windows.get(0).key(); // the chosen primary failed to build — focus whatever opened
        }
        pm.save();
        gcOrphanWindowSessions(toOpen);
        focusKey(primary);
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
    public void openExternalFiles(List<Path> files) {
        if (files == null || files.isEmpty()) {
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
        target.controller().openExternalFiles(files);
        focus(target.stage());
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
        if (windows.isEmpty()) {
            pluginManager.closeAll(); // last window gone — close every plugin class loader, freeing jar handles (#442)
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
        for (Holder h : new ArrayList<>(windows)) {
            h.stage().toFront(); // make it obvious which window is asking about unsaved changes
            h.stage().requestFocus();
            if (!h.controller().confirmCloseAllBuffers()) {
                return false; // cancelled — the app keeps running and nothing was disposed
            }
        }
        for (Holder h : new ArrayList<>(windows)) {
            try {
                h.controller().disposeWindow(); // shut this window's services + kill its subprocesses
            } catch (RuntimeException | Error t) {
                java.util.logging.Logger.getLogger(WindowManager.class.getName())
                        .log(java.util.logging.Level.WARNING, "disposeWindow failed during quit", t);
            }
        }
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
        List<String> keys = new ArrayList<>();
        for (Holder h : windows) {
            keys.add(h.key);
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
        Settings settings = shared.getSettings();
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.reapplyAfterSharedSettingsChange(settings);
        }
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

    /** Re-registers the synthetic {@code externalTool.run.*} commands in every window after the set changed. */
    public void broadcastExternalToolsChanged() {
        for (Holder h : new ArrayList<>(windows)) {
            h.controller.refreshExternalToolCommands();
        }
    }

    /**
     * Rebuilds the shared keymap from scratch — base keymap ({@code Settings.keymap}, with the macOS
     * {@code .mac} variant when present) → user overrides → enabled plugins' keymap overrides — mirroring
     * the startup order in {@link MainController}'s {@code applyPlugins}. The {@code KeymapManager} is a
     * single instance shared by every window's {@link KeyDispatcher}, so this switches the active keymap
     * live across all windows with no restart; a broadcast refreshes keymap-derived UI. A stale mid-chord
     * prefix in any dispatcher self-cancels on the next key, so no explicit reset is needed.
     */
    public void reloadSharedKeymap() {
        Settings settings = shared.getSettings();
        keymap.loadNamed(settings.getKeymap());
        keymap.applyOverrides(settings.keybindingsFor(com.editora.command.KeymapManager.isMac()));
        if (settings.isPluginSupport()) {
            for (com.editora.plugin.PluginDescriptor d : pluginManager.descriptors()) {
                if (d.enabled() && d.loadError() == null && d.manifest().keymap != null) {
                    keymap.applyOverrides(d.manifest().keymap);
                }
            }
        }
        broadcastSettingsApplied();
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
        try {
            Stage stage = primaryStage != null ? primaryStage : new Stage();
            primaryStage = null; // only the first window reuses the JavaFX primary stage
            CommandRegistry registry = new CommandRegistry();
            // A no-project window normally uses the default workspace-state.json (stateFile null); a project
            // OR an extra "untitled" no-project window passes its own session file so windows never clobber.
            ConfigManager config = stateFile == null ? new ConfigManager(shared) : new ConfigManager(shared, stateFile);
            config.setWorkspaceStateFile(config.getWorkspaceStateFile()); // load this window's session

            FXMLLoader loader = new FXMLLoader(WindowManager.class.getResource("main.fxml"));
            // Set the classloader explicitly: FXMLLoader otherwise falls back to the FX thread's context
            // classloader, which is non-null at launch but can become null later — so building a window at
            // runtime (every project window past the first) would fail with a null-classloader NPE.
            loader.setClassLoader(WindowManager.class.getClassLoader());
            BorderPane root = loader.load();
            MainController controller = loader.getController();
            controller.setPluginManager(pluginManager); // before init: applyPlugins() runs inside init
            controller.init(stage, config, registry, keymap);
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
            controller.applyStartupChrome(zen, expert, simple);
            restoreWindowBounds(stage, config.getWorkspaceState());
            // Don't bury a secondary window full-screen on top of an existing one: drop maximized so it
            // falls back to normal (cascadable) bounds. (Projects carried identical saved bounds — incl.
            // maximized — from the single-window era, so otherwise every project window stacks exactly.)
            if (secondary && stage.isMaximized()) {
                stage.setMaximized(false);
            }
            stage.show();
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

            windows.add(new Holder(key, stage, controller));
            controller.startup(null, targets, newFile, noSession); // chrome flags already applied, pre-show()
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
