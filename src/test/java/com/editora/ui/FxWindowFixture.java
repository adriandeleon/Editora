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

    private boolean disposed;

    private FxWindowFixture(Path configDir, SharedConfig shared, WindowManager wm, MainController controller) {
        this.configDir = configDir;
        this.shared = shared;
        this.windowManager = wm;
        this.controller = controller;
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
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(shared.getSettings().keybindingsFor(KeymapManager.isMac()));
            WindowManager wm = new WindowManager(shared, keymap, null);
            MainController controller = wm.buildWindowForTest(zen, expert, simple, targets, noSession);
            onBuilt.accept(controller);
            return new FxWindowFixture(dir, shared, wm, controller);
        });
    }

    /** Boots the production {@code --diff-ui LEFT RIGHT} path against an isolated config directory. */
    static FxWindowFixture createDiff(Path dir, Path left, Path right, Consumer<MainController> onBuilt)
            throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(shared.getSettings().keybindingsFor(KeymapManager.isMac()));
            WindowManager wm = new WindowManager(shared, keymap, null);
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
            deleteRecursively(configDir);
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
        IOException failure = null;
        for (int attempt = 0; attempt < 4 && Files.exists(root); attempt++) {
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
                failure = null;
            } catch (IOException e) {
                failure = e;
            } catch (UncheckedIOException e) {
                // Files.walk wraps a file disappearing during traversal. Retry the whole tree; a persistent
                // resource leak still fails below when the root remains after the bounded attempts.
                failure = e.getCause();
            }
        }
        if (Files.exists(root)) {
            throw failure == null ? new IOException("Failed to delete test directory " + root) : failure;
        }
    }
}
