package com.editora.ui;

import java.util.List;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import com.editora.editor.MenuChords;
import com.editora.editor.RowReasons;
import org.jspecify.annotations.Nullable;

/**
 * Finds and runs a right-click menu's row by the command it is {@linkplain MenuChords#tag tagged} with. A panel whose
 * menu is built for its selection, row by row under rules of its own (the Debug window's Variables view offers a
 * dozen rows, each for some kinds of row only), gives its commands the menu itself: the command builds the menu as a
 * right-click would and fires the row, so it does exactly what the row does, and only where the row is offered.
 */
final class MenuRows {

    private MenuRows() {}

    /** The row of {@code items} (submenus included) tagged with {@code commandId}; null when there is none. */
    static @Nullable MenuItem find(List<MenuItem> items, String commandId) {
        for (MenuItem item : items) {
            if (commandId.equals(MenuChords.commandOf(item))) {
                return item;
            }
            if (item instanceof Menu menu) {
                MenuItem inside = find(menu.getItems(), commandId);
                if (inside != null) {
                    return inside;
                }
            }
        }
        return null;
    }

    /**
     * Fires the row of {@code items} tagged with {@code commandId}, as choosing it would, and answers true; false when
     * there is no such row or it is disabled.
     */
    static boolean fire(List<MenuItem> items, String commandId) {
        MenuItem row = find(items, commandId);
        if (row == null || row.isDisable()) {
            return false;
        }
        row.fire();
        return true;
    }

    /**
     * Why the row of {@code items} tagged with {@code commandId} cannot run, as its tooltip says it ({@link
     * RowReasons}); null when there is no such row or it can run, or says nothing.
     */
    static @Nullable String reason(List<MenuItem> items, String commandId) {
        MenuItem row = find(items, commandId);
        return row == null || !row.isDisable() ? null : RowReasons.reasonOf(row);
    }
}
