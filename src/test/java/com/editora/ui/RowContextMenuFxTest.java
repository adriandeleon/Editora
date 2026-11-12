package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A keyboard context-menu request (Menu key / Shift+F10) targets the focused tree, list or table, not a cell,
 * so menus installed on cells never opened from the keyboard. {@link RowContextMenu} routes the request to the
 * selected row's cell, with coordinates on that row, and opens a control's own menu at that row too.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RowContextMenuFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** What a cell saw: which row, whether it was a keyboard request, and where the menu would open. */
    private record Request(String row, boolean keyboard, double screenY, double rowBottom) {}

    private static ContextMenuEvent request(boolean keyboard) {
        return new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, keyboard, null);
    }

    private static TreeView<String> tree(List<Request> seen) {
        TreeItem<String> root = new TreeItem<>("root");
        root.setExpanded(true);
        for (String name : List.of("alpha", "beta", "gamma")) {
            root.getChildren().add(new TreeItem<>(name));
        }
        TreeView<String> tree = new TreeView<>(root);
        tree.setCellFactory(t -> new TreeCell<>() {
            {
                // The way the tool windows install their menus: on the cell.
                setOnContextMenuRequested(e -> {
                    if (!isEmpty()) {
                        double bottom = localToScreen(getBoundsInLocal()).getMaxY();
                        seen.add(new Request(getItem(), e.isKeyboardTrigger(), e.getScreenY(), bottom));
                        e.consume();
                    }
                });
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
            }
        });
        RowContextMenu.install(tree);
        return tree;
    }

    private static void show(javafx.scene.Parent root) {
        Stage stage = new Stage();
        stage.setScene(new Scene(root, 300, 300));
        stage.show();
        root.applyCss();
        root.layout();
    }

    @Test
    void aKeyboardRequestOpensTheSelectedRowsMenuAnchoredToThatRow() throws Exception {
        List<Request> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            TreeView<String> tree = tree(seen);
            show(tree);
            tree.getSelectionModel().select(2); // "beta" (row 0 is the root)
            tree.fireEvent(request(true));
            assertEquals(1, seen.size(), "the selected row's cell received the request");
            Request r = seen.get(0);
            assertEquals("beta", r.row());
            assertTrue(r.keyboard());
            assertEquals(r.rowBottom(), r.screenY(), 0.5, "the menu opens at the row, not mid-control");
            tree.getScene().getWindow().hide();
        });
    }

    @Test
    void mouseRequestsAndAnEmptySelectionAreLeftAlone() throws Exception {
        List<Request> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            TreeView<String> tree = tree(seen);
            show(tree);
            tree.getSelectionModel().select(1);
            tree.fireEvent(request(false)); // a right-click on the control's empty area
            assertTrue(seen.isEmpty(), "a mouse request is not re-routed to the selected row");
            tree.getSelectionModel().clearSelection();
            tree.getFocusModel().focus(-1);
            tree.fireEvent(request(true));
            assertTrue(seen.isEmpty(), "nothing selected: nothing to open a menu for");
            tree.getScene().getWindow().hide();
        });
    }

    @Test
    void listsAreRoutedTheSameWay() throws Exception {
        List<String> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = new ListView<>();
            list.getItems().addAll("one", "two", "three");
            list.setCellFactory(v -> new ListCell<>() {
                {
                    setOnContextMenuRequested(e -> {
                        if (!isEmpty()) {
                            seen.add(getItem());
                            e.consume();
                        }
                    });
                }

                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty ? null : item);
                }
            });
            RowContextMenu.install(list);
            show(list);
            list.getSelectionModel().select(2);
            list.fireEvent(request(true));
            assertEquals(List.of("three"), seen);
            list.getScene().getWindow().hide();
        });
    }

    /** A list of {@code rows} rows, shown, with row {@code selected} selected. */
    private static ListView<String> shownList(int rows, int selected) {
        ListView<String> list = new ListView<>();
        for (int i = 0; i < rows; i++) {
            list.getItems().add("row " + i);
        }
        show(list);
        list.getSelectionModel().select(selected);
        return list;
    }

    private static ListCell<?> cellOf(ListView<?> list, int index) {
        for (javafx.scene.Node node : list.lookupAll(".list-cell")) {
            if (node instanceof ListCell<?> cell && cell.getIndex() == index && !cell.isEmpty()) {
                return cell;
            }
        }
        throw new AssertionError("row " + index + " has no cell");
    }

    private static void assertInside(Bounds row, double screenX, double screenY) {
        assertTrue(screenX >= row.getMinX() - 0.5 && screenX <= row.getMaxX() + 0.5, screenX + " in " + row);
        assertTrue(screenY >= row.getMinY() - 0.5 && screenY <= row.getMaxY() + 0.5, screenY + " in " + row);
    }

    /**
     * A control's own menu ({@code setContextMenu}), whose items act on the selected row: JavaFX shows it in the
     * middle of the control for the keyboard; anchored, it opens at the selected row.
     */
    @Test
    void aControlsOwnMenuOpensAtTheSelectedRowForTheKeyboard() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = shownList(5, 3);
            ContextMenu menu = new ContextMenu(new MenuItem("Copy"));
            list.setContextMenu(menu);
            RowContextMenu.anchor(list, menu);
            list.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, null));
            assertTrue(menu.isShowing(), "the keyboard's request opens the menu");
            ListCell<?> cell = cellOf(list, 3);
            assertInside(cell.localToScreen(cell.getBoundsInLocal()), menu.getAnchorX(), menu.getAnchorY());
            menu.hide();

            // A right-click keeps the pointer's place: the control's own handler shows the menu there.
            Bounds first = cellOf(list, 0).localToScreen(cellOf(list, 0).getBoundsInLocal());
            double x = first.getMinX() + 3;
            double y = first.getMinY() + 3;
            list.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 3, 3, x, y, false, null));
            assertTrue(menu.isShowing());
            assertEquals(y, menu.getAnchorY(), 0.5, "a mouse request is not moved to the selected row");
            menu.hide();
            list.getScene().getWindow().hide();
        });
    }

    /** With nothing selected there is no row to anchor to: the request is left to the control. */
    @Test
    void anAnchoredMenuWithNoSelectionIsLeftToTheControl() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = shownList(3, 0);
            list.getSelectionModel().clearSelection();
            list.getFocusModel().focus(-1);
            ContextMenu menu = new ContextMenu(new MenuItem("Copy"));
            ContextMenuEvent request =
                    new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, null);
            assertFalse(RowContextMenu.showAtSelectedRow(list, menu, request.copyFor(list, list)));
            assertFalse(menu.isShowing());
            list.getScene().getWindow().hide();
        });
    }

    /** A selected row scrolled out of view is brought into it before its menu opens: the menu is on the row. */
    @Test
    void aRowOutOfViewIsScrolledIntoViewFirst() throws Exception {
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = shownList(400, 250);
            ContextMenuEvent keyboard =
                    new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, null);
            Point2D at = RowContextMenu.menuPoint(list, keyboard);
            ListCell<?> cell = cellOf(list, 250); // realized now
            assertInside(cell.localToScreen(cell.getBoundsInLocal()), at.getX(), at.getY());
            Bounds control = list.localToScreen(list.getBoundsInLocal());
            assertInside(control, at.getX(), at.getY());

            // A handler that builds its menu per request asks the same question for a right-click: the pointer.
            ContextMenuEvent mouse =
                    new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 7, 9, 107, 209, false, null);
            assertEquals(new Point2D(107, 209), RowContextMenu.menuPoint(list, mouse));
            list.getScene().getWindow().hide();
        });
    }

    /** A table's rows carry the menus (a row factory): the request goes to the selected row, as in a list. */
    @Test
    void tablesAreRoutedToTheSelectedRow() throws Exception {
        List<Request> seen = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            TableView<String> table = new TableView<>();
            TableColumn<String, String> name = new TableColumn<>("Name");
            name.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyStringWrapper(c.getValue()));
            table.getColumns().add(name);
            table.getItems().addAll("one", "two", "three");
            table.setRowFactory(v -> {
                TableRow<String> row = new TableRow<>();
                row.setOnContextMenuRequested(e -> {
                    if (!row.isEmpty()) {
                        double bottom =
                                row.localToScreen(row.getBoundsInLocal()).getMaxY();
                        seen.add(new Request(row.getItem(), e.isKeyboardTrigger(), e.getScreenY(), bottom));
                        e.consume();
                    }
                });
                return row;
            });
            RowContextMenu.install(table);
            show(table);
            table.getSelectionModel().select(1);
            table.fireEvent(new ContextMenuEvent(ContextMenuEvent.CONTEXT_MENU_REQUESTED, 0, 0, 0, 0, true, null));
            assertEquals(1, seen.size(), "the selected row received the request");
            assertEquals("two", seen.get(0).row());
            assertEquals(seen.get(0).rowBottom(), seen.get(0).screenY(), 0.5, "at the row, not mid-table");
            table.getScene().getWindow().hide();
        });
    }

    @Test
    void theMenuOpensInsideTheRow() {
        assertEquals(48, RowContextMenu.anchorX(1000), 0.0);
        assertEquals(10, RowContextMenu.anchorX(40), 0.0);
        assertEquals(0, RowContextMenu.anchorX(0), 0.0);
    }
}
