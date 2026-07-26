package com.editora.ui;

import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeView;

/**
 * Wires a tool-window filter/search {@link TextField} so the keyboard flow works when focus lands on the
 * field (as {@code focusFirstItem} now does): <b>Down</b> moves focus into the results tree (selecting the
 * first row if none is selected), and <b>Enter</b> opens the selected (or first) row via {@code onEnter}.
 * Used by the Project / Structure / Bookmarks / Personal-Notes tool windows so all four behave alike.
 *
 * <p>The {@link ListView} overload (GitHub tool window) adds Emacs {@code C-n}/{@code C-p}, which move the
 * selection <em>without leaving the field</em>, so filter-then-pick needs no hand off the keyboard. The tree
 * overload has no such binding — there {@code C-n}/{@code C-p} would have to coexist with expand/collapse.
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

    /**
     * The {@link ListView} flavour: <b>Down</b> moves focus into the list, <b>{@code C-n}/{@code C-p}</b>
     * move the list selection while focus stays in the field (so typing can continue), and <b>Enter</b>
     * activates the selected (or first) row.
     */
    static void install(TextField field, ListView<?> list, Runnable onEnter) {
        field.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    if (!list.getItems().isEmpty()) {
                        // Down enters the list: select row 0 if nothing is selected, else keep the row C-n reached.
                        selectRelative(list, list.getSelectionModel().isEmpty() ? 0 : 1);
                        list.requestFocus();
                    }
                    e.consume();
                }
                // Only with Control — a bare n/p is a character the user is typing into the filter.
                case N -> {
                    if (e.isControlDown()) {
                        selectRelative(list, 1);
                        e.consume();
                    }
                }
                case P -> {
                    if (e.isControlDown()) {
                        selectRelative(list, -1);
                        e.consume();
                    }
                }
                case ENTER -> {
                    if (list.getSelectionModel().isEmpty() && !list.getItems().isEmpty()) {
                        list.getSelectionModel().select(0);
                    }
                    onEnter.run();
                    e.consume();
                }
                default -> {}
            }
        });
    }

    /** Moves the list selection by {@code delta}, clamped; a selection-less list starts at row 0. */
    private static void selectRelative(ListView<?> list, int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int current = list.getSelectionModel().getSelectedIndex();
        int i = current < 0 ? 0 : Math.clamp(current + delta, 0, size - 1);
        list.getSelectionModel().clearAndSelect(i);
        list.scrollTo(i);
    }
}
