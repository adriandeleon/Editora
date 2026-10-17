package com.editora.ui;

import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;

/**
 * The Settings window's keyboard: the sidebar's arrow, Home/End and Page keys (only onto a page that can be
 * opened), Ctrl/Cmd+F and type-to-search, Down from the search box into the sidebar, and Escape (first the
 * search, then the window).
 */
final class SettingsKeys {

    private SettingsKeys() {}

    /**
     * Wires the keys. {@code canLand} says whether sidebar row {@code i} is a page the selection may rest on;
     * {@code isHeader} whether an item is a group header (shown above a page the keys scroll up to).
     */
    static void install(
            Scene scene,
            Stage stage,
            TextField search,
            ListView<Object> sidebar,
            IntPredicate canLand,
            Predicate<Object> isHeader) {
        sidebar.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown()) {
                return;
            }
            int from = sidebar.getSelectionModel().getSelectedIndex();
            int page = (int) Math.max(1, sidebar.getHeight() / 34 - 1);
            int target = SettingsSidebarNav.target(sidebar.getItems().size(), from, e.getCode(), page, canLand);
            if (target == SettingsSidebarNav.NOT_A_NAVIGATION_KEY) {
                return;
            }
            e.consume();
            if (target >= 0 && target != from) {
                sidebar.getSelectionModel().clearAndSelect(target);
                sidebar.getFocusModel().focus(target);
                reveal(sidebar, target, isHeader);
            }
        });
        // Typing a letter on the sidebar searches; Down in the search box goes on to the pages.
        sidebar.addEventHandler(KeyEvent.KEY_TYPED, e -> {
            String ch = e.getCharacter();
            if (!e.isControlDown()
                    && !e.isMetaDown()
                    && !e.isAltDown()
                    && !ch.isEmpty()
                    && ch.codePoints().allMatch(Character::isLetterOrDigit)) {
                search.requestFocus();
                search.appendText(ch);
                e.consume();
            }
        });
        search.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.DOWN) {
                sidebar.requestFocus();
                e.consume();
            }
        });
        // Escape leaves: first a running search, then the window. A filter, because a ListView (the sidebar,
        // where the focus usually is) swallows Escape — so what really uses the key is exempted by name in
        // escapeIsTaken. An open combo popup or context menu takes the key before the scene.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.F && e.isShortcutDown() && !e.isAltDown()) {
                e.consume(); // Ctrl/Cmd+F: the search box, wherever the focus is
                search.requestFocus();
                search.selectAll();
                return;
            }
            if (e.getCode() != KeyCode.ESCAPE || e.isShortcutDown() || e.isAltDown() || escapeIsTaken(e.getTarget())) {
                return;
            }
            e.consume();
            if (!search.getText().isEmpty()) {
                search.clear();
            } else {
                stage.close();
            }
        });
    }

    /**
     * Whether Escape pressed on {@code target} is that control's own: the shortcut recorder (cancels the
     * recording), a snippet/template body (a text editor — not a request to close the window), a cell
     * being edited, or a combo whose popup is open.
     */
    static boolean escapeIsTaken(Object target) {
        for (Node n = target instanceof Node node ? node : null; n != null; n = n.getParent()) {
            if (n instanceof CodeArea
                    || n.getStyleClass().contains("shortcut-capture")
                    || (n instanceof javafx.scene.control.Cell<?> cell && cell.isEditing())
                    || (n instanceof javafx.scene.control.ComboBoxBase<?> combo && combo.isShowing())) {
                return true;
            }
        }
        return false;
    }

    /** Scrolls the sidebar just far enough to show row {@code index}; going up, its group header too. */
    static void reveal(ListView<Object> sidebar, int index, Predicate<Object> isHeader) {
        if (index < 0) {
            return;
        }
        if (sidebar.lookup(".virtual-flow") instanceof javafx.scene.control.skin.VirtualFlow<?> flow) {
            javafx.scene.control.IndexedCell<?> first = flow.getFirstVisibleCell();
            javafx.scene.control.IndexedCell<?> last = flow.getLastVisibleCell();
            if (first != null && last != null && index > first.getIndex() && index < last.getIndex()) {
                return; // fully in view already
            }
        }
        List<Object> items = sidebar.getItems();
        boolean up = index > 0 && isHeader.test(items.get(index - 1));
        sidebar.scrollTo(up ? index - 1 : index);
    }
}
