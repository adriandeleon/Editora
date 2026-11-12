package com.editora.ui;

import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TableView;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;

import org.jspecify.annotations.Nullable;

/**
 * Makes a tree, list or table whose rows have a context menu reachable from the keyboard, at the row.
 *
 * <p>A context-menu request from the Menu key or Shift+F10 is delivered to the focus owner — the
 * {@code TreeView}/{@code ListView}/{@code TableView} itself, since cells are not focusable — so a menu installed
 * on a cell (via {@code setContextMenu} or {@code setOnContextMenuRequested}) never saw it, and every row action
 * in the Project, Bookmarks, Notes, TODO, Git Log and Structure tool windows was mouse-only. {@link #install}
 * routes such a request to the selected row's cell, re-fired with coordinates on that row, so whatever the cell
 * does for a right-click it now does for the keyboard, with the menu anchored to the row instead of to the middle
 * of the control. Mouse requests are untouched.
 *
 * <p>A control that owns one menu for all its rows ({@code control.setContextMenu(menu)}) is shown by JavaFX in
 * the middle of the control for the keyboard: {@link #anchor} opens it at the selected row instead. A control
 * whose own handler builds the menu per request asks {@link #menuPoint} where to open it.
 */
final class RowContextMenu {

    private RowContextMenu() {}

    /**
     * Installs the routing on a {@link TreeView}, {@link ListView}, {@link TableView} or {@link TreeTableView}
     * whose menus live on its cells (a table's on its rows).
     */
    static void install(Control control) {
        control.addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            // Only the keyboard request aimed at the control itself: a request re-fired at a cell (below)
            // bubbles back through here and must not be routed again.
            if (!e.isKeyboardTrigger() || e.getTarget() != control || e.isConsumed()) {
                return;
            }
            IndexedCell<?> cell = revealedCell(control, selectedIndex(control));
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

    /**
     * Opens {@code menu} — the control's one menu, whose items act on the selected row — at that row for a
     * keyboard request, instead of in the middle of the control where JavaFX puts it. A filter, so the control's
     * own handler does not show it there first; with nothing selected the request is left to that handler.
     */
    static void anchor(Control control, ContextMenu menu) {
        control.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, e -> {
            if (showAtSelectedRow(control, menu, e)) {
                e.consume();
            }
        });
    }

    /**
     * Shows {@code menu} at the selected row when {@code e} is a keyboard request aimed at the control and a row
     * is selected; false (and nothing shown) otherwise, so the caller leaves the request to its usual path.
     */
    static boolean showAtSelectedRow(Control control, ContextMenu menu, ContextMenuEvent e) {
        if (!e.isKeyboardTrigger() || e.getTarget() != control) {
            return false;
        }
        Bounds row = selectedRowOnScreen(control);
        if (row == null) {
            return false;
        }
        menu.show(control, row.getMinX() + anchorX(row.getWidth()), row.getMaxY());
        return true;
    }

    /**
     * Where on screen the menu for request {@code e} opens: at the pointer for a mouse request, and for the
     * keyboard under the selected row (scrolled into view first), a little in from its left edge. With nothing
     * selected the request's own point stands.
     */
    static Point2D menuPoint(Control control, ContextMenuEvent e) {
        return menuPoint(control, selectedIndex(control), e);
    }

    /** As {@link #menuPoint(Control, ContextMenuEvent)}, for row {@code index} rather than the selected one. */
    static Point2D menuPoint(Control control, int index, ContextMenuEvent e) {
        Bounds row = e.isKeyboardTrigger() ? rowOnScreen(control, index) : null;
        return row == null
                ? new Point2D(e.getScreenX(), e.getScreenY())
                : new Point2D(row.getMinX() + anchorX(row.getWidth()), row.getMaxY());
    }

    /** The selected row's cell on screen, scrolled into view first; null when nothing is selected. */
    static @Nullable Bounds selectedRowOnScreen(Control control) {
        return rowOnScreen(control, selectedIndex(control));
    }

    /** Row {@code index}'s cell on screen, scrolled into view first; null when there is no such row. */
    static @Nullable Bounds rowOnScreen(Control control, int index) {
        IndexedCell<?> cell = revealedCell(control, index);
        return cell == null ? null : cell.localToScreen(cell.getBoundsInLocal());
    }

    /** How far in from the row's left edge the menu opens: past the disclosure arrow, never off a narrow row. */
    static double anchorX(double rowWidth) {
        return Math.max(0, Math.min(rowWidth / 4, 48));
    }

    /**
     * The cell showing row {@code index}, scrolled into view first when it is not realized or the control's edge
     * cuts it: the menu opens under the row, so a row half below the viewport would put it off the control.
     */
    private static @Nullable IndexedCell<?> revealedCell(Control control, int index) {
        if (index < 0) {
            return null;
        }
        IndexedCell<?> cell = cellFor(control, index);
        if (cell == null || !insideControl(control, cell)) {
            scrollTo(control, index);
            control.applyCss();
            control.layout();
            cell = cellFor(control, index);
        }
        return cell;
    }

    /** Whether {@code cell} lies within the control's height: neither edge cuts it. */
    private static boolean insideControl(Control control, IndexedCell<?> cell) {
        Bounds inScene = cell.localToScene(cell.getBoundsInLocal());
        Bounds bounds = inScene == null ? null : control.sceneToLocal(inScene);
        return bounds == null || (bounds.getMinY() >= -0.5 && bounds.getMaxY() <= control.getHeight() + 0.5);
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
        } else if (control instanceof TableView<?> table) {
            index = table.getSelectionModel() == null
                    ? -1
                    : table.getSelectionModel().getSelectedIndex();
            if (index < 0 && table.getFocusModel() != null) {
                index = table.getFocusModel().getFocusedIndex();
            }
        } else if (control instanceof TreeTableView<?> table) {
            index = table.getSelectionModel() == null
                    ? -1
                    : table.getSelectionModel().getSelectedIndex();
            if (index < 0 && table.getFocusModel() != null) {
                index = table.getFocusModel().getFocusedIndex();
            }
        }
        return index;
    }

    private static void scrollTo(Control control, int index) {
        if (control instanceof TreeView<?> tree) {
            tree.scrollTo(index);
        } else if (control instanceof ListView<?> list) {
            list.scrollTo(index);
        } else if (control instanceof TableView<?> table) {
            table.scrollTo(index);
        } else if (control instanceof TreeTableView<?> table) {
            table.scrollTo(index);
        }
    }

    /**
     * The realized, non-empty cell showing row {@code index}, or null. In a table that is the whole row (a
     * {@code TableRow}), not one of its cells: the row is what carries the menu and spans the columns.
     */
    private static @Nullable IndexedCell<?> cellFor(Control control, int index) {
        if (index < 0) {
            return null;
        }
        String selector = control instanceof TableView<?>
                ? ".table-row-cell"
                : control instanceof TreeTableView<?> ? ".tree-table-row-cell" : ".indexed-cell";
        for (Node node : control.lookupAll(selector)) {
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
