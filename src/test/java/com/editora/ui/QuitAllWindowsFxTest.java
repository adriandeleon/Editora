package com.editora.ui;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.editora.config.ConfigManager;
import com.editora.config.WorkspaceState;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The in-app quit ({@code app.quit} / the toolbar Quit button / {@code C-x C-c}) ends in
 * {@code Platform.exit()}, which fires <b>no</b> {@code Stage.onCloseRequest} — so the per-window close
 * handler, the only thing that prompts for unsaved buffers and persists a window's session, never runs. It
 * used to prompt + persist only the window you happened to quit <em>from</em>: every other window's dirty
 * buffers were discarded with no prompt, and its session (tabs, carets, bounds) silently reverted to whatever
 * it was at launch.
 *
 * <p>This drives the fix, {@link WindowManager#confirmCloseAllWindows}, with a clean (non-dirty) buffer in the
 * second window — a dirty one would open a modal save prompt, which a headless test can't answer. The session
 * half is the half that regresses silently; the prompt half is the same {@code confirmCloseAllBuffers} call
 * the per-window close handler already makes.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QuitAllWindowsFxTest {

    @TempDir
    Path tmp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void quittingPersistsTheSessionOfEveryWindowNotJustTheOneYouQuitFrom() throws Exception {
        FxWindowFixture fx = FxWindowFixture.create(); // window A
        WindowManager wm = fx.windowManager;

        // A second window, as "New Window" (C-x 5 2) creates. It gets its own session file.
        MainController b = FxTestSupport.callOnFx(() -> {
            wm.newWindow();
            List<?> holders = FxTestSupport.field(wm, "windows");
            Object holder = holders.get(holders.size() - 1);
            return (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
        });
        assertTrue(b != fx.controller, "a genuinely second window");

        // Open a file in window B only.
        Path file = tmp.resolve("in-window-b.txt");
        Files.writeString(file, "work in the other window\n");
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent("work in the other window\n");
            FxTestSupport.call(b, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        });

        ConfigManager configB = FxTestSupport.field(b, "config");
        WorkspaceState stateB = configB.getWorkspaceState();
        assertTrue(stateB.getOpenFiles().isEmpty(), "B's session hasn't been persisted yet");

        // Stand in for a discovered plugin loader. Quit must close the shared manager after stopping every
        // window's plugin instances; Platform.exit() does not run the ordinary Stage close handlers.
        com.editora.plugin.PluginManager plugins = FxTestSupport.field(wm, "pluginManager");
        Set<URLClassLoader> live = FxTestSupport.field(plugins, "live");
        live.add(new URLClassLoader("quit-test-plugin", new URL[0], getClass().getClassLoader()));
        AtomicInteger stopped = new AtomicInteger();
        com.editora.plugin.Plugin spy = new com.editora.plugin.Plugin() {
            @Override
            public void start(com.editora.plugin.PluginContext context) {}

            @Override
            public void stop() {
                stopped.incrementAndGet();
            }
        };
        addStartedPlugin(fx.controller, spy);
        addStartedPlugin(b, spy);

        // Quit. (onQuit() itself opens a confirm dialog, which a headless test can't answer — this is the
        // step it performs once confirmed, and the step that was missing.)
        boolean ok = FxTestSupport.callOnFx(
                () -> (Boolean) FxTestSupport.call(wm, "confirmCloseAllWindows", new Class<?>[] {}));
        assertTrue(ok, "nothing was dirty, so nothing cancelled the quit");
        assertEquals(2, stopped.get(), "each window's successfully started plugin instance must be stopped");
        assertTrue(live.isEmpty(), "application Quit must release shared plugin class loaders");

        assertEquals(
                1,
                stateB.getOpenFiles().size(),
                "window B's session must be persisted on quit — it used to be silently dropped");
        assertEquals(
                file.toAbsolutePath().toString(),
                stateB.getOpenFiles().get(0).getPath(),
                "and it must be B's own file");
    }

    private static void addStartedPlugin(MainController controller, com.editora.plugin.Plugin plugin) {
        PluginCoordinator coordinator = FxTestSupport.field(controller, "pluginCoordinator");
        List<com.editora.plugin.Plugin> started = FxTestSupport.field(coordinator, "startedPlugins");
        started.add(plugin);
    }
}
