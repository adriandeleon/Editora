package com.editora.ui;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * A right-click <b>Select All</b> / <b>Copy</b> menu for a read-only console {@link CodeArea} (the Build
 * Output tabs and the Test Runner detail pane). These areas are read-only, so keyboard select/copy already
 * works; this adds the discoverable mouse affordance. <b>Copy</b> copies the current selection, or the whole
 * buffer when nothing is selected (the common "grab all the output" case). Both items are disabled while the
 * console is empty.
 */
final class ConsoleContextMenu {

    private ConsoleContextMenu() {}

    static void install(CodeArea area) {
        MenuItem selectAll = new MenuItem(tr("editmenu.selectAll"));
        selectAll.setGraphic(Icons.selectAll());
        selectAll.setOnAction(e -> {
            area.selectAll();
            area.requestFocus();
        });

        MenuItem copy = new MenuItem(tr("editmenu.copy"));
        copy.setGraphic(Icons.copy());
        copy.setOnAction(e -> copySelectionOrAll(area));

        ContextMenu menu = new ContextMenu(selectAll, copy);
        area.setOnContextMenuRequested(e -> {
            boolean empty = area.getLength() == 0;
            selectAll.setDisable(empty);
            copy.setDisable(empty);
            menu.show(area, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    private static void copySelectionOrAll(CodeArea area) {
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
