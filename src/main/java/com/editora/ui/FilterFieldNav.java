package com.editora.ui;

import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;

/**
 * Wires a tool-window filter/search {@link TextField} so the keyboard flow works when focus lands on the
 * field (as {@code focusFirstItem} now does): <b>Down</b> moves focus into the results tree (selecting the
 * first row if none is selected), and <b>Enter</b> opens the selected (or first) row via {@code onEnter}.
 * Used by the Project / Structure / Bookmarks / Personal-Notes tool windows so all four behave alike.
 */
final class FilterFieldNav {

    private FilterFieldNav() {}

    static void install(TextField field, TreeView<?> tree, Runnable onEnter) {
        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    if (tree.getExpandedItemCount() > 0) {
                        if (tree.getSelectionModel().isEmpty()) {
                            tree.getSelectionModel().select(0);
                        }
                        tree.requestFocus();
                        tree.scrollTo(Math.max(0, tree.getSelectionModel().getSelectedIndex()));
                    }
                    e.consume();
                }
                case ENTER -> {
                    if (tree.getSelectionModel().isEmpty() && tree.getExpandedItemCount() > 0) {
                        tree.getSelectionModel().select(0);
                    }
                    onEnter.run();
                    e.consume();
                }
                default -> {}
            }
        });
    }
}
