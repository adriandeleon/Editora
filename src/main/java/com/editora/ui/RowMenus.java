package com.editora.ui;

import java.util.function.BooleanSupplier;

import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Control;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.KeyDispatcher;
import com.editora.command.KeymapManager;
import com.editora.command.TextInputKeymap;
import com.editora.editor.MenuChords;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The right-click menu of a list or tree whose rows are a command's each: a row is titled as its command is
 * (without the palette category), shows that command's chord, and does what the command does to the selected
 * row, so the palette and a key binding reach the same action.
 */
final class RowMenus {

    private RowMenus() {}

    /**
     * A row for command {@code commandId}: the command's title without its category, the command's chord, and
     * {@code action}, which is what the command itself calls for the selected row.
     */
    static MenuItem row(String commandId, @Nullable Node icon, Runnable action) {
        return row(commandId, commandId, icon, action);
    }

    /**
     * {@link #row(String, Node, Runnable)} that shows the chord of {@code chordOf} instead: the Copy row of a
     * control that answers the keymap's own Copy chord ({@link #onCopyKey}) shows that one.
     */
    static MenuItem row(String commandId, String chordOf, @Nullable Node icon, Runnable action) {
        MenuItem item = new MenuItem(DebugPanelText.bareTitle(tr("command." + commandId)));
        if (icon != null) {
            item.setGraphic(icon);
        }
        item.setOnAction(e -> action.run());
        return MenuChords.tag(item, chordOf);
    }

    /**
     * Gives {@code control} one menu of {@code rows} that act on its selected row. A right-click selects the
     * row under the pointer first; the Menu key and Shift+F10 open the menu at the selected row. The rows are
     * disabled while {@code hasRow} is false: nothing is selected, or the selection is a heading; a row given a
     * rule of its own ({@link ListRowCommands#enabledWhen}) is disabled while that rule says so too.
     */
    static ContextMenu install(Control control, BooleanSupplier hasRow, MenuItem... rows) {
        ContextMenu menu = new ContextMenu(rows);
        menu.setOnShowing(e -> {
            boolean none = !hasRow.getAsBoolean();
            for (MenuItem row : rows) {
                row.setDisable(none || !ListRowCommands.enabledNow(row));
            }
        });
        control.setContextMenu(menu);
        RowContextMenu.anchor(control, menu);
        return menu;
    }

    /**
     * Runs {@code copy} when the platform's copy shortcut, or the chord the active keymap binds to Copy (M-w in
     * Emacs), is pressed in {@code control}.
     */
    static void onCopyKey(Node control, Runnable copy) {
        control.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isConsumed()) {
                return;
            }
            KeymapManager keymap = TextInputKeymap.sharedKeymap();
            String token = keymap == null ? null : KeyDispatcher.chord(e);
            String command = keymap == null || token == null ? null : keymap.commandFor(token);
            if ("edit.copy".equals(command) || (e.getCode() == KeyCode.C && e.isShortcutDown())) {
                copy.run();
                e.consume();
            }
        });
    }

    /** Puts {@code text} on the clipboard; false, and nothing copied, when it is null or empty. */
    static boolean copy(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }
}
