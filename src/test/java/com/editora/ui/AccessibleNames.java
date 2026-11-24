package com.editora.ui;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Cell;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Spinner;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.ToolBar;
import javafx.scene.control.TreeTableRow;
import javafx.stage.WindowEvent;

/**
 * ACC-01 (Pauta 6.2.0): what a screen reader says for a control. A control a person can reach with Tab or the
 * mouse is named by its own text, a {@code Label} whose {@code labelFor} is the control, or its accessible text;
 * a filled list or tree cell by its text or its graphic's accessible text; a menu row by its text, or, for a
 * {@code CustomMenuItem}, by its content's accessible text. This walks a scene and collects every one that
 * reads as nothing, keyed by where it sits so that the same control seen after many commands counts once.
 *
 * <p>All methods run on the FX thread.
 */
final class AccessibleNames {

    /** What holds controls but is no control a person uses by itself: scrolling, separators, layout, progress. */
    private static final List<Class<?>> NOT_A_CONTROL = List.of(
            Label.class, // read as text; one that acts on a click is ACC-02's, a role and an action
            ScrollBar.class,
            ScrollPane.class,
            Separator.class,
            ToolBar.class,
            SplitPane.class,
            javafx.scene.control.ButtonBar.class,
            TabPane.class, // each tab reads as its own title
            MenuBar.class, // each menu reads as its own title (checked as rows)
            ProgressIndicator.class, // not focusable, nothing to press
            TableRow.class, // a row reads as its cells
            TreeTableRow.class);

    /** Unnamed controls found so far: a key for where each sits, and the first place it was seen. */
    private final Map<String, String> unnamed = new java.util.TreeMap<>();

    /** How many scenes were walked, and how many controls were looked at. */
    int scenes;

    int controls;

    Map<String, String> unnamed() {
        return Collections.unmodifiableMap(unnamed);
    }

    /** Walks one scene: every visible, enabled control in it, and the rows of every menu its controls carry. */
    void scene(Scene scene, String where) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        scenes++;
        Parent root = scene.getRoot();
        root.applyCss();
        root.layout(); // a list makes its cells in layout
        walk(root, labelled(root), where);
    }

    /** Checks each row of a menu, and of its submenus. */
    void rows(List<MenuItem> items, String where) {
        for (MenuItem item : items) {
            if (item instanceof SeparatorMenuItem || !item.isVisible()) {
                continue;
            }
            String name = item instanceof CustomMenuItem custom && custom.getContent() != null
                    ? custom.getContent().getAccessibleText()
                    : item.getText();
            if (!has(name) && !has(item.getText())) {
                unnamed.putIfAbsent(
                        "menu row " + (item.getId() == null ? "" : "#" + item.getId()) + " " + item.getStyleClass()
                                + shows(item) + " in " + parentMenu(item),
                        where);
            }
            if (item instanceof Menu menu) {
                rows(menu.getItems(), where);
            }
        }
    }

    /** What a row shows instead of text: its content's or its graphic's kind. */
    private static String shows(MenuItem item) {
        javafx.scene.Node shown = item instanceof CustomMenuItem custom ? custom.getContent() : item.getGraphic();
        if (shown == null) {
            return " (nothing)";
        }
        return " (" + shown.getClass().getSimpleName() + " " + shown.getStyleClass()
                + (shown instanceof Parent p
                        ? " "
                                + p.getChildrenUnmodifiable().stream()
                                        .map(c -> c.getClass().getSimpleName() + c.getStyleClass())
                                        .toList()
                        : "")
                + ")";
    }

    private static String parentMenu(MenuItem item) {
        Menu menu = item.getParentMenu();
        if (menu != null) {
            return "menu '" + menu.getText() + "'";
        }
        return item.getParentPopup() == null
                ? "a menu"
                : "popup " + item.getParentPopup().getStyleClass();
    }

    /** The controls some label in this scene names (its {@code labelFor}). */
    private static Set<Node> labelled(Parent root) {
        Set<Node> named = Collections.newSetFromMap(new IdentityHashMap<>());
        collectLabelled(root, named);
        return named;
    }

    private static void collectLabelled(Node node, Set<Node> named) {
        if (node instanceof Label label && label.getLabelFor() != null) {
            named.add(label.getLabelFor());
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectLabelled(child, named);
            }
        }
    }

    private void walk(Node node, Set<Node> labelled, String where) {
        if (!node.isVisible() || node.isMouseTransparent() && !node.isFocusTraversable()) {
            return;
        }
        // A disabled control is checked too: a Settings row that waits on a switch, a form that waits for a
        // selection and a field that waits for a running program are named or not either way.
        if (node instanceof Control control && NOT_A_CONTROL.stream().noneMatch(c -> c.isInstance(control))) {
            controls++;
            if (!named(control, labelled)) {
                unnamed.putIfAbsent(describe(control), where);
            }
            ContextMenu menu = LazyContextMenu.menuOf(control); // built now, as the first right-click would
            if (menu != null) {
                if (LazyContextMenu.isUnbuilt(menu) && menu.getOnShowing() != null) {
                    // Built on its first showing: build it now, as the first right-click would.
                    menu.getOnShowing().handle(new WindowEvent(menu, WindowEvent.WINDOW_SHOWING));
                }
                rows(menu.getItems(), where + " (right-click menu)");
            }
        }
        if (node instanceof MenuButton button) {
            rows(button.getItems(), where);
        }
        // A combo box's or a spinner's own parts (its list, its editor) are its skin's: the box carries the name.
        if (node instanceof Parent parent && !(node instanceof ComboBoxBase<?>) && !(node instanceof Spinner<?>)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                walk(child, labelled, where);
            }
        }
    }

    static boolean named(Control control, Set<Node> labelled) {
        if (has(control.getAccessibleText()) || labelled.contains(control)) {
            return true;
        }
        if (control instanceof Cell<?> cell) {
            // An empty cell is no row; a filled one reads its text or its graphic's accessible text.
            if (cell.isEmpty()) {
                return true;
            }
            Node graphic = cell.getGraphic();
            return has(cell.getText()) || graphic != null && has(graphic.getAccessibleText());
        }
        if (control instanceof Labeled labeled) {
            return has(labeled.getText());
        }
        // Anything else (a field, a list, a slider) has no text of its own: a field reads its prompt while it is
        // empty and its value after, and neither names it.
        return false;
    }

    private static boolean has(String text) {
        return text != null && !text.isBlank();
    }

    /** The first words shown near {@code control} (its row's title, say), to find it by; empty when none. */
    private static String near(Control control) {
        int depth = 0;
        for (Parent p = control.getParent(); p != null && depth < 4; p = p.getParent(), depth++) {
            for (Node child : p.getChildrenUnmodifiable()) {
                if (child != control && child instanceof Labeled labeled && has(labeled.getText())) {
                    return "near '" + labeled.getText().strip() + "': ";
                }
            }
        }
        return "";
    }

    /** The control's class, id and style classes, and its ancestors' style classes: where it sits, not which. */
    static String describe(Control control) {
        String id = control.getId();
        StringBuilder path = new StringBuilder();
        int depth = 0;
        for (Parent p = control.getParent(); p != null && depth < 8; p = p.getParent(), depth++) {
            if (!p.getStyleClass().isEmpty()) {
                path.append(" < ").append(String.join(".", p.getStyleClass()));
            } else if (p.getId() != null) {
                path.append(" < #").append(p.getId());
            }
        }
        return near(control) + control.getClass().getName().replaceFirst("^.*\\.", "")
                + (id == null ? "" : "#" + id)
                + " " + control.getStyleClass()
                + path;
    }
}
