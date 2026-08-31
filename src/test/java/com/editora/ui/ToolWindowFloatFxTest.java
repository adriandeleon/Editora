package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Floating a tool window out into its own stage, and docking it back.
 *
 * <p>Needs a real (if headless) stage throughout: the thing being tested is re-parenting a live node
 * between two scenes, which is exactly what a non-toolkit test cannot reach.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowFloatFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowManager manager, ToolWindow a, ToolWindow b, ConfigManager config, Stage owner) {
        Map<ToolWindow, Stage> stages() {
            return FxTestSupport.field(manager, "floatingStages");
        }

        Map<ToolWindow, Region> panels() {
            return FxTestSupport.field(manager, "panels");
        }

        SplitPane hSplit() {
            return FxTestSupport.field(manager, "hSplit");
        }
    }

    private static ToolWindow window(String id, ToolWindow.Side side) {
        return new ToolWindow(id, id, side, () -> new Label("i"), new Label(id), "tool." + id);
    }

    /** Builds the manager inside a shown stage — a floating window needs an owner to attach to. */
    private static Rig rig() throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-float");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        BorderPane workspace = new BorderPane();
        ToolWindowManager m = new ToolWindowManager(workspace, new Region(), config, new KeymapManager());
        ToolWindow a = window("alpha", ToolWindow.Side.LEFT);
        ToolWindow b = window("beta", ToolWindow.Side.LEFT);
        m.register(a);
        m.register(b);
        Stage owner = new Stage();
        owner.setScene(new Scene(workspace, 1200, 800));
        owner.setX(0);
        owner.setY(0);
        owner.show();
        return new Rig(m, a, b, config, owner);
    }

    private static void close(Rig r) {
        r.owner().close();
    }

    @Test
    void floatingMovesThePanelOutOfTheDock() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            int docked = r.hSplit().getItems().size();

            r.manager().toggleFloating(r.a());

            assertTrue(r.manager().isFloating(r.a()));
            assertTrue(r.manager().isOpen(r.a()), "a floating window is still open");
            assertEquals(docked - 1, r.hSplit().getItems().size(), "the panel should have left the outer split");
            assertNotNull(r.stages().get(r.a()));
            assertTrue(r.stages().get(r.a()).isShowing());
            close(r);
        });
    }

    /** The panel is one node: it has to be released by the stage's scene before the split can take it back. */
    @Test
    void dockingPutsThePanelBackOnItsSide() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            Region panel = r.panels().get(r.a());
            r.manager().toggleFloating(r.a());

            r.manager().toggleFloating(r.a());

            assertFalse(r.manager().isFloating(r.a()));
            assertTrue(r.manager().isOpen(r.a()));
            assertTrue(r.hSplit().getItems().contains(panel), "the same panel node must return to the split");
            assertTrue(r.stages().isEmpty());
            close(r);
        });
    }

    /** The stylesheets are on the owner's scene, not the user-agent theme — an unstyled panel is the bug. */
    @Test
    void theFloatingSceneCarriesTheAppStylesheets() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.owner().getScene().getStylesheets().add("data:text/css,.x{}");
            r.manager().open(r.a());

            r.manager().toggleFloating(r.a());

            assertEquals(
                    r.owner().getScene().getStylesheets(),
                    r.stages().get(r.a()).getScene().getStylesheets());
            close(r);
        });
    }

    /** Closing the stage closes the window but retains floating as its presentation for the next open. */
    @Test
    void closingAFloatingWindowClosesTheToolWindow() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());

            r.manager().close(r.a());

            assertFalse(r.manager().isOpen(r.a()));
            assertFalse(r.manager().isFloating(r.a()));
            assertTrue(r.stages().isEmpty());
            assertFalse(r.panels().containsKey(r.a()), "the panel should have been dropped with the window");
            assertEquals("FLOATING", r.config().getWorkspaceState().getToolWindowPresentationModes().get("alpha"));
            close(r);
        });
    }

    @Test
    void reopeningAClosedFloatingWindowFloatsItAgain() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());
            r.manager().close(r.a());

            r.manager().open(r.a());

            assertTrue(r.manager().isFloating(r.a()));
            assertTrue(r.stages().get(r.a()).isShowing());
            close(r);
        });
    }

    @Test
    void dockingBackMakesDockedTheRememberedPresentation() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());
            r.manager().toggleFloating(r.a());
            r.manager().close(r.a());

            r.manager().open(r.a());

            assertFalse(r.manager().isFloating(r.a()));
            assertEquals("DOCKED", r.config().getWorkspaceState().getToolWindowPresentationModes().get("alpha"));
            close(r);
        });
    }

    @Test
    void boundsArePersistedAndTheFloatingSetIsRecorded() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());
            Stage stage = r.stages().get(r.a());
            stage.setX(120);
            stage.setY(140);
            stage.setWidth(360);
            stage.setHeight(480);

            assertEquals(List.of("alpha"), r.config().getWorkspaceState().getFloatingToolWindows());
            List<Double> b =
                    r.config().getWorkspaceState().getFloatingToolWindowBounds().get("alpha");
            assertEquals(List.of(120.0, 140.0, 360.0, 480.0), b);
            close(r);
        });
    }

    /** Docking back frees the side, so the window that took its place is not disturbed. */
    @Test
    void aFloatingWindowIsNotCountedAgainstItsSide() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());

            r.manager().open(r.b()); // the side is free now

            assertTrue(r.manager().isOpen(r.a()), "floating alpha must not be closed by docking beta");
            assertTrue(r.manager().isOpen(r.b()));
            assertSame(r.b(), r.manager().getOpenToolWindows().get(0));
            close(r);
        });
    }

    /** Zen closes everything, which has to include the windows that are no longer in the dock. */
    @Test
    void closeAllOpenIncludesFloatingWindows() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());
            r.manager().open(r.b());

            List<String> closed = r.manager().closeAllOpen();

            assertTrue(closed.contains("alpha"));
            assertTrue(closed.contains("beta"));
            assertTrue(r.stages().isEmpty());
            close(r);
        });
    }

    /** Maximize is a docking concept; asking a detached stage to take over a split must be a no-op. */
    @Test
    void maximizingAFloatingWindowDoesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            r.manager().open(r.a());
            r.manager().toggleFloating(r.a());

            r.manager().toggleMaximized(r.a());

            assertFalse(r.manager().isMaximized(r.a()));
            close(r);
        });
    }

    /**
     * The restart case: a window that was floating when the session was written comes back floating.
     *
     * <p>Restoring is deferred a pulse — {@code restore()} runs during window construction, before there is
     * a Scene and so before there is an owner for a floating stage — hence the second hop onto the FX
     * thread here, which lets that queued work run before anything is asserted.
     */
    @Test
    void aFloatingWindowIsRestoredFloating() throws Exception {
        Rig[] rig = new Rig[1];
        FxTestSupport.runOnFx(() -> {
            Rig r = rigUnchecked();
            rig[0] = r;
            r.config().getWorkspaceState().getFloatingToolWindows().add("alpha");
            r.config().getWorkspaceState().getOpenToolWindows().put("LEFT", List.of("beta"));

            r.manager().restore();

            assertTrue(r.manager().isOpen(r.b()), "the docked side restores immediately");
            assertFalse(r.manager().isFloating(r.a()), "the float is deferred to the next pulse");
        });
        FxTestSupport.runOnFx(() -> {
            Rig r = rig[0];
            assertTrue(r.manager().isFloating(r.a()), "the floating window did not come back detached");
            assertTrue(r.manager().isOpen(r.b()), "restoring a float must not disturb the docked side");
            close(r);
        });
    }

    private static Rig rigUnchecked() {
        try {
            return rig();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
