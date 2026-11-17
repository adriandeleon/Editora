package com.editora.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;

/**
 * Builds a real, fully-wired {@link MainController} window headlessly for {@code ui/} tests, against an
 * isolated temp config dir. Goes through the production boot path ({@link WindowManager#buildWindowForTest})
 * so tests exercise the same construction the app uses — if that path changes, this fixture tracks it.
 *
 * <p>Usage: {@code FxTestSupport.bootToolkit()} once, then {@code FxWindowFixture.create()} per test class
 * (the build is ~100–300 ms), and {@link #dispose()} after.
 */
final class FxWindowFixture implements AutoCloseable {

    final Path configDir;
    final SharedConfig shared;
    final WindowManager windowManager;
    final MainController controller;
    /** This window's own folder for Export Configuration's zip, deleted on {@link #dispose()}. */
    final Path exportDir;

    /** Set to leave the config directory in place on {@link #dispose()} — for a test that starts again on it. */
    boolean keepConfigDir;

    private boolean disposed;

    private FxWindowFixture(Path configDir, SharedConfig shared, WindowManager wm, MainController controller) {
        this.configDir = configDir;
        this.shared = shared;
        this.windowManager = wm;
        this.controller = controller;
        this.exportDir = shared.exportDirectory();
    }

    /** Boot a window on the FX thread, isolated to a fresh temp config dir. */
    static FxWindowFixture create() throws Exception {
        return create(false, false, false, c -> {});
    }

    /**
     * Boot a window with the session-only CLI chrome flags ({@code --zen}/{@code --expert}/{@code --simple}).
     *
     * <p>{@code onBuilt} runs on the FX thread <em>inside the same runnable as the build</em>, so no queued
     * {@code Platform.runLater} has had a chance to run yet. That lets a test assert what the window looked
     * like on its <b>first frame</b> rather than after the deferred startup work settles — the distinction
     * that separates "chrome applied before show()" from "chrome fixed up a moment later".
     */
    static FxWindowFixture create(boolean zen, boolean expert, boolean simple, Consumer<MainController> onBuilt)
            throws Exception {
        return create(Files.createTempDirectory("editora-fx-test"), zen, expert, simple, List.of(), onBuilt);
    }

    /**
     * Boot a window against a caller-supplied config dir (so a test can seed {@code workspace-state.json}
     * first) and with command-line {@code FILE} targets, as {@code editora FILE} does.
     */
    static FxWindowFixture create(
            Path dir,
            boolean zen,
            boolean expert,
            boolean simple,
            List<MainController.OpenTarget> targets,
            Consumer<MainController> onBuilt)
            throws Exception {
        return create(dir, zen, expert, simple, targets, false, onBuilt);
    }

    /** As above, with {@code --no-session} (open only {@code targets}, skipping the saved session's files). */
    static FxWindowFixture create(
            Path dir,
            boolean zen,
            boolean expert,
            boolean simple,
            List<MainController.OpenTarget> targets,
            boolean noSession,
            Consumer<MainController> onBuilt)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            boolean fresh = !savesLanguageServers(dir);
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            quietRemoteExecution(shared);
            quietLanguageServers(shared, fresh);
            isolateExports(shared);
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(UserKeys.overrides(shared.getSettings()));
            WindowManager wm = new WindowManager(shared, keymap, null);
            // A window a command opens has its session read in place here, so a test gets it back at once; the
            // read off the FX thread is StoresOffFxThreadFxTest's.
            wm.sessionReads = FxWindowFixture::readAndWait;
            wm.builtForTest = FxWindowFixture::readInPlace;
            MainController controller = wm.buildWindowForTest(zen, expert, simple, targets, noSession);
            onBuilt.accept(controller);
            return new FxWindowFixture(dir, shared, wm, controller);
        });
    }

    /**
     * Remote execution starts off in a test window, as it did before it became the default: mounting a remote
     * folder with it on puts the one-time host question, a modal dialog nobody here answers. A test about
     * running on a host switches it on itself.
     */
    private static void quietRemoteExecution(SharedConfig shared) {
        shared.getSettings().setRemoteExecution(false);
    }

    /** Whether {@code dir}'s settings file already says whether language servers are on. */
    private static boolean savesLanguageServers(Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        return Files.exists(file) && Files.readString(file).contains("\"lspSupport\"");
    }

    /**
     * Language servers and debugging start off in a fresh test window, as before they became the default: with them on,
     * every code file a test opens would start whatever server this machine has installed. A test about them switches
     * them on itself. A window on a config folder whose settings already say (a test that starts again on it) keeps
     * what was saved there.
     */
    private static void quietLanguageServers(SharedConfig shared, boolean fresh) {
        if (fresh) {
            shared.getSettings().setLspSupport(false);
            // Debugging too: on, a window detects the debug adapters in the background, and that detection could
            // overwrite what a test set up by hand.
            shared.getSettings().setDebugSupport(false);
        }
    }

    /**
     * Export Configuration writes its zip to the home directory; a test window writes it to a temporary folder
     * of its own, so the command sweep (which runs {@code config.export}) leaves nothing in the real home.
     */
    private static void isolateExports(SharedConfig shared) throws IOException {
        shared.setExportDirectory(Files.createTempDirectory(EXPORT_DIR_PREFIX));
    }

    private static final String EXPORT_DIR_PREFIX = "editora-fx-exports";

    /**
     * A window's session read on a thread of its own (so it waits for the config writes queued before it, as the
     * app's does) while the command waits for it: the window is built before the command returns.
     */
    static void readAndWait(Runnable read) {
        try {
            Thread.ofVirtual().name("window-session-read").start(read).join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Has a test window do in place, on the FX thread, the file work the app hands to threads of its own
     * (PERF-01, Pauta 6.0.1): snippet and template files, project settings, shared run configurations. A test
     * then sees the result as its step returns, as it did before the work left the FX thread; the work off the
     * FX thread itself is StoresOffFxThreadFxTest's.
     */
    static void readInPlace(MainController controller) {
        if (controller.snippetCoordinator != null) {
            controller.snippetCoordinator.manager().fileWork = Runnable::run;
        }
        if (controller.templateActions.templates != null) {
            controller.templateActions.templates.fileWork = Runnable::run;
        }
        controller.lspCoordinator.projectScope().reads = Runnable::run;
        controller.runConfigurations.sharedFiles = Runnable::run;
    }

    /**
     * Boot a default window after {@code beforeWindows} has prepared the shared config — the place where
     * {@code App.start} does its own pre-window wiring (claiming the config dir, for one).
     */
    static FxWindowFixture create(Path dir, Consumer<SharedConfig> beforeWindows) throws Exception {
        return create(dir, beforeWindows, true);
    }

    /**
     * As {@link #create(Path, Consumer)}, but the window does its file work where the app does it: off the FX
     * thread, on the threads that own it (StoresOffFxThreadFxTest).
     */
    static FxWindowFixture createWithFileWorkOffThread(Path dir, Consumer<SharedConfig> beforeWindows)
            throws Exception {
        return create(dir, beforeWindows, false);
    }

    private static FxWindowFixture create(Path dir, Consumer<SharedConfig> beforeWindows, boolean inPlace)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            boolean fresh = !savesLanguageServers(dir);
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            quietRemoteExecution(shared);
            quietLanguageServers(shared, fresh);
            isolateExports(shared);
            beforeWindows.accept(shared);
            // What the launch's prefetch looks at for the first window (FirstWindowPrep): which servers run
            // Editora's own copy. Done here, in the fixture's own set-up, so the window applies its support at once.
            LspServerCommands.prepareOwnCopies(shared.getSettings(), bootstrap.getConfigDir());
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(UserKeys.overrides(shared.getSettings()));
            WindowManager wm = new WindowManager(shared, keymap, null);
            if (inPlace) {
                // A window a command opens has its session read in place here, so a test gets it back at once;
                // the read off the FX thread is StoresOffFxThreadFxTest's.
                wm.sessionReads = FxWindowFixture::readAndWait;
                wm.builtForTest = FxWindowFixture::readInPlace;
            }
            MainController controller = wm.buildWindowForTest();
            return new FxWindowFixture(dir, shared, wm, controller);
        });
    }

    /**
     * Makes this a project window on a plain folder of its own (under {@link #configDir}, so {@link #dispose()}
     * removes it), the way {@code WindowManager} builds one, and returns that folder. Run configurations exist
     * only in a project window. Idempotent: the same folder and project every time.
     */
    Path useProject() throws Exception {
        Path root = Files.createDirectories(configDir.resolve("project"));
        FxTestSupport.runOnFx(() -> {
            shared.getSettings().setProjectSupport(true); // a window has a project only while the feature is on
            controller.setWindowContext(
                    windowManager, new com.editora.config.Project("fx-test", "fx-test", root.toString()));
        });
        return root;
    }

    /**
     * Launches {@code --single-window} the way {@code App.start} does, but with no session read before the toolkit
     * (no bootstrap): the window's session is read off the FX thread and the window built when it lands.
     */
    static FxWindowFixture launchWithoutReadAhead(Path dir) throws Exception {
        Object[] made = FxTestSupport.callOnFx(() -> {
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            quietRemoteExecution(shared);
            quietLanguageServers(shared, !savesLanguageServers(dir));
            isolateExports(shared);
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(UserKeys.overrides(shared.getSettings()));
            WindowManager wm = new WindowManager(shared, keymap, null);
            wm.launch(new javafx.stage.Stage(), null, List.of(), false, false, null, false, "", false);
            return new Object[] {shared, wm};
        });
        WindowManager wm = (WindowManager) made[1];
        SettingsRig.awaitFx(
                "the window built once its session was read",
                () -> !FxTestSupport.<List<?>>field(wm, "windows").isEmpty());
        MainController controller = FxTestSupport.callOnFx(() -> (MainController) FxTestSupport.call(
                FxTestSupport.<List<?>>field(wm, "windows").get(0), "controller", new Class<?>[] {}));
        return new FxWindowFixture(dir, (SharedConfig) made[0], wm, controller);
    }

    /** Boots the production {@code --diff-ui LEFT RIGHT} path against an isolated config directory. */
    static FxWindowFixture createDiff(Path dir, Path left, Path right, Consumer<MainController> onBuilt)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            boolean fresh = !savesLanguageServers(dir);
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            quietRemoteExecution(shared);
            quietLanguageServers(shared, fresh);
            isolateExports(shared);
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(UserKeys.overrides(shared.getSettings()));
            WindowManager wm = new WindowManager(shared, keymap, null);
            // A window a command opens has its session read in place here, so a test gets it back at once; the
            // read off the FX thread is StoresOffFxThreadFxTest's.
            wm.sessionReads = FxWindowFixture::readAndWait;
            wm.builtForTest = FxWindowFixture::readInPlace;
            MainController controller = wm.buildDiffWindowForTest(new WindowManager.DiffUiRequest(left, right));
            onBuilt.accept(controller);
            return new FxWindowFixture(dir, shared, wm, controller);
        });
    }

    /**
     * Force-dispose the test window without invoking user-facing close prompts, then stop every resource
     * owner created by this fixture before deleting its config directory.
     */
    synchronized void dispose() throws Exception {
        if (disposed) {
            return;
        }
        disposed = true;
        Exception failure = null;
        try {
            FxTestSupport.runOnFx(() -> {
                RuntimeException cleanupFailure = null;
                List<?> holders = List.copyOf(FxTestSupport.<List<?>>field(windowManager, "windows"));
                for (Object holder : holders) {
                    MainController ownedController =
                            (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                    javafx.stage.Stage ownedStage =
                            (javafx.stage.Stage) FxTestSupport.call(holder, "stage", new Class<?>[] {});
                    // A prompt this window raised late (an external-change question after a focus check, say)
                    // would outlive the test and be the "next dialog" of whichever test runs after it.
                    for (javafx.stage.Window open : List.copyOf(javafx.stage.Window.getWindows())) {
                        if (open instanceof javafx.stage.Stage dialog
                                && dialog.getOwner() == ownedStage
                                && dialog.getScene() != null
                                && dialog.getScene().getRoot() instanceof javafx.scene.control.DialogPane) {
                            dialog.hide();
                        }
                    }
                    try {
                        ownedController.disposePlugins();
                    } catch (RuntimeException e) {
                        cleanupFailure = combineRuntime(cleanupFailure, e);
                    }
                    try {
                        ownedController.disposeWindow();
                    } catch (RuntimeException e) {
                        cleanupFailure = combineRuntime(cleanupFailure, e);
                    }
                    try {
                        ownedStage.close();
                    } catch (RuntimeException e) {
                        cleanupFailure = combineRuntime(cleanupFailure, e);
                    }
                }
                try {
                    FxTestSupport.<com.editora.plugin.PluginManager>field(windowManager, "pluginManager")
                            .closeAll();
                } catch (RuntimeException e) {
                    cleanupFailure = combineRuntime(cleanupFailure, e);
                }
                if (cleanupFailure != null) {
                    throw cleanupFailure;
                }
            });
        } catch (Exception e) {
            failure = e;
        }
        try {
            // Controller shutdown can release worker completions that were already queued for the FX thread.
            // Let those callbacks observe sessionClosed before shutting down the shared config writer.
            FxTestSupport.drainFx();
        } catch (Exception e) {
            failure = combine(failure, e);
        }
        try {
            shared.shutdown();
        } catch (RuntimeException e) {
            failure = combine(failure, e);
        }
        try {
            windowManager.recovery().close(); // its worker thread and session lock; a no-op if never used
        } catch (RuntimeException e) {
            failure = combine(failure, e);
        }
        try {
            if (!keepConfigDir) {
                deleteRecursively(configDir);
            }
        } catch (IOException e) {
            failure = combine(failure, e);
        }
        try {
            // Only the folder isolateExports made: a test that moved the export elsewhere keeps that place.
            if (exportDir.getFileName().toString().startsWith(EXPORT_DIR_PREFIX)) {
                deleteRecursively(exportDir);
            }
        } catch (IOException e) {
            failure = combine(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    @Override
    public void close() throws Exception {
        dispose();
    }

    private static Exception combine(Exception first, Exception next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static RuntimeException combineRuntime(RuntimeException first, RuntimeException next) {
        if (first == null) {
            return next;
        }
        first.addSuppressed(next);
        return first;
    }

    private static void deleteRecursively(Path root) throws IOException {
        IOException[] failure = {null};
        java.util.function.BooleanSupplier deleted = () -> {
            failure[0] = deletePass(root);
            return !Files.exists(root);
        };
        // A language server asked to stop may still write its workspace under root for a moment (jdtls's .metadata),
        // and nothing tells when it is done: a failed pass is tried again after each FX pulse, for up to about five
        // seconds, rather than racing it (QUAL-07: no sleep).
        if (!deleted.getAsBoolean() && !javafx.application.Platform.isFxApplicationThread()) {
            try {
                com.editora.testkit.Await.holdsWithin(deleted, java.time.Duration.ofSeconds(5));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (Files.exists(root)) {
            throw failure[0] == null ? new IOException("Failed to delete test directory " + root) : failure[0];
        }
    }

    /** One pass over the tree, deepest first; the failure that stopped it, or null. */
    private static IOException deletePass(Path root) {
        if (!Files.exists(root)) {
            return null;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            return null;
        } catch (IOException e) {
            return e;
        } catch (UncheckedIOException e) {
            // Files.walk wraps a file disappearing during traversal. Retry the whole tree; a persistent
            // resource leak still fails when the root remains after the bounded attempts.
            return e.getCause();
        }
    }
}
