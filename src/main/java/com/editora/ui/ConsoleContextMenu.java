package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.stage.Window;

import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A right-click <b>Select All</b> / <b>Copy</b> menu for a read-only console {@link CodeArea} (the Build
 * Output tabs and the Test Runner detail pane). These areas are read-only, so keyboard select/copy already
 * works; this adds the discoverable mouse affordance. <b>Copy</b> copies the current selection, or the whole
 * buffer when nothing is selected (the common "grab all the output" case). Both items are disabled while the
 * console is empty.
 *
 * <p>The rows show the chords of {@code edit.copy} and {@code edit.selectAll}, and those commands do what the rows
 * do while the keyboard is in such a view ({@link #runInFocusedView}): a read-only view is {@linkplain #mark marked}
 * with its Copy and Select All, which the commands look for from the focus owner up before they act on the editor.
 * So the palette, the menu bar and a key binding reach a console's Copy as its menu row does (KBD-01, MENU-05).
 */
final class ConsoleContextMenu {

    /** The node property a read-only text view keeps its {@link Actions} under. */
    private static final String KEY = "editora.readOnlyText";

    /** What {@code edit.copy} and {@code edit.selectAll} do in a marked read-only view. */
    record Actions(Runnable copy, Runnable selectAll) {}

    private ConsoleContextMenu() {}

    static void install(CodeArea area) {
        mark(area, () -> copySelectionOrAll(area), () -> selectAllIn(area));
        ContextMenu menu = new ContextMenu(selectAllRow(area), copyRow(area));
        area.setOnContextMenuRequested(e -> {
            refresh(menu.getItems(), area.getLength() == 0);
            menu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /**
     * The same menu over a read-only text control (the Application Log's), with a <b>Find</b> row that runs
     * {@code find}: the window's find bar, which command {@code findCommand} opens too. It replaces the stock menu
     * JavaFX gives a text area, whose Cut, Paste and Delete mean nothing in a read-only log.
     */
    static ContextMenu install(TextInputControl area, Runnable find, String findCommand) {
        Runnable copy = () -> {
            String selected = area.getSelectedText();
            RowMenus.copy(selected == null || selected.isEmpty() ? area.getText() : selected);
        };
        Runnable selectAll = () -> {
            area.selectAll();
            area.requestFocus();
        };
        mark(area, copy, selectAll);
        MenuItem selectAllRow =
                com.editora.editor.MenuChords.tag(new MenuItem(tr("editmenu.selectAll")), "edit.selectAll");
        selectAllRow.setGraphic(Icons.selectAll());
        selectAllRow.setOnAction(e -> selectAll.run());

        MenuItem copyRow = com.editora.editor.MenuChords.tag(new MenuItem(tr("editmenu.copy")), "edit.copy");
        copyRow.setGraphic(Icons.copy());
        copyRow.setOnAction(e -> copy.run());

        MenuItem findRow = com.editora.editor.MenuChords.tag(new MenuItem(tr("find.prompt")), findCommand);
        findRow.setGraphic(Icons.find());
        findRow.setOnAction(e -> find.run());

        ContextMenu menu = new ContextMenu(selectAllRow, copyRow, new SeparatorMenuItem(), findRow);
        menu.setOnShowing(e -> refresh(menu.getItems(), area.getLength() == 0));
        area.setContextMenu(menu);
        return menu;
    }

    /**
     * Marks {@code view} as a read-only text view whose Copy and Select All are {@code copy} and {@code selectAll}:
     * {@code edit.copy} and {@code edit.selectAll} run those while the keyboard is in it.
     */
    static void mark(Node view, Runnable copy, Runnable selectAll) {
        view.getProperties().put(KEY, new Actions(copy, selectAll));
    }

    /**
     * Runs Copy ({@code copy}) or Select All of the read-only text view the focused window's keyboard is in, and
     * answers true; false, having done nothing, when the keyboard is in no such view (the commands then act on the
     * editor, as before).
     */
    static boolean runInFocusedView(boolean copy) {
        for (Window window : Window.getWindows()) {
            Scene scene = window.isFocused() ? window.getScene() : null;
            Actions actions = scene == null ? null : actionsAt(scene.getFocusOwner());
            if (actions != null) {
                (copy ? actions.copy() : actions.selectAll()).run();
                return true;
            }
        }
        return false;
    }

    /** The actions of the marked view at {@code focus} or above it; null when there is none. */
    static @Nullable Actions actionsAt(@Nullable Node focus) {
        for (Node node = focus; node != null; node = node.getParent()) {
            if (node.hasProperties() && node.getProperties().get(KEY) instanceof Actions actions) {
                return actions;
            }
        }
        return null;
    }

    /** The Select All row of a read-only {@code area}, showing the chord of {@code edit.selectAll}. */
    static MenuItem selectAllRow(CodeArea area) {
        MenuItem selectAll =
                com.editora.editor.MenuChords.tag(new MenuItem(tr("editmenu.selectAll")), "edit.selectAll");
        selectAll.setGraphic(Icons.selectAll());
        selectAll.setOnAction(e -> selectAllIn(area));
        return selectAll;
    }

    /** The Copy row of a read-only {@code area} (the selection, or all of it), showing the chord of {@code edit.copy}. */
    static MenuItem copyRow(CodeArea area) {
        MenuItem copy = com.editora.editor.MenuChords.tag(new MenuItem(tr("editmenu.copy")), "edit.copy");
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> copySelectionOrAll(area));
        return copy;
    }

    /** Disables every row of {@code rows} while the view is {@code empty}; enables them otherwise. */
    private static void refresh(List<MenuItem> rows, boolean empty) {
        for (MenuItem row : rows) {
            row.setDisable(empty);
        }
    }

    private static void selectAllIn(CodeArea area) {
        area.selectAll();
        area.requestFocus();
    }

    /** Copies {@code area}'s selection, or all of its text when nothing is selected; nothing when it is empty. */
    static void copySelectionOrAll(CodeArea area) {
        String text = area.getSelectedText();
        if (text == null || text.isEmpty()) {
            text = area.getText();
        }
        if (text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
