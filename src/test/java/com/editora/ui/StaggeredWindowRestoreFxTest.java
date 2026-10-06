package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Restoring several windows shows the primary one before the others are built, builds the rest one at a
 * time, and loses nothing if the app is closed part-way.
 *
 * <p>The queue's scheduler is replaced by one the test steps by hand, so "before the others are built" is an
 * exact statement about state rather than a race against animation frames.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaggeredWindowRestoreFxTest {

    private static final String FIRST = "untitled:aaaa1111";
    private static final String LAST = "untitled:bbbb2222";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** A config dir whose saved open set is three windows — the global one (the primary) in the middle. */
    private record Seeded(Path dir, Path globalFile, Path firstFile, Path lastFile) {}

    private static Seeded seed() throws Exception {
        Path dir = Files.createTempDirectory("editora-staggered-restore");
        Path files = Files.createDirectories(dir.resolve("files"));
        Path global = Files.writeString(files.resolve("global.txt"), "global\n");
        Path first = Files.writeString(files.resolve("first.txt"), "first\n");
        Path last = Files.writeString(files.resolve("last.txt"), "last\n");
        Files.writeString(
                dir.resolve("projects.json"),
                "{\"schemaVersion\":2,\"projects\":[],\"activeProjectId\":\"\",\"openProjectIds\":[\"" + FIRST
                        + "\",\"\",\"" + LAST + "\"]}");
        Files.writeString(dir.resolve("workspace-state.json"), session(global));
        Path windows = Files.createDirectories(dir.resolve("windows"));
        Files.writeString(windows.resolve("aaaa1111.json"), session(first));
        Files.writeString(windows.resolve("bbbb2222.json"), session(last));
        return new Seeded(dir, global, first, last);
    }

    private static String session(Path file) {
        return "{\"schemaVersion\":1,\"openFiles\":[{\"path\":\"" + file + "\"}],\"activeFile\":\"" + file + "\"}";
    }

    /** A launched window manager whose restore queue only advances when {@link #step} is called. */
    private static final class Launched {
        final SharedConfig shared;
        final WindowManager wm;
        final ConfigManager bootstrap;
        final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();

        Launched(Path dir) throws Exception {
            Object[] made = FxTestSupport.callOnFx(() -> {
                ConfigManager bootstrap = new ConfigManager(dir);
                bootstrap.load();
                SharedConfig s = bootstrap.shared();
                KeymapManager keymap = new KeymapManager();
                keymap.loadNamed(s.getSettings().getKeymap());
                WindowManager manager = new WindowManager(s, keymap, null);
                manager.adoptBootstrapConfig(bootstrap); // as App.start does
                manager.restoreScheduler = scheduled::add;
                manager.launch(new Stage(), null, List.of(), false, false, null, false, null, false);
                return new Object[] {s, manager, bootstrap};
            });
            shared = (SharedConfig) made[0];
            wm = (WindowManager) made[1];
            bootstrap = (ConfigManager) made[2];
        }

        /** The {@link ConfigManager} of the window restored for {@code key}. */
        ConfigManager configOf(String key) throws Exception {
            return FxTestSupport.callOnFx(() -> {
                for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                    if (key.equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                        return (ConfigManager) FxTestSupport.call(holder, "config", new Class<?>[] {});
                    }
                }
                throw new AssertionError("no window " + key);
            });
        }

        List<String> keys() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                List<String> keys = new ArrayList<>();
                for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                    keys.add((String) FxTestSupport.call(holder, "key", new Class<?>[] {}));
                }
                return keys;
            });
        }

        boolean allShowing() throws Exception {
            return FxTestSupport.callOnFx(() -> {
                for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                    if (!((Stage) FxTestSupport.call(holder, "stage", new Class<?>[] {})).isShowing()) {
                        return false;
                    }
                }
                return true;
            });
        }

        void step() throws Exception {
            FxTestSupport.runOnFx(() -> scheduled.remove().run());
        }

        void dispose() throws Exception {
            FxTestSupport.runOnFx(() -> {
                for (Object holder : List.copyOf(FxTestSupport.<List<?>>field(wm, "windows"))) {
                    MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                    c.disposePlugins();
                    c.disposeWindow();
                    ((Stage) FxTestSupport.call(holder, "stage", new Class<?>[] {})).close();
                }
                FxTestSupport.<com.editora.plugin.PluginManager>field(wm, "pluginManager")
                        .closeAll();
            });
            FxTestSupport.drainFx();
            shared.shutdown();
        }
    }

    @Test
    void thePrimaryWindowIsShownBeforeTheOthersAreBuiltAndAllExistOnceTheQueueDrains() throws Exception {
        Seeded seeded = seed();
        Launched app = new Launched(seeded.dir());
        try {
            assertEquals(List.of(""), app.keys(), "only the primary window is built by launch itself");
            assertTrue(app.allShowing(), "and it is already showing");
            assertTrue(FxTestSupport.callOnFx(app.wm::restorePending));
            assertEquals(1, app.scheduled.size(), "one continuation, not one per window");

            app.step();
            assertEquals(List.of("", FIRST), app.keys(), "one window per step, in saved order");
            assertTrue(FxTestSupport.callOnFx(app.wm::restorePending));

            app.step();
            assertEquals(List.of("", FIRST, LAST), app.keys());
            assertTrue(app.allShowing());
            assertFalse(FxTestSupport.callOnFx(app.wm::restorePending));
            assertTrue(app.scheduled.isEmpty(), "nothing is scheduled once the set is complete");

            // The default window adopted the launch's already-parsed session instead of re-reading the file;
            // the others have a session file of their own to read.
            assertSame(app.bootstrap, app.configOf(""));
            assertNotSame(app.bootstrap, app.configOf(FIRST));

            // Both untitled sessions survived the session-file GC, which ran against the whole restore set
            // while only the first window existed.
            assertTrue(Files.exists(seeded.dir().resolve("windows/aaaa1111.json")));
            assertTrue(Files.exists(seeded.dir().resolve("windows/bbbb2222.json")));
        } finally {
            app.dispose();
        }
    }

    @Test
    void quittingPartWayKeepsTheUnbuiltWindowsAndTheirSessions() throws Exception {
        Seeded seeded = seed();
        String firstSession = Files.readString(seeded.dir().resolve("windows/aaaa1111.json"));
        String lastSession = Files.readString(seeded.dir().resolve("windows/bbbb2222.json"));
        Launched app = new Launched(seeded.dir());
        try {
            app.step(); // global + FIRST are built; LAST is still pending
            assertEquals(List.of("", FIRST), app.keys());

            // The debounced open-set reconcile must not write the half-built set...
            FxTestSupport.runOnFx(() -> FxTestSupport.invoke(app.wm, "reconcileOpenSet"));
            // ...and a quit must not build the rest underneath itself.
            assertTrue(FxTestSupport.callOnFx(app.wm::confirmCloseAllWindows));
            while (!app.scheduled.isEmpty()) {
                app.step();
            }
            assertEquals(List.of("", FIRST), app.keys(), "no window is built after the quit was confirmed");
            app.shared.flushWrites();

            String index = Files.readString(seeded.dir().resolve("projects.json"));
            assertTrue(index.contains(LAST), "the unbuilt window is still in the saved open set: " + index);
            assertTrue(index.contains(FIRST));
            assertEquals(lastSession, Files.readString(seeded.dir().resolve("windows/bbbb2222.json")));
            assertTrue(
                    Files.readString(seeded.dir().resolve("windows/aaaa1111.json"))
                            .contains(seeded.firstFile().getFileName().toString()),
                    "a built window's session still names its file (was: " + firstSession + ")");
        } finally {
            app.dispose();
        }
    }

    @Test
    void anExternalLaunchForAFileOfAPendingWindowWaitsForThatWindow() throws Exception {
        Seeded seeded = seed();
        Launched app = new Launched(seeded.dir());
        try {
            FxTestSupport.runOnFx(() -> app.wm.openExternalLaunchInNewWindow(
                    List.of(new MainController.OpenTarget(seeded.lastFile(), -1, -1)), false, false, false));
            assertEquals(List.of(""), app.keys(), "decided once every window exists, not against a partial set");
            app.step();
            app.step();
            // The window restoring that file took the launch; no fourth window was opened over it.
            assertEquals(List.of("", FIRST, LAST), app.keys());
        } finally {
            app.dispose();
        }
    }
}
