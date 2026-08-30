package com.editora.ui;

import java.util.List;

import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-configuration group is pinned in the toolbar's fixed tail, not in the customizable icon cluster.
 *
 * <p>A {@link ToolBar} overflows from its end, so anything in the cluster is what a narrow window takes
 * away — which is the wrong thing to lose for the control that starts a run. It therefore sits in the tail
 * beside the project switcher, across the bar's slack from the icons, the way an IDE pins its run widget.
 *
 * <p>Asserted on the live scene graph rather than on the catalog, because the widgets are declared inside
 * the ToolBar in the FXML and only re-parented when the toolbar is built: a rebuild that stopped calling
 * {@code appendFixedTail} would leave them where the FXML put them, and a catalog-only test would not see
 * it.
 */
@Tag("fx")
class RunConfigToolbarPlacementFxTest {

    private static final List<String> GROUP =
            List.of("runConfigCombo", "runConfigRunButton", "runConfigDebugButton", "runConfigStopButton");

    private static FxWindowFixture fx;

    /** Wide enough that the icon cluster fits with room to spare, so the bar has real slack to sit across. */
    private static final double WIDE = 1600;

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        FxTestSupport.runOnFx(() -> {
            Scene scene = FxTestSupport.<Stage>field(fx.controller, "stage").getScene();
            scene.getRoot().resize(WIDE, 800);
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        });
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static Node widget(String field) {
        return FxTestSupport.field(fx.controller, field);
    }

    @Test
    void theWholeGroupLivesInThePinnedTail() throws Exception {
        HBox tail = FxTestSupport.field(fx.controller, "toolbarTail");
        ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");

        for (String field : GROUP) {
            Node n = widget(field);
            assertTrue(
                    FxTestSupport.callOnFx(() -> tail.getChildren().contains(n)),
                    field + " is not in the toolbar's pinned tail");
            assertFalse(
                    FxTestSupport.callOnFx(() -> bar.getItems().contains(n)),
                    field + " is still an item of the ToolBar, so a narrow window can push it into the"
                            + " overflow chevron");
        }
    }

    @Test
    void theGroupIsInOrderAndAheadOfTheProjectSwitcher() throws Exception {
        HBox tail = FxTestSupport.field(fx.controller, "toolbarTail");
        Node combo = FxTestSupport.field(fx.controller, "toolbarProjectCombo");

        List<Integer> positions = FxTestSupport.callOnFx(() ->
                GROUP.stream().map(f -> tail.getChildren().indexOf(widget(f))).toList());
        int projectAt = FxTestSupport.callOnFx(() -> tail.getChildren().indexOf(combo));

        assertEquals(
                positions.stream().sorted().toList(),
                positions,
                "the selector and its Run/Debug/Stop buttons are out of order in the tail: " + positions);
        assertTrue(projectAt >= 0, "precondition: the project switcher is in the tail");
        assertTrue(
                positions.get(positions.size() - 1) < projectAt,
                "the run-configuration group should sit left of the project switcher, but the last of it is at "
                        + positions.get(positions.size() - 1) + " and the switcher at " + projectAt);
    }

    /**
     * The point of the move, in pixels: the group sits across the bar's slack from the icons rather than
     * hard against the last of them. Only a layout pass at a real width shows this, hence the sized scene.
     */
    @Test
    void theGroupSitsAcrossTheBarsSlackFromTheIconCluster() throws Exception {
        ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");
        Node combo = widget("runConfigCombo");

        double gap = FxTestSupport.callOnFx(() -> {
            double clusterRight = bar.getItems().stream()
                    .filter(Node::isManaged)
                    .map(n -> n.localToScene(n.getBoundsInLocal()))
                    .mapToDouble(Bounds::getMaxX)
                    .max()
                    .orElse(0);
            return combo.localToScene(combo.getBoundsInLocal()).getMinX() - clusterRight;
        });

        assertTrue(
                gap > 24,
                "the run-configuration group is only " + Math.round(gap) + "px right of the last icon — it is"
                        + " sitting beside the cluster rather than over at the right end of the bar");
    }

    /**
     * The catalog must not offer the group as a draggable item as well: it is one set of nodes, so an
     * entry that put it back in the cluster would move it out of the tail on the next rebuild.
     */
    @Test
    void theCatalogNoLongerOffersTheGroupAsACustomizableItem() {
        for (String id : List.of(
                "toolbar.runConfig", "toolbar.runConfig.run", "toolbar.runConfig.debug", "toolbar.runConfig.stop")) {
            assertFalse(
                    com.editora.toolbar.ToolbarCatalog.isKnownId(id),
                    id + " is still a catalog item, so it can be dragged back into the cluster it no longer"
                            + " belongs to");
        }
        assertFalse(
                com.editora.toolbar.ToolbarCatalog.defaultLayout().stream()
                        .anyMatch(t -> t.startsWith("toolbar.runConfig")),
                "the shipped default layout still places the run-configuration group in the icon cluster");
    }

    /**
     * Recent sits in the icon cluster's file group, between Save As and Undo.
     *
     * <p>It used to be pinned in the fixed tail beside the project switcher, where it read as a project
     * control rather than as one of the ways to get a file on screen. Asserted on the live bar for the same
     * reason the run group above is: the FXML and the catalog both merely <em>declare</em>, and it is
     * {@code appendFixedTail} plus the layout rebuild that decide where a widget actually lands — a
     * regression that put it back in the tail would leave a catalog-only assertion perfectly happy.
     */
    @Test
    void recentSitsBetweenSaveAsAndUndoInTheIconCluster() throws Exception {
        ToolBar bar = FxTestSupport.field(fx.controller, "toolBar");
        HBox tail = FxTestSupport.field(fx.controller, "toolbarTail");
        Node recent = widget("recentButton");

        List<Node> items = bar.getItems();
        assertTrue(items.contains(recent), "Recent is not in the customizable cluster");
        assertFalse(tail.getChildren().contains(recent), "Recent is still pinned in the fixed tail");

        int saveAs = items.indexOf(widget("saveAsButton"));
        int undo = items.indexOf(widget("undoButton"));
        int at = items.indexOf(recent);
        assertTrue(saveAs >= 0 && undo >= 0, "the default layout lost Save As or Undo");
        assertTrue(
                saveAs < at && at < undo,
                "Recent is at index " + at + ", outside Save As (" + saveAs + ") … Undo (" + undo + ")");
    }
}
