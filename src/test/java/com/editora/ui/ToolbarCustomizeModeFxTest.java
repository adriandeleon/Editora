package com.editora.ui;

import java.util.List;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ToolBar;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Customizing the toolbar on the bar itself: the mode, its menu, and what a drop does. A drag cannot be
 * started without a pointer, so a test puts the coordinator where a drag-detected leaves it (the dragged
 * node remembered) and sends the drag events that follow.
 */
@Tag("fx")
class ToolbarCustomizeModeFxTest {

    private static final String A = "file.new";
    private static final String B = "file.find";
    private static final String C = "file.save";

    private FxWindowFixture fx;
    private Settings settings;
    private ToolbarCoordinator toolbar;
    private ToolBar bar;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create();
        settings = fx.shared.getSettings();
        toolbar = FxTestSupport.field(fx.controller, "toolbarCoordinator");
        bar = FxTestSupport.field(fx.controller, "toolBar");
        FxTestSupport.runOnFx(() -> {
            assertNotNull(ToolbarCatalog.item(A), A);
            assertNotNull(ToolbarCatalog.item(B), B);
            assertNotNull(ToolbarCatalog.item(C), C);
            toolbar.setLayout(List.of(A, B, C));
        });
        FxTestSupport.drainFx(); // the rebuild is deferred past the event that asked for it
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            FxTestSupport.<ContextMenu>field(toolbar, "contextMenu").hide();
            SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
            FxTestSupport.<Stage>field(window, "stage").hide();
        });
        fx.dispose();
    }

    private List<Node> nodes() {
        return FxTestSupport.field(toolbar, "customNodes");
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    /** Where a drag-detected on {@code node} leaves the coordinator. */
    private void dragging(Node node) {
        setField("dragSource", node);
        setField("dragIndex", nodes().indexOf(node));
    }

    private void setField(String name, Object value) {
        try {
            java.lang.reflect.Field f = ToolbarCoordinator.class.getDeclaredField(name);
            f.setAccessible(true);
            f.set(toolbar, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** A drag event over {@code target}, on its right half when {@code rightHalf}. */
    private static DragEvent drag(javafx.event.EventType<DragEvent> type, Node target, boolean rightHalf) {
        Bounds inScene = target.localToScene(target.getBoundsInLocal());
        double x = rightHalf ? inScene.getMaxX() - 1 : inScene.getMinX() + 1;
        double y = inScene.getMinY() + inScene.getHeight() / 2;
        return new DragEvent(null, target, type, null, x, y, x, y, TransferMode.MOVE, null, target, null);
    }

    @Test
    void customizeModeIsEnteredAndLeftByTheCommandAndLeftByEscape() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node first = nodes().get(0);
            assertNull(first.getOnDragDetected(), "outside the mode an icon is just a button");

            toolbar.toggleCustomizeMode();
            assertTrue(bar.getStyleClass().contains("toolbar-customizing"));
            assertEquals(tr("status.toolbar.customizeOn"), echo());
            assertNotNull(first.getOnDragDetected());
            assertNotNull(first.getOnDragDropped());

            Event.fireEvent(bar, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.A, false, false, false, false));
            assertTrue(bar.getStyleClass().contains("toolbar-customizing"), "only Escape leaves the mode");

            Event.fireEvent(
                    bar, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertFalse(bar.getStyleClass().contains("toolbar-customizing"));
            assertEquals(tr("status.toolbar.customizeOff"), echo());
            assertNull(first.getOnDragDetected());
            assertNull(first.getOnDragDropped());

            toolbar.toggleCustomizeMode();
            toolbar.toggleCustomizeMode();
            assertFalse(bar.getStyleClass().contains("toolbar-customizing"));
            assertEquals(tr("status.toolbar.customizeOff"), echo());
        });
    }

    @Test
    void theIconsStayDraggableWhenTheBarIsRebuiltDuringCustomizing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            toolbar.toggleCustomizeMode();
            toolbar.setLayout(List.of(C, A));
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            assertEquals(2, nodes().size());
            nodes().forEach(n -> assertNotNull(n.getOnDragDetected(), "the rebuilt icons can be dragged too"));
        });
    }

    @Test
    void aDropOnAnotherIconPutsTheDraggedOneBeforeOrAfterIt() throws Exception {
        FxTestSupport.runOnFx(() -> {
            toolbar.toggleCustomizeMode();
            bar.applyCss();
            bar.layout();
            Node a = nodes().get(0);
            Node c = nodes().get(2);
            dragging(a);

            Event.fireEvent(c, drag(DragEvent.DRAG_OVER, c, true));
            assertTrue(c.getStyleClass().contains("toolbar-drop-after"), "the marker shows where it would land");
            Event.fireEvent(c, drag(DragEvent.DRAG_OVER, c, false));
            assertTrue(c.getStyleClass().contains("toolbar-drop-before"));
            assertFalse(c.getStyleClass().contains("toolbar-drop-after"));
            Event.fireEvent(c, drag(DragEvent.DRAG_EXITED, c, false));
            assertFalse(c.getStyleClass().contains("toolbar-drop-before"));

            Event.fireEvent(a, drag(DragEvent.DRAG_OVER, a, true)); // over itself: nowhere to go
            assertFalse(a.getStyleClass().contains("toolbar-drop-after"));

            Event.fireEvent(c, drag(DragEvent.DRAG_DROPPED, c, true));
            assertEquals(List.of(B, C, A), settings.getToolbarLayout(), "dropped on the right half: after it");
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            bar.applyCss();
            bar.layout();
            assertEquals(List.of(B, C, A), toolbar.effectiveLayout());
            Node last = nodes().get(2);
            Node first = nodes().get(0);
            dragging(last);
            Event.fireEvent(first, drag(DragEvent.DRAG_DROPPED, first, false));
            assertEquals(List.of(A, B, C), settings.getToolbarLayout(), "dropped on the left half: before it");

            // The drag ends over the bar: nothing is removed.
            Bounds on = bar.localToScreen(bar.getBoundsInLocal());
            if (on != null) {
                double x = on.getMinX() + 2;
                double y = on.getMinY() + 2;
                Event.fireEvent(
                        last,
                        new DragEvent(
                                null,
                                last,
                                DragEvent.DRAG_DONE,
                                null,
                                x,
                                y,
                                x,
                                y,
                                TransferMode.MOVE,
                                null,
                                null,
                                null));
                assertEquals(List.of(A, B, C), settings.getToolbarLayout());
            }
            assertNull(FxTestSupport.field(toolbar, "dragSource"), "the gesture is over");
        });
    }

    @Test
    void aDropOnTheBarItselfMovesTheIconToTheEnd() throws Exception {
        FxTestSupport.runOnFx(() -> {
            toolbar.toggleCustomizeMode();
            Event.fireEvent(bar, drag(DragEvent.DRAG_DROPPED, bar, true)); // nothing is being dragged
            assertEquals(List.of(A, B, C), settings.getToolbarLayout());

            dragging(nodes().get(0));
            Event.fireEvent(bar, drag(DragEvent.DRAG_OVER, bar, true));
            Event.fireEvent(bar, drag(DragEvent.DRAG_DROPPED, bar, true));
            assertEquals(List.of(B, C, A), settings.getToolbarLayout());
        });
    }

    @Test
    void anIconDraggedOffTheBarIsRemoved() throws Exception {
        FxTestSupport.runOnFx(() -> {
            toolbar.toggleCustomizeMode();
            Node b = nodes().get(1);
            dragging(b);
            assertTrue(b.getStyleClass().add("toolbar-dragging"));

            Event.fireEvent(
                    b,
                    new DragEvent(
                            null, b, DragEvent.DRAG_DONE, null, -5000, -5000, -5000, -5000, null, null, null, null));

            assertEquals(List.of(A, C), settings.getToolbarLayout());
            assertFalse(b.getStyleClass().contains("toolbar-dragging"));
        });
        FxTestSupport.drainFx();
        assertEquals(2, FxTestSupport.callOnFx(() -> nodes().size()));
    }

    @Test
    void aMoveThatNamesNoIconChangesNothing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            Class<?>[] moveTypes = {int.class, int.class, boolean.class};
            FxTestSupport.call(toolbar, "reorder", moveTypes, -1, 1, true);
            FxTestSupport.call(toolbar, "reorder", moveTypes, 0, 3, true);
            FxTestSupport.call(toolbar, "reorder", moveTypes, 3, 0, false);
            FxTestSupport.call(toolbar, "reorderToEnd", new Class<?>[] {int.class}, -1);
            FxTestSupport.call(toolbar, "reorderToEnd", new Class<?>[] {int.class}, 3);
            assertEquals(List.of(A, B, C), settings.getToolbarLayout());

            FxTestSupport.call(toolbar, "reorder", moveTypes, 2, 0, false); // the last before the first
            assertEquals(List.of(C, A, B), settings.getToolbarLayout());
        });
    }

    @Test
    void theBarsMenuNamesWhatItsFirstItemWillDoAndOpensTheSettingsPage() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ContextMenu menu = FxTestSupport.field(toolbar, "contextMenu");
            Event.fireEvent(
                    bar, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 100, 100, false, null));
            assertTrue(menu.isShowing());
            List<MenuItem> items = menu.getItems();
            assertEquals(tr("toolbar.menu.customize"), items.get(0).getText());
            assertEquals(tr("toolbar.menu.configure"), items.get(2).getText());
            assertEquals(tr("toolbar.menu.restoreDefault"), items.get(3).getText());

            items.get(0).fire();
            assertTrue(bar.getStyleClass().contains("toolbar-customizing"));
            menu.hide();

            Event.fireEvent(
                    bar, new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 100, 100, false, null));
            assertEquals(tr("toolbar.menu.done"), menu.getItems().get(0).getText(), "in the mode it ends it");
            menu.getItems().get(0).fire();
            assertFalse(bar.getStyleClass().contains("toolbar-customizing"));

            menu.getItems().get(3).fire();
            assertEquals(List.of(), settings.getToolbarLayout(), "an empty layout is the shipped default");
            assertEquals(tr("status.toolbar.restored"), echo());
            assertEquals(ToolbarCatalog.defaultLayout(), toolbar.effectiveLayout());

            menu.getItems().get(2).fire();
            SettingsWindow window = FxTestSupport.field(fx.controller, "settingsWindow");
            assertTrue(FxTestSupport.<Stage>field(window, "stage").isShowing());
            javafx.scene.control.ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            assertEquals("TOOLBAR", ((Enum<?>) sidebar.getSelectionModel().getSelectedItem()).name());
        });
    }

    @Test
    void everyCatalogItemHasAnIconAndALabel() throws Exception {
        FxTestSupport.runOnFx(() -> {
            for (ToolbarCatalog.Item item : ToolbarCatalog.items()) {
                assertNotNull(ToolbarCoordinator.iconFor(item.id()), item.id());
                String label = ToolbarCoordinator.labelFor(item.id());
                assertFalse(label.isBlank(), item.id());
                assertEquals(
                        item.commandId() == null ? tr("toolbar.item.recent") : tr("command." + item.commandId()),
                        label,
                        item.id());
            }
            assertNull(ToolbarCoordinator.iconFor(ToolbarCatalog.SEPARATOR), "a separator is drawn as a divider");
            assertEquals(tr("toolbar.item.separator"), ToolbarCoordinator.labelFor(ToolbarCatalog.SEPARATOR));
            assertNull(ToolbarCoordinator.iconFor("an.item.removed.since"));
            assertEquals("an.item.removed.since", ToolbarCoordinator.labelFor("an.item.removed.since"));
            assertNotNull(ToolbarIcons.node(null), "an item with no icon key still gets a glyph");
            assertNotNull(ToolbarIcons.node("a-key-added-later"));
        });
    }

    @Test
    void anExtraItemIsAButtonThatRunsItsCommandAndNamesItInItsTooltip() throws Exception {
        String extra = ToolbarCatalog.items().stream()
                .map(ToolbarCatalog.Item::id)
                .filter(id -> !ToolbarCatalog.defaultLayout().contains(id))
                .filter(id -> ToolbarCatalog.item(id).commandId() != null)
                .filter(id -> ToolbarCatalog.item(id).commandId().equals("view.toggleZen"))
                .findFirst()
                .orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(extra != null, "the catalog offers Zen as an extra");
        FxTestSupport.runOnFx(() -> toolbar.setLayout(List.of(A, extra)));
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            Button button = (Button) nodes().get(1);
            assertTrue(button.getTooltip().getText().startsWith(tr("command.view.toggleZen")));
            com.editora.config.ConfigManager config = FxTestSupport.field(fx.controller, "config");
            assertFalse(config.getWorkspaceState().isZenMode());
            button.fire();
            assertTrue(config.getWorkspaceState().isZenMode());
            button.fire();
            assertFalse(config.getWorkspaceState().isZenMode());
        });
    }
}
