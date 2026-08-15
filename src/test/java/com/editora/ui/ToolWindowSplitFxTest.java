package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A side can host two tool windows, stacked in an inner split.
 *
 * <p>The invariant that matters throughout is that the side's contribution to the <em>outer</em> split is
 * swapped in place as it splits and unsplits: a remove-then-add would renumber the outer dividers and lose
 * the side's width, which is what every size assertion here is really guarding.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowSplitFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(
            ToolWindowManager manager, ToolWindow a, ToolWindow b, ToolWindow c, ConfigManager config, Scene scene) {
        void layout() {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }

        SplitPane hSplit() {
            return FxTestSupport.field(manager, "hSplit");
        }

        java.util.Map<ToolWindow.Side, Region> containers() {
            return FxTestSupport.field(manager, "sideContainers");
        }
    }

    private static ToolWindow window(String id, ToolWindow.Side side) {
        return new ToolWindow(id, id, side, () -> new Label("i"), new Label(id), "tool." + id);
    }

    private static Rig rig() throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-split");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        BorderPane workspace = new BorderPane();
        ToolWindowManager m = new ToolWindowManager(workspace, new Region(), config, new KeymapManager());
        ToolWindow a = window("alpha", ToolWindow.Side.LEFT);
        ToolWindow b = window("beta", ToolWindow.Side.LEFT);
        ToolWindow c = window("gamma", ToolWindow.Side.LEFT);
        m.register(a);
        m.register(b);
        m.register(c);
        Scene scene = new Scene(workspace, 1200, 800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(m, a, b, c, config, scene);
    }

    @Test
    void aPlainOpenStillReplaces() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().open(r.b());

            assertFalse(r.manager().isOpen(r.a()), "a plain open must keep replacing, as it always has");
            assertTrue(r.manager().isOpen(r.b()));
        });
    }

    @Test
    void openInSplitKeepsBothOpen() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().openInSplit(r.b());

            assertTrue(r.manager().isOpen(r.a()));
            assertTrue(r.manager().isOpen(r.b()));
            assertEquals(List.of(r.a(), r.b()), r.manager().getOpenToolWindows());
        });
    }

    /** The side contributes one node to the outer split whether it holds one window or two. */
    @Test
    void theOuterSplitStillSeesOneItemPerSide() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            int bare = r.hSplit().getItems().size(); // just the editor
            r.manager().open(r.a());
            assertEquals(bare + 1, r.hSplit().getItems().size());

            r.manager().openInSplit(r.b());
            assertEquals(bare + 1, r.hSplit().getItems().size(), "the second window must not join the OUTER split");
            assertTrue(r.containers().get(ToolWindow.Side.LEFT) instanceof SplitPane, "the side became a split");
        });
    }

    /**
     * The side's width survives splitting and unsplitting. Swapping the container in place is what makes
     * this true — removing the panel and adding an inner split would renumber the dividers and reset it.
     */
    @Test
    void theSideKeepsItsWidthAcrossSplitAndUnsplit() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.layout();
            r.hSplit().setDividerPosition(0, 0.33);
            r.layout();

            r.manager().openInSplit(r.b());
            r.layout();
            assertEquals(0.33, r.hSplit().getDividerPositions()[0], 0.001, "splitting the side moved its edge");

            r.manager().close(r.b());
            r.layout();
            assertEquals(0.33, r.hSplit().getDividerPositions()[0], 0.001, "unsplitting the side moved its edge");
        });
    }

    /** Closing one of two leaves the survivor docked alone, not an inner split holding one panel. */
    @Test
    void closingOneOfTwoCollapsesBackToASinglePanel() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().openInSplit(r.b());
            r.manager().close(r.a());

            assertTrue(r.manager().isOpen(r.b()));
            assertFalse(r.containers().get(ToolWindow.Side.LEFT) instanceof SplitPane);
            assertEquals(List.of(r.b()), r.manager().getOpenToolWindows());
        });
    }

    /** A side holds two; a third joining evicts the companion rather than the window that was there first. */
    @Test
    void aThirdWindowEvictsTheCompanionNotThePrimary() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().openInSplit(r.b());
            r.manager().openInSplit(r.c());

            assertTrue(r.manager().isOpen(r.a()), "the primary should have stayed put");
            assertFalse(r.manager().isOpen(r.b()));
            assertTrue(r.manager().isOpen(r.c()));
        });
    }

    @Test
    void canSplitWithReportsWhetherASecondWindowWouldFit() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            assertFalse(r.manager().canSplitWith(r.b()), "nothing open on the side yet");

            r.manager().open(r.a());
            assertTrue(r.manager().canSplitWith(r.b()));
            assertFalse(r.manager().canSplitWith(r.a()), "already open — it cannot split with itself");

            r.manager().openInSplit(r.b());
            assertFalse(r.manager().canSplitWith(r.c()), "the side is full");
        });
    }

    /** Both windows and the inner divider are written, so the pairing comes back next session. */
    @Test
    void thePairingAndItsDividerArePersisted() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().openInSplit(r.b());
            r.layout();
            SplitPane inner = (SplitPane) r.containers().get(ToolWindow.Side.LEFT);
            inner.setDividerPosition(0, 0.4);
            r.layout();
            r.manager().persistDividers();

            assertEquals(
                    List.of("alpha", "beta"),
                    r.config().getWorkspaceState().getOpenToolWindows().get("LEFT"));
            assertEquals(
                    0.4,
                    r.config().getWorkspaceState().getToolWindowSplitDividers().get("LEFT"),
                    0.001);
        });
    }

    /** A build that predates split sides reads only the single-id fields, so they keep carrying the primary. */
    @Test
    void theLegacySingleIdFieldStillCarriesThePrimary() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().openInSplit(r.b());
            assertEquals("alpha", r.config().getWorkspaceState().getOpenLeftToolWindow());
        });
    }

    @Test
    void restoreReopensBothInOrder() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.config().getWorkspaceState().getOpenToolWindows().put("LEFT", List.of("beta", "alpha"));

            r.manager().restore();

            assertEquals(List.of(r.b(), r.a()), r.manager().getOpenToolWindows(), "stacking order must survive");
        });
    }

    /** Maximizing a split side gives the whole side the space — its panels aren't outer-split items. */
    @Test
    void maximizingASplitSideExpandsBothPanes() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            r.manager().openInSplit(r.b());
            r.layout();

            r.manager().toggleMaximized(r.b());
            r.layout();

            assertTrue(r.manager().isMaximized(r.b()));
            assertEquals(1.0, r.hSplit().getDividerPositions()[0], 0.02, "the side did not take the split");
            assertNotNull(r.containers().get(ToolWindow.Side.LEFT));
        });
    }
}
