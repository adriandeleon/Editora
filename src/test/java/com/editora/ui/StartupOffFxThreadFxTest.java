package com.editora.ui;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.stage.Stage;

import com.editora.App;
import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.SharedConfig;
import com.editora.editor.UiZoom;
import com.editora.perf.BlockingSites;
import com.editora.plugin.PluginManager;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.2.0), start-up: before the first window is shown, the FX thread waits once for the
 * config-prefetch thread and reads nothing of the user's itself. The test runs the launch's own prefetch
 * ({@code App.prefetchConfig}, on its real {@code config-prefetch} thread), builds the windows on the FX thread
 * as {@code App.start} does, and listens to {@link BlockingSites}, which each place that start-up or a window's
 * set-up used to read, write, list or stat on the FX thread names as it touches the disk.
 *
 * <p>What it cannot see: disk work at a place that does not name itself (it checks the named places, not every
 * file call); {@code App.start} itself, which the test does not run (it repeats its few calls); and what a
 * window does after it is built and the restore drained (a user's command, a later settings apply).
 */
@Tag("fx")
class StartupOffFxThreadFxTest {

    private static final String UNTITLED = "untitled:aaaa1111";

    /** Every {@link BlockingSites#enter} heard: the site and the thread's name, and whether it was the FX thread. */
    private record Heard(String site, String thread, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private final ArrayDeque<Runnable> scheduled = new ArrayDeque<>();
    private Path dir;
    private Path emptyDir;
    private SharedConfig shared;
    private WindowManager wm;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        if (wm != null) {
            FxTestSupport.runOnFx(() -> {
                for (Object holder : List.copyOf(FxTestSupport.<List<?>>field(wm, "windows"))) {
                    MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                    c.disposePlugins();
                    c.disposeWindow();
                    ((Stage) FxTestSupport.call(holder, "stage", new Class<?>[] {})).close();
                }
                FxTestSupport.<PluginManager>field(wm, "pluginManager").closeAll();
            });
            FxTestSupport.drainFx();
        }
        if (shared != null) {
            shared.shutdown();
        }
        // What the launch set process-wide, put back as the other tests in this JVM expect it.
        DebugLog.detachFileForTest();
        com.editora.process.ProcessRegistry.setLedgerFile(null);
        FxTestSupport.runOnFx(() -> {
            UiZoom.init(100);
            // The launch made the user's theme the app's, at their zoom: its sheet set the whole JVM's fonts and
            // sizes, and later tests in this JVM measured rows and cards in it. The default theme again, at 100%.
            Themes.applyAppTheme(Themes.DEFAULT);
        });
        if (emptyDir != null) {
            Themes.loadUserThemes(emptyDir);
        }
        com.editora.i18n.Messages.init("en");
    }

    /**
     * A config folder as a user's after some use: a UI zoom other than 100% (one no other test here uses: the
     * zoomed sheets are kept process-wide), a theme of their own, recent files and search
     * history, a project window (with project settings) and an untitled window open, a crashed run's process
     * ledger and MCP endpoint left behind.
     */
    private void seed(int uiZoom) throws Exception {
        dir = Files.createTempDirectory("editora-startup-offfx");
        emptyDir = Files.createDirectories(dir.resolve("no-themes"));
        Path files = Files.createDirectories(dir.resolve("files"));
        Path recent = Files.writeString(files.resolve("recent.txt"), "recent\n");
        Path inUntitled = Files.writeString(files.resolve("untitled.txt"), "untitled\n");
        Path root = Files.createDirectories(dir.resolve("project"));
        Files.createDirectories(root.resolve(".editora"));
        Files.writeString(root.resolve(".editora/settings.json"), "{\"editor\":{\"tabSize\":3}}");

        ConfigManager seeding = new ConfigManager(dir);
        seeding.load();
        seeding.shared().getSettings().setProjectSupport(true);
        seeding.shared().getSettings().setUiZoom(uiZoom);
        seeding.shared().getSettings().setLspSupport(false);
        seeding.shared().getSettings().setDebugSupport(true); // its set-up looks for java-debug
        seeding.shared().getSettings().setRemoteExecution(false);
        Project project = seeding.shared().projects().createOrGet("project", root);
        seeding.shared().projects().setActive(project.id());
        seeding.shared().projects().markOpen(project.id());
        seeding.shared().projects().markOpen(UNTITLED);
        seeding.shared().saveSettings();
        seeding.shared().projects().save();
        seeding.shared().recentFiles().add(recent);
        seeding.shared().searchHistory().add("needle");
        seeding.shared().flushWrites();
        seeding.shared().shutdown();

        Path windows = Files.createDirectories(dir.resolve("windows"));
        Files.writeString(
                windows.resolve("aaaa1111.json"),
                "{\"schemaVersion\":1,\"openFiles\":[{\"path\":\"" + inUntitled + "\"}],\"activeFile\":\"" + inUntitled
                        + "\"}");
        Files.createDirectories(dir.resolve("themes"));
        Files.writeString(
                dir.resolve("themes/my-dusk.css"), ".root { -color-bg-default: #101010; -color-fg-default: #eeeeee; }");
        // A crashed run's: a ledger row of a dead process with no live owner, and an endpoint of a dead server.
        Files.writeString(dir.resolve("spawned-servers.txt"), "999999999\t1\t/bin/false\n");
        Files.writeString(dir.resolve("mcp-endpoint.json"), "{\"pid\":999999999,\"startMillis\":1,\"port\":1}");
    }

    /** {@code App.prefetchConfig} on its own thread, as {@code main} starts it, and its result. */
    private Object prefetch() throws Exception {
        Method m = App.class.getDeclaredMethod("prefetchConfig", Path.class, List.class, CountDownLatch.class);
        m.setAccessible(true);
        CompletableFuture<?> future = (CompletableFuture<?>) m.invoke(null, dir, List.of(), new CountDownLatch(0));
        return future.get(60, TimeUnit.SECONDS);
    }

    private static Object member(Object boot, String name) throws Exception {
        Method m = boot.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        return m.invoke(boot);
    }

    /** As {@code App.start} does after {@code takeBootstrap}: the zoom, the keymap, the manager, the launch. */
    private void launch(Object boot) throws Exception {
        ConfigManager bootstrap = (ConfigManager) member(boot, "manager");
        shared = bootstrap.shared();
        FxTestSupport.runOnFx(() -> {
            try {
                UiZoom.init(shared.getSettings().getUiZoom());
                javafx.application.Application.setUserAgentStylesheet(
                        Themes.userAgentStylesheetFor(shared.getSettings().getTheme()));
                KeymapManager keymap = new KeymapManager();
                keymap.loadNamed(shared.getSettings().getKeymap());
                keymap.applyOverrides(UserKeys.overrides(shared.getSettings()));
                wm = new WindowManager(shared, keymap, null, (PluginManager) member(boot, "plugins"));
                wm.restoreScheduler = scheduled::add;
                wm.adoptBootstrapConfig(bootstrap, (ConfigManager) member(boot, "projectWindow"));
                wm.launch(new Stage(), null, List.of(), false, false, null, false, null, false);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Builds the restored windows one by one, as their turns come (a read still running defers its turn). */
    private void drainRestore() throws Exception {
        for (int i = 0; i < 400 && FxTestSupport.callOnFx(wm::restorePending); i++) {
            // a read still running queues its window's turn when it lands
            Await.holdsWithin(() -> onFx(() -> !scheduled.isEmpty() || !wm.restorePending()), Duration.ofSeconds(10));
            Runnable next = FxTestSupport.callOnFx(scheduled::poll);
            if (next != null) {
                FxTestSupport.runOnFx(next);
            }
        }
        assertFalse(FxTestSupport.callOnFx(wm::restorePending), "every restored window was built");
        FxTestSupport.drainFx();
    }

    private List<String> sitesOn(boolean fx) {
        List<String> out = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx() == fx) {
                out.add(h.site() + " @" + h.thread());
            }
        }
        return out;
    }

    private boolean heardOnPrefetch(String site) {
        return heard.stream()
                .anyMatch(h -> h.site().startsWith(site) && h.thread().equals("config-prefetch"));
    }

    private List<String> windowKeys() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<String> keys = new ArrayList<>();
            for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                keys.add((String) FxTestSupport.call(holder, "key", new Class<?>[] {}));
            }
            return keys;
        });
    }

    private ConfigManager configOf(String key) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            for (Object holder : FxTestSupport.<List<?>>field(wm, "windows")) {
                if (key.equals(FxTestSupport.call(holder, "key", new Class<?>[] {}))) {
                    return (ConfigManager) FxTestSupport.call(holder, "config", new Class<?>[] {});
                }
            }
            throw new AssertionError("no window " + key);
        });
    }

    @Test
    void theLaunchAndEveryRestoredWindowsSetUpTouchNoDiskOnTheFxThread() throws Exception {
        seed(120);
        DebugLog.install();
        DebugLog.append("before the launch: " + dir); // logged before the session log is opened
        BlockingSites.listenForTest(
                (site, thread) -> heard.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread())));

        Object boot = prefetch();
        launch(boot);
        drainRestore();
        BlockingSites.listenForTest(null);

        // The windows were built from what the prefetch thread read; the FX thread named no disk work, except
        // the first window's own icons, which the rule allows it (and which may be read by an earlier test).
        List<String> onFx = sitesOn(true);
        onFx.removeIf(s -> s.startsWith("AppIcons.read"));
        assertEquals(List.of(), onFx, "disk work on the FX thread; all heard: " + heard);

        // ...and the places did run, where they now belong.
        for (String site : List.of(
                "InstanceLock.claim",
                "DebugLog.attachFile",
                "ProcessRegistry.reapOrphans",
                "McpServer.reapStaleEndpoint",
                "UserThemes.load",
                "UiZoom.build",
                "readVersioned recent-files.json",
                "readVersioned search-history.json",
                "ProjectSettings.load",
                "lsp-own-copies",
                "java-debug-locate",
                "XmlBundledSchemas.ensure",
                "LspManager.makeLooseRoot",
                "tool-path-is-file")) {
            assertTrue(heardOnPrefetch(site), site + " ran on the config-prefetch thread; heard: " + heard);
        }
        assertTrue(
                heard.stream()
                        .anyMatch(h -> h.site().equals("readVersioned aaaa1111.json")
                                && h.thread().equals("window-session-read")),
                "the restored window's session was read on a thread of its own; heard: " + heard);

        // Each moved thing still did its job.
        assertTrue(shared.isPrimaryInstance(), "the launch claimed the config folder");
        ConfigManager second = new ConfigManager(dir);
        try {
            assertFalse(second.shared().claimInstance(), "a second claim is refused while the first holds it");
        } finally {
            second.shared().shutdown();
        }
        assertFalse(Files.exists(dir.resolve("spawned-servers.txt")), "the crashed run's ledger was reaped");
        assertFalse(Files.exists(dir.resolve("mcp-endpoint.json")), "the stale MCP endpoint was removed");
        assertTrue(Themes.names().contains("My Dusk"), "the user's theme loaded: " + Themes.names());
        assertTrue(
                FxTestSupport.callOnFx(() -> shared.recentFiles().getList().stream()
                        .anyMatch(p -> p.getFileName().toString().equals("recent.txt"))),
                "the recent files are there for the first window");
        assertTrue(
                FxTestSupport.callOnFx(() -> shared.searchHistory().getList().contains("needle")),
                "and the search history");
        String log = Files.readString(dir.resolve("editora-session.log"));
        assertTrue(log.contains("before the launch: " + dir), "the session log holds what was logged before it");

        // The restored windows: the project one first, the untitled one after it.
        List<String> keys = windowKeys();
        assertEquals(2, keys.size(), "every saved window was restored: " + keys);
        assertEquals(UNTITLED, keys.get(1));
        assertTrue(
                configOf(UNTITLED).getWorkspaceState().getOpenFiles().stream()
                        .anyMatch(f -> f.getPath().endsWith("untitled.txt")),
                "the untitled window got its own session");
    }

    @Test
    void aLaterWindowReadsTheIconsNoMore() throws Exception {
        seed(130);
        Object boot = prefetch();
        launch(boot);
        drainRestore();
        BlockingSites.listenForTest(
                (site, thread) -> heard.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread())));
        FxTestSupport.runOnFx(wm::newWindow);
        BlockingSites.listenForTest(null);
        assertFalse(
                heard.stream().anyMatch(h -> h.site().equals("AppIcons.read")),
                "the window icons are the first window's, kept: " + heard);
    }

    /** {@code App.start} has the window icons decoded on a thread of their own while it waits for the config. */
    @Test
    void theIconsReadAheadAreDecodedOffTheFxThread() throws Exception {
        AppIcons.forgetForTest();
        List<Heard> reads = new java.util.concurrent.CopyOnWriteArrayList<>();
        BlockingSites.listenForTest((site, thread) -> {
            if (site.equals("AppIcons.read")) {
                reads.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread()));
            }
        });
        try {
            FxTestSupport.runOnFx(AppIcons::readAhead); // as start() calls it, on the FX thread
            FxTestSupport.awaitFxCondition(
                    () -> reads.size() == AppIcons.WINDOW_SIZES.length, "every window icon read");
        } finally {
            BlockingSites.listenForTest(null);
        }
        assertEquals(AppIcons.WINDOW_SIZES.length, reads.size(), "every window icon was read: " + reads);
        assertTrue(reads.stream().noneMatch(Heard::fx), "read off the FX thread: " + reads);
        FxTestSupport.runOnFx(() -> {
            for (int size : AppIcons.WINDOW_SIZES) {
                assertTrue(AppIcons.icon(size) != null, size + " px is decoded and kept");
            }
        });
    }

    @Test
    void theIconsAreReadOnceAndAMissingOneIsNone() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.image.Image first = AppIcons.icon(16);
            assertTrue(first != null && first == AppIcons.icon(16), "one image, kept");
            assertTrue(AppIcons.icon(999) == null, "a size that is not bundled");
        });
    }

    /** {@code probe}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> probe) {
        try {
            return FxTestSupport.callOnFx(probe);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
