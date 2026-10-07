package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.stage.Stage;

import com.editora.command.KeymapManager;
import com.editora.config.ConfigManager;
import com.editora.config.SharedConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The launch-time sweep of untitled-window session files (data-loss review C4), through the real
 * {@link WindowManager#launch}.
 *
 * <p>It deletes every {@code windows/<uuid>.json} that is not in the open set. When {@code projects.json}
 * fails to load, that set is the empty default — so a zero-length, truncated or newer index used to cost every
 * untitled window its tabs, layout, run configurations, program arguments and debug watches.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LaunchSessionGcFxTest {

    private static final String GOOD = "{\"schemaVersion\":2,\"projects\":[],\"activeProjectId\":\"\","
            + "\"openProjectIds\":[\"\",\"untitled:aaaa1111\"]}";

    private static final String SESSION = "{\"schemaVersion\":12,\"debugWatches\":[\"MY-WATCH\"],"
            + "\"programArgs\":{\"/x/M.java\":\"--my-args\"},\"runConfigurations\":[],\"openFiles\":[]}";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<String> launchWith(String index) throws Exception {
        Path dir = Files.createTempDirectory("editora-launch-gc");
        Path windows = Files.createDirectories(dir.resolve("windows"));
        Files.writeString(windows.resolve("aaaa1111.json"), SESSION);
        Files.writeString(windows.resolve("bbbb2222.json"), SESSION); // closed in an earlier session
        Files.writeString(dir.resolve("projects.json"), index);

        Object[] made = FxTestSupport.callOnFx(() -> {
            ConfigManager bootstrap = new ConfigManager(dir);
            bootstrap.load();
            SharedConfig shared = bootstrap.shared();
            KeymapManager keymap = new KeymapManager();
            keymap.loadNamed(shared.getSettings().getKeymap());
            WindowManager manager = new WindowManager(shared, keymap, null);
            manager.adoptBootstrapConfig(bootstrap);
            manager.restoreScheduler = task -> {}; // the sweep runs inside launch(); the other windows are not needed
            manager.launch(new Stage(), null, List.of(), false, false, null, false, null, false);
            return new Object[] {shared, manager};
        });
        List<String> left;
        try (var files = Files.list(windows)) {
            left = files.map(p -> p.getFileName().toString()).sorted().toList();
        }
        SharedConfig shared = (SharedConfig) made[0];
        WindowManager wm = (WindowManager) made[1];
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
        return left;
    }

    @org.junit.jupiter.api.Test
    void withAnIndexThatLoadedOnlyTheSessionsOfWindowsNoLongerOpenAreSwept() throws Exception {
        assertEquals(List.of("aaaa1111.json"), launchWith(GOOD));
    }

    @ParameterizedTest(name = "index holding [{0}]")
    @ValueSource(
            strings = {
                "",
                "{\"schemaVersion\":2,\"projects\":[],\"activeProjectId\":\"\",\"openProj",
                "{\"schemaVersion\":99,\"projects\":[],\"activeProjectId\":\"\",\"openProjectIds\":[\"\",\"untitled:aaaa1111\"]}"
            })
    void withAnIndexThatDidNotLoadNoSessionFileIsDeleted(String index) throws Exception {
        assertEquals(List.of("aaaa1111.json", "bbbb2222.json"), launchWith(index));
    }
}
