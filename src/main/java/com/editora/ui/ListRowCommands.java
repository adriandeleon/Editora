package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.command.Command;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The commands behind the right-click menus of the lists, trees and tables whose rows had none: the Settings
 * pages' lists, the Project Map's cards and columns, the debugger's views and windows (MENU-05, Pauta 6.0.1).
 *
 * <p>Those lists do a handful of things to a row, each the same in all of them: copy it, open it (what Enter or a
 * double-click does, or the page's button for it), rename it, turn it on or off, remove it, move it up or down. Each of those is one command
 * here, which does to the selected row of the list the keyboard is in what that list's own menu row does. A list
 * {@linkplain #bind binds} what each command does to it; a command looks for the binding from the focus owner of
 * the focused window up, then from the focus owner of every other Editora window, so the palette reaches a list in
 * the Settings window or the Map as it reaches one in a tool window. With no list that takes the command, or no
 * row selected in it, the status line says so.
 *
 * <p>The binding lives in the control's own properties: nothing static holds a window.
 */
final class ListRowCommands {

    static final String COPY = "list.copy";
    static final String OPEN = "list.open";
    static final String REMOVE = "list.remove";
    static final String MOVE_UP = "list.moveUp";
    static final String MOVE_DOWN = "list.moveDown";
    static final String TOGGLE = "list.toggle";
    static final String RENAME = "list.rename";

    /** The commands, in palette order. */
    static final List<String> IDS = List.of(OPEN, COPY, RENAME, TOGGLE, REMOVE, MOVE_UP, MOVE_DOWN);

    /** The prefix of the control property a command's action for that control is kept under. */
    private static final String KEY = "editora.listRow.";

    /** The menu item property holding when a row is enabled beyond "a row is selected" (its button's state). */
    private static final String ENABLED = "editora.listRow.enabled";

    private ListRowCommands() {}

    static void register(MainController mc) {
        for (String id : IDS) {
            mc.registry.register(Command.of(id, () -> run(id, mc::setStatus)));
        }
    }

    /**
     * Runs command {@code id} on the list the keyboard is in, or says on {@code status} why it could not. A list in
     * a window other than the focused one (a Settings page's, while the palette ran the command from the main
     * window) is brought forward first: a row is never removed, or run, in a window the user is not looking at.
     */
    static void run(String id, Consumer<String> status) {
        Target target = targetFor(id);
        if (target == null) {
            status.accept(tr("status.list.noTarget"));
            return;
        }
        if (!target.window().isFocused() && target.window() instanceof Stage stage) {
            stage.toFront();
            stage.requestFocus();
        }
        if (!target.action().getAsBoolean()) {
            status.accept(tr("status.list.selectRow"));
        }
    }

    /** Has command {@code id} run {@code action} on {@code control}'s selected row; false from it: no row. */
    static void bind(Control control, String id, BooleanSupplier action) {
        control.getProperties().put(KEY + id, action);
    }

    /** A bound action and the window its list is in. */
    record Target(Window window, BooleanSupplier action) {}

    /** What command {@code id} runs where the keyboard is ({@link #targetFor}), or null. */
    static @Nullable BooleanSupplier actionFor(String id) {
        Target target = targetFor(id);
        return target == null ? null : target.action();
    }

    /**
     * What command {@code id} runs where the keyboard is: from the focus owner of the focused window up to the
     * first node {@linkplain #bind bound} to it, else the same in each other showing window; null when none is.
     */
    static @Nullable Target targetFor(String id) {
        List<Window> windows = new ArrayList<>(Window.getWindows());
        windows.sort((a, b) -> Boolean.compare(b.isFocused(), a.isFocused()));
        for (Window window : windows) {
            Scene scene = window.getScene();
            BooleanSupplier action = scene == null ? null : actionAt(scene.getFocusOwner(), id);
            if (action != null) {
                return new Target(window, action);
            }
        }
        return null;
    }

    /** The action bound to {@code id} on {@code focus} or the nearest node above it; null when none is. */
    static @Nullable BooleanSupplier actionAt(@Nullable Node focus, String id) {
        for (Node node = focus; node != null; node = node.getParent()) {
            if (node.hasProperties() && node.getProperties().get(KEY + id) instanceof BooleanSupplier action) {
                return action;
            }
        }
        return null;
    }

    /**
     * A menu row of {@code control} for command {@code id}: titled as the command (or {@code title}, a button's own
     * words where the page has one), showing its chord, and doing {@code action}, which the command does too while
     * the keyboard is in {@code control}.
     */
    static MenuItem row(
            Control control, String id, @Nullable String title, @Nullable Node icon, BooleanSupplier action) {
        bind(control, id, action);
        MenuItem item = RowMenus.row(id, icon, action::getAsBoolean);
        if (title != null) {
            item.setText(title);
        }
        return item;
    }

    /**
     * A row that does what {@code button} does to the selected row, under the button's own words (which it follows:
     * Disable becomes Enable), and is disabled while the button is. A disabled button does nothing when fired, so
     * the command says so instead.
     */
    static MenuItem buttonRow(Control control, String id, ButtonBase button, @Nullable Node icon) {
        MenuItem item = commandButtonRow(control, id, button, icon);
        item.textProperty().bind(button.textProperty());
        return item;
    }

    /**
     * As {@link #buttonRow}, titled as the command rather than as the button: a button that shows a glyph (▲)
     * rather than words.
     */
    static MenuItem commandButtonRow(Control control, String id, ButtonBase button, @Nullable Node icon) {
        MenuItem item = row(control, id, null, icon, () -> {
            if (button.isDisabled() || !hasSelection(control)) {
                return false;
            }
            button.fire();
            return true;
        });
        return enabledWhen(item, () -> !button.isDisabled());
    }

    /**
     * A row of {@code control}'s menu for a Project Map button that a card command of its own runs
     * ({@link MapButtonCommands}): under the button's words, disabled while the button is, and doing what it
     * does; the command does the same while the Map's keyboard is in {@code owner}.
     */
    static MenuItem mapButtonRow(Node owner, String commandId, ButtonBase button) {
        MapButtonCommands.bind(owner, commandId, button::fire);
        MenuItem item = RowMenus.row(commandId, null, button::fire);
        item.textProperty().bind(button.textProperty());
        return enabledWhen(item, () -> !button.isDisabled());
    }

    /**
     * The Copy row of {@code control}: copies what {@code selectedText} gives for the selected rows, and the
     * keymap's Copy chord pressed in the control does the same, so the row shows that chord.
     */
    static MenuItem copyRow(Control control, java.util.function.Supplier<@Nullable String> selectedText) {
        BooleanSupplier copy = () -> RowMenus.copy(selectedText.get());
        bind(control, COPY, copy);
        RowMenus.onCopyKey(control, copy::getAsBoolean);
        return RowMenus.row(COPY, "edit.copy", Icons.copy(), copy::getAsBoolean);
    }

    /** {@code item} styled as a row that deletes (MENU-12). */
    static MenuItem danger(MenuItem item) {
        item.getStyleClass().add("danger");
        return item;
    }

    /** Disables {@code item} on each showing while {@code enabled} is false, beside the menu's own rule. */
    static MenuItem enabledWhen(MenuItem item, BooleanSupplier enabled) {
        item.getProperties().put(ENABLED, enabled);
        return item;
    }

    /** Whether {@code item} may be enabled now, as far as its own rule goes ({@link #enabledWhen}). */
    static boolean enabledNow(MenuItem item) {
        return !(item.getProperties().get(ENABLED) instanceof BooleanSupplier enabled) || enabled.getAsBoolean();
    }

    /** Gives {@code list} its menu of {@code rows}, disabled while no row is selected. */
    static ContextMenu install(ListView<?> list, MenuItem... rows) {
        return RowMenus.install(list, () -> list.getSelectionModel().getSelectedItem() != null, rows);
    }

    /** Gives {@code tree} its menu of {@code rows}, disabled while no row is selected. */
    static ContextMenu install(TreeView<?> tree, MenuItem... rows) {
        return RowMenus.install(tree, () -> tree.getSelectionModel().getSelectedItem() != null, rows);
    }

    /** The selected rows of {@code list}, as {@code text} gives each, one to a line; null when none is selected. */
    static <T> @Nullable String selectedText(ListView<T> list, Function<T, @Nullable String> text) {
        return joined(list.getSelectionModel().getSelectedItems(), text);
    }

    /** The selected rows of {@code tree}, as {@code text} gives each, one to a line; null when none is selected. */
    static <T> @Nullable String selectedText(TreeView<T> tree, Function<T, @Nullable String> text) {
        List<T> values = new ArrayList<>();
        for (TreeItem<T> item : tree.getSelectionModel().getSelectedItems()) {
            if (item != null && item.getValue() != null) {
                values.add(item.getValue());
            }
        }
        return joined(values, text);
    }

    private static <T> @Nullable String joined(List<T> rows, Function<T, @Nullable String> text) {
        List<String> lines = new ArrayList<>();
        for (T row : rows) {
            String line = row == null ? null : text.apply(row);
            if (line != null && !line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    private static boolean hasSelection(Control control) {
        if (control instanceof ListView<?> list) {
            return list.getSelectionModel().getSelectedItem() != null;
        }
        if (control instanceof TreeView<?> tree) {
            return tree.getSelectionModel().getSelectedItem() != null;
        }
        return true; // a table's rows decide for themselves
    }
}
