package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A keyboard context-menu request (Menu key / Shift+F10) targets the focused tree or list, not a cell, so
 * menus installed on cells never opened from the keyboard. {@link RowContextMenu} routes the request to the
 * selected row's cell, with coordinates on that row.
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

    @Test
    void theMenuOpensInsideTheRow() {
        assertEquals(48, RowContextMenu.anchorX(1000), 0.0);
        assertEquals(10, RowContextMenu.anchorX(40), 0.0);
        assertEquals(0, RowContextMenu.anchorX(0), 0.0);
    }
}
