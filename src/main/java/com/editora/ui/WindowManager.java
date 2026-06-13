package com.editora.ui;

import com.editora.command.CommandRegistry;
import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.ProjectManager;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.config.WorkspaceState;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import javafx.application.HostServices;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;

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
            String newFile,
            boolean simple) {
        this.primaryStage = primaryStage; // reused for the first window built
        Settings settings = shared.getSettings();
        if (!settings.isProjectSupport()) {
            buildWindow("", null, null, targets, zen, newFile, simple);
            return;
        }
        ProjectManager pm = projects();
        LinkedHashSet<String> toOpen = new LinkedHashSet<>(pm.openProjectIds());
        Project cli = null;
        if (projectArg != null) {
            Path root = Path.of(projectArg).toAbsolutePath().normalize();
            String name = root.getFileName() == null
                    ? root.toString()
                    : root.getFileName().toString();
            cli = pm.createOrGet(name, root);
            pm.save();
            toOpen.add(cli.id());
        }
        if (toOpen.isEmpty()) {
            toOpen.add(""); // nothing remembered ⇒ open the global window
        }
        String primary = cli != null
                ? cli.id()
                : toOpen.contains("")
                        ? ""
                        : (pm.active() != null && toOpen.contains(pm.active().id()))
                                ? pm.active().id()
                                : toOpen.iterator().next();

        for (String key : toOpen) {
            Project project = key.isEmpty() ? null : findProject(key);
            if (!key.isEmpty() && project == null) {
                continue; // a stale id (project deleted out from under the open set)
            }
            boolean isPrimary = key.equals(primary);
            buildWindow(
                    key,
                    project,
                    project == null ? null : pm.stateFile(project),
                    isPrimary ? targets : List.of(),
                    isPrimary && zen,
                    isPrimary ? newFile : null,
                    isPrimary && simple);
            pm.markOpen(key);
        }
        pm.save();
        focusKey(primary);
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
        Stage stage = buildWindow("", null, null, List.of(), false, null, false);
        projects().markOpen("");
        setActiveAndSave("");
        return stage;
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
        Stage stage = buildWindow(project.id(), project, projects().stateFile(project), List.of(), false, null, false);
        projects().markOpen(project.id());
        setActiveAndSave(project.id());
        return stage;
    }

    private void setActiveAndSave(String key) {
        projects().setActive(key);
        projects().save();
    }

    // --- lifecycle ---

    /**
     * Called from a window's close handler after its session has been persisted. Drops the window from the
     * live set; if other windows remain (the user closed one of several), forgets it from the restore set.
     * When the last window closes (a quit), the restore set is left intact so the app reopens it.
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
        if (!windows.isEmpty()) {
            projects().markClosed(holder.key);
            projects().save();
        }
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

    // --- window construction (extracted from App.start) ---

    private Stage buildWindow(
            String key,
            Project project,
            Path stateFile,
            List<MainController.OpenTarget> targets,
            boolean zen,
            String newFile,
            boolean simple) {
        try {
            Stage stage = primaryStage != null ? primaryStage : new Stage();
            primaryStage = null; // only the first window reuses the JavaFX primary stage
            CommandRegistry registry = new CommandRegistry();
            ConfigManager config = project == null ? new ConfigManager(shared) : new ConfigManager(shared, stateFile);
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
            controller.startup(null, targets, zen, newFile, simple);
            return stage;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to build a window for project '" + key + "'", e);
        }
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
