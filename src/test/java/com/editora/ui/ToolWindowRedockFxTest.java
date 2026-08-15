package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-docking a tool window by dragging its stripe button to another stripe.
 *
 * <p>Driven through the two operations the drag handlers call rather than through synthetic drag events:
 * a {@code Dragboard} cannot be constructed outside a real gesture, so a DnD-level test would be testing
 * a mock. The gesture wiring above these is thin; what has to be right — the side move, the resulting
 * order, keeping an open window open, and persistence — is all here.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ToolWindowRedockFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private record Rig(ToolWindowManager manager, ToolWindow a, ToolWindow b, ToolWindow c, ConfigManager config) {
        Pane stripe(ToolWindow.Side side) {
            return FxTestSupport.field(
                    manager,
                    switch (side) {
                        case LEFT -> "leftStripe";
                        case RIGHT -> "rightStripe";
                        case BOTTOM -> "bottomStripe";
                    });
        }

        /** The ids on a stripe, in the order their buttons actually sit there. */
        java.util.List<String> shown(ToolWindow.Side side) {
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (var node : stripe(side).getChildren()) {
                for (ToolWindow tw : java.util.List.of(a, b, c)) {
                    if (FxTestSupport.<java.util.Map<ToolWindow, javafx.scene.control.Button>>field(
                                            manager, "stripeButtons")
                                    .get(tw)
                            == node) {
                        ids.add(tw.getId());
                    }
                }
            }
            return ids;
        }
    }

    private static ToolWindow window(String id, ToolWindow.Side side) {
        return new ToolWindow(id, id, side, () -> new Label("i"), new Label(id), "tool." + id);
    }

    private static Rig rig() throws Exception {
        Path dir = Files.createTempDirectory("editora-tw-redock");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        ConfigManager config = new ConfigManager(shared);
        BorderPane workspace = new BorderPane();
        Region editor = new Region();
        ToolWindowManager m = new ToolWindowManager(workspace, editor, config, new KeymapManager());
        ToolWindow a = window("alpha", ToolWindow.Side.LEFT);
        ToolWindow b = window("beta", ToolWindow.Side.LEFT);
        ToolWindow c = window("gamma", ToolWindow.Side.BOTTOM);
        m.register(a);
        m.register(b);
        m.register(c);
        Scene scene = new Scene(workspace, 1200, 800);
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        return new Rig(m, a, b, c, config);
    }

    @Test
    void droppingOntoAButtonOnAnotherStripeMovesSides() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().dockOnto(r.a(), r.c(), false); // alpha (left) onto gamma (bottom)

            assertEquals(ToolWindow.Side.BOTTOM, r.manager().currentSide(r.a()));
            assertEquals(java.util.List.of("alpha", "gamma"), r.shown(ToolWindow.Side.BOTTOM));
            assertEquals(java.util.List.of("beta"), r.shown(ToolWindow.Side.LEFT), "the button left its old stripe");
        });
    }

    @Test
    void theNewSideIsPersisted() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().dockOnto(r.a(), r.c(), true);
            assertEquals(
                    "BOTTOM",
                    r.config().getWorkspaceState().getToolWindowSides().get("alpha"),
                    "the move must survive a restart");
        });
    }

    /** Dropping on empty stripe space has no neighbour to measure against, so it lands last on that side. */
    @Test
    void droppingOnAStripeLandsLastOnThatSide() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().dockToSideEnd(r.a(), ToolWindow.Side.BOTTOM);

            assertEquals(ToolWindow.Side.BOTTOM, r.manager().currentSide(r.a()));
            assertEquals(java.util.List.of("gamma", "alpha"), r.shown(ToolWindow.Side.BOTTOM));
        });
    }

    /** Dropping onto a neighbour on the SAME side is still just a reorder — the old behaviour, kept. */
    @Test
    void aSameSideDropStillReorders() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            assertEquals(java.util.List.of("alpha", "beta"), r.shown(ToolWindow.Side.LEFT));

            r.manager().dockOnto(r.b(), r.a(), false); // beta before alpha

            assertEquals(java.util.List.of("beta", "alpha"), r.shown(ToolWindow.Side.LEFT));
            assertEquals(ToolWindow.Side.LEFT, r.manager().currentSide(r.b()), "a same-side drop is not a move");
        });
    }

    /**
     * A window that was open stays open on its new side. The side change has to close it — the panel
     * belongs to the old side's split — and a drag that silently shut the window you were looking at
     * reads as having lost it.
     */
    @Test
    void anOpenWindowSurvivesTheMove() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().open(r.a());
            assertTrue(r.manager().isOpen(r.a()));

            r.manager().dockToSideEnd(r.a(), ToolWindow.Side.RIGHT);

            assertTrue(r.manager().isOpen(r.a()), "the window was closed by its own re-dock");
            assertEquals(ToolWindow.Side.RIGHT, r.manager().currentSide(r.a()));
        });
    }

    /** A closed window must not be opened by being dragged. */
    @Test
    void aClosedWindowStaysClosed() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            r.manager().dockToSideEnd(r.a(), ToolWindow.Side.RIGHT);
            assertFalse(r.manager().isOpen(r.a()));
        });
    }

    /**
     * An empty stripe is unmanaged, and an unmanaged node receives no drag events — so without revealing
     * it for the duration of a drag, an empty side is not merely invisible but undroppable, and re-docking
     * would only ever work between sides that already had a button.
     */
    @Test
    void anEmptyStripeIsRevealedWhileDragging() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            Pane right = r.stripe(ToolWindow.Side.RIGHT);
            assertFalse(right.isManaged(), "nothing is docked right, so the stripe starts hidden");

            FxTestSupport.call(r.manager(), "setDraggingStripeButton", new Class[] {boolean.class}, true);
            assertTrue(right.isManaged(), "the empty stripe cannot receive a drop while unmanaged");
            assertTrue(right.isVisible());

            FxTestSupport.call(r.manager(), "setDraggingStripeButton", new Class[] {boolean.class}, false);
            assertFalse(right.isManaged(), "the empty stripe must go away again when the drag ends");
        });
    }
}
