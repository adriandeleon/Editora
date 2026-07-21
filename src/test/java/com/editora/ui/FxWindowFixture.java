package com.editora.ui;

import java.io.IOException;
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
final class FxWindowFixture {

    final Path configDir;
    final SharedConfig shared;
    final WindowManager windowManager;
    final MainController controller;

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
        return FxTestSupport.callOnFx(() -> {
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            keymap.applyOverrides(shared.getSettings().keybindingsFor(KeymapManager.isMac()));
            WindowManager wm = new WindowManager(shared, keymap, null);
            MainController controller = wm.buildWindowForTest(zen, expert, simple, targets);
            onBuilt.accept(controller);
            return new FxWindowFixture(dir, shared, wm, controller);
        });
    }

    /** Hide the window and delete the temp config dir. Programmatic close() doesn't fire onCloseRequest. */
    void dispose() throws Exception {
        FxTestSupport.runOnFx(() -> {
            try {
                FxTestSupport.<javafx.stage.Stage>field(controller, "stage").close();
            } catch (RuntimeException ignored) {
                // best-effort teardown
            }
        });
        // Drain any queued async config writes (settings.toml/session) before deleting the temp dir —
        // otherwise the ConfigWriter's temp-file + ATOMIC_MOVE can race the delete and throw
        // NoSuchFileException, an intermittent test error.
        shared.flushWrites();
        deleteRecursively(configDir);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // leftover temp files are harmless
                }
            });
        }
    }
}
