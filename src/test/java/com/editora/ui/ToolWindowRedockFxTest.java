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

    /**
     * The drop-target highlight goes on at most once, however long the cursor hovers.
     *
     * <p>{@code DRAG_OVER} fires tens of times a second, and the handler used to {@code add} the class on
     * every one of them while both clear paths called {@code remove}, which drops only the first occurrence
     * — so a second of hovering left dozens behind and one removal could not clear them.
     */
    @Test
    void hoveringDoesNotStackTheDropTargetHighlight() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            Pane stripe = r.stripe(ToolWindow.Side.LEFT);
            for (int i = 0; i < 50; i++) {
                markDropTarget(r, stripe, true);
            }
            assertEquals(
                    1,
                    java.util.Collections.frequency(stripe.getStyleClass(), DROP_TARGET),
                    "the highlight class stacked up, so one removal cannot clear it");

            markDropTarget(r, stripe, false);
            assertFalse(stripe.getStyleClass().contains(DROP_TARGET));
        });
    }

    /**
     * Ending the drag clears every stripe, whatever the drop landed on.
     *
     * <p>This is the case that was actually reported: dropping a button onto a NEIGHBOUR to reorder it means
     * the button consumes the event, so the stripe's own DRAG_DROPPED never runs — and sliding from stripe
     * space onto one of its child buttons is not an exit from the stripe, so DRAG_EXITED does not run
     * either. The stripe kept its dashed drop-zone border for the rest of the session.
     */
    @Test
    void endingTheDragClearsTheHighlightOnEveryStripe() throws Exception {
        Rig r = rig();
        FxTestSupport.runOnFx(() -> {
            for (ToolWindow.Side side : ToolWindow.Side.values()) {
                markDropTarget(r, r.stripe(side), true);
            }

            FxTestSupport.call(r.manager(), "setDraggingStripeButton", new Class[] {boolean.class}, false);

            for (ToolWindow.Side side : ToolWindow.Side.values()) {
                assertFalse(
                        r.stripe(side).getStyleClass().contains(DROP_TARGET),
                        side + " kept the drop-zone highlight after the drag ended");
            }
        });
    }

    private static final String DROP_TARGET = "tool-stripe-drop-target";

    /** Drives the manager's own highlight helper — a static method, so the instance is only a handle. */
    private static void markDropTarget(Rig r, Pane stripe, boolean on) {
        FxTestSupport.call(r.manager(), "setStripeDropTarget", new Class[] {Pane.class, boolean.class}, stripe, on);
    }
}
