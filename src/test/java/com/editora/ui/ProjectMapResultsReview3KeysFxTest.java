package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.Region;

import com.editora.command.CommandRegistry;
import com.editora.command.KeymapManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The usual find keys in the Project Map window under real keymaps (Project Map review 3 B17, B1): Find focuses
 * "Search the map…" (Ctrl+F, Cmd+F, Emacs C-s), and F3 / Shift+F3 on the map step through a search's matches —
 * though the CUA keymap binds F3 to Find, and no tab opens.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class ProjectMapResultsReview3KeysFxTest {

    @TempDir
    Path temp;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void findFocusesTheMapsSearchAndF3StepsUnderTheCuaKeymap() throws Exception {
        findKeys("cua", KeyCode.F, true);
    }

    @Test
    void findFocusesTheMapsSearchAndF3StepsUnderTheEmacsKeymap() throws Exception {
        findKeys("emacs", KeyCode.S, false);
    }

    /** {@code find} is the key that runs {@code find.show} in {@code keymap}, with the shortcut or with Ctrl. */
    private void findKeys(String keymap, KeyCode find, boolean shortcut) throws Exception {
        Path project = Files.createTempDirectory(temp, keymap).toRealPath();
        for (String folder : List.of("one", "two", "three")) {
            Files.writeString(Files.createDirectories(project.resolve(folder)).resolve("hit.txt"), "x");
        }
        ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
        ProjectMapView view = FxTestSupport.field(panel, "mapView");
        Region surface = FxTestSupport.field(view, "surface");
        TextField field = FxTestSupport.field(panel, "mapFilterField");
        TabPane tabs = FxTestSupport.field(fx.controller, "tabPane");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setKeymap(keymap);
            fx.windowManager.reloadSharedKeymap();
            if (panel.getScene() == null) {
                registry.run("tool.project");
            }
            panel.mapWindow().closeQuietly();
            panel.setRoot(project);
            panel.openMapWindow(true);
            field.setText("hit.txt");
            FxTestSupport.<javafx.animation.PauseTransition>field(panel, "mapFilterDebounce")
                    .stop();
            FxTestSupport.invoke(panel, "searchMap");
        });
        waitFor(() -> view.searchResult().matches().size() == 3, keymap + ": three matches");
        List<Path> matches = FxTestSupport.callOnFx(() -> view.searchResult().paths());
        waitFor(() -> matches.getFirst().equals(FxTestSupport.field(surface, "selected")), "the first is selected");
        Scene scene = FxTestSupport.callOnFx(surface::getScene);
        int tabCount = FxTestSupport.callOnFx(() -> tabs.getTabs().size());

        FxTestSupport.runOnFx(() -> {
            String in = keymap + ": ";
            surface.requestFocus();
            press(surface, KeyCode.F3, false, false, false);
            assertEquals(matches.get(1), FxTestSupport.field(surface, "selected"), in + "F3 steps to the next match");
            press(surface, KeyCode.F3, true, false, false);
            assertEquals(matches.getFirst(), FxTestSupport.field(surface, "selected"), in + "Shift+F3 steps back");

            boolean mac = KeymapManager.isMac();
            press(surface, find, false, shortcut ? !mac : true, shortcut && mac);
            assertSame(field, scene.getFocusOwner(), in + "Find focuses the Map's search field");
            assertEquals(tabCount, tabs.getTabs().size(), in + "and opens nothing");
        });
    }

    private static void press(Node target, KeyCode code, boolean shift, boolean ctrl, boolean meta) {
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, false, meta));
    }

    private static void waitFor(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (FxTestSupport.callOnFx(condition::getAsBoolean)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        assertNotNull(null, "timed out: " + message);
    }
}
