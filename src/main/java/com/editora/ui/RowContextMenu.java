package com.editora.ui;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;

/**
 * Makes a tree or list whose context menus live on its <em>cells</em> reachable from the keyboard.
 *
 * <p>A context-menu request from the Menu key or Shift+F10 is delivered to the focus owner — the
 * {@code TreeView}/{@code ListView} itself, since cells are not focusable — so a menu installed on a cell
 * (via {@code setContextMenu} or {@code setOnContextMenuRequested}) never saw it, and every row action in the
 * Project, Bookmarks, Notes, TODO, Git Log and Structure tool windows was mouse-only. This routes such a
 * request to the selected row's cell, re-fired with coordinates on that row, so whatever the cell does for a
 * right-click it now does for the keyboard, with the menu anchored to the row instead of to the middle of
 * the control. Mouse requests are untouched.
 */
final class RowContextMenu {

    private RowContextMenu() {}

    /** Installs the routing on a {@link TreeView} or {@link ListView}. */
    static void install(Control control) {
        control.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            // Only the keyboard request aimed at the control itself: a request re-fired at a cell (below)
            // bubbles back through here and must not be routed again.
            if (!e.isKeyboardTrigger() || e.getTarget() != control || e.isConsumed()) {
                return;
            }
            int index = selectedIndex(control);
            IndexedCell<?> cell = cellFor(control, index);
            if (cell == null && index >= 0) {
                scrollTo(control, index); // the selected row is scrolled out of view: realize its cell first
                control.applyCss();
                control.layout();
                cell = cellFor(control, index);
            }
            if (cell == null) {
                return; // nothing selected: leave the default behaviour
            }
            Bounds onScreen = cell.localToScreen(cell.getBoundsInLocal());
            Bounds inScene = cell.localToScene(cell.getBoundsInLocal());
            if (onScreen == null || inScene == null) {
                return;
            }
            double dx = anchorX(onScreen.getWidth());
            Event.fireEvent(
                    cell,
                    new ContextMenuEvent(
                            ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            inScene.getMinX() + dx,
                            inScene.getMaxY(),
                            onScreen.getMinX() + dx,
                            onScreen.getMaxY(),
                            true,
                            null));
            e.consume();
        });
    }

    /** How far in from the row's left edge the menu opens: past the disclosure arrow, never off a narrow row. */
    static double anchorX(double rowWidth) {
        return Math.max(0, Math.min(rowWidth / 4, 48));
    }

    private static int selectedIndex(Control control) {
        int index = -1;
        if (control instanceof TreeView<?> tree) {
            index = tree.getSelectionModel().getSelectedIndex();
            if (index < 0 && tree.getFocusModel() != null) {
                index = tree.getFocusModel().getFocusedIndex();
            }
        } else if (control instanceof ListView<?> list) {
            index = list.getSelectionModel().getSelectedIndex();
            if (index < 0 && list.getFocusModel() != null) {
                index = list.getFocusModel().getFocusedIndex();
            }
        }
        return index;
    }

    private static void scrollTo(Control control, int index) {
        if (control instanceof TreeView<?> tree) {
            tree.scrollTo(index);
        } else if (control instanceof ListView<?> list) {
            list.scrollTo(index);
        }
    }

    /** The realized, non-empty cell showing row {@code index}, or null. */
    private static IndexedCell<?> cellFor(Control control, int index) {
        if (index < 0) {
            return null;
        }
        for (Node node : control.lookupAll(".indexed-cell")) {
            if (node instanceof IndexedCell<?> cell
                    && cell.getIndex() == index
                    && !cell.isEmpty()
                    && cell.isVisible()) {
                return cell;
            }
        }
        return null;
    }
}
