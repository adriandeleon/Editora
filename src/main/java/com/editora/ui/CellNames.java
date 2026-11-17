package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.text.Text;

import org.jspecify.annotations.Nullable;

/**
 * Names a list or tree row that lays out its own nodes. JavaFX reads a cell from its text or, when it has none,
 * from its graphic's accessible text; a row built from an icon and a few labels has neither, and a screen reader
 * says nothing as it moves through the list. ACC-01 (Pauta 6.0.1): the row's graphic carries the words it shows.
 */
final class CellNames {

    private CellNames() {}

    /**
     * Sets {@code graphic}'s accessible text to the words its labels and texts show, in order, and returns it:
     * {@code setGraphic(CellNames.read(row))}, after the labels are filled. Call it each time the row is filled.
     */
    static <N extends Node> N read(N graphic) {
        String words = words(graphic);
        graphic.setAccessibleText(words.isEmpty() ? null : words);
        return graphic;
    }

    /**
     * {@code CheckBoxListCell.forListView}, with each row's check box named after the row: the box is a control
     * of its own inside the row, and JavaFX gives it no name.
     */
    static <T> javafx.util.Callback<javafx.scene.control.ListView<T>, javafx.scene.control.ListCell<T>> checkBoxes(
            javafx.util.Callback<T, javafx.beans.value.ObservableValue<Boolean>> selected) {
        return list -> new javafx.scene.control.cell.CheckBoxListCell<>(selected) {
            @Override
            public void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                if (getGraphic() instanceof javafx.scene.control.CheckBox box) {
                    box.setAccessibleText(empty ? null : getText());
                }
            }
        };
    }

    /** The text {@link #read} gives a row: its shown labels' and texts' words, joined by spaces. Pure. */
    static String words(Node node) {
        List<String> parts = new ArrayList<>();
        collect(node, parts);
        return String.join(" ", parts);
    }

    private static void collect(@Nullable Node node, List<String> parts) {
        if (node == null || !node.isVisible()) {
            return;
        }
        String text = node instanceof Labeled labeled ? labeled.getText() : node instanceof Text t ? t.getText() : null;
        if (text != null && !text.isBlank()) {
            parts.add(text.strip());
        }
        if (node instanceof Labeled labeled) {
            collect(labeled.getGraphic(), parts);
        } else if (node instanceof Parent parent && !(node instanceof javafx.scene.control.Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, parts);
            }
        }
    }
}
