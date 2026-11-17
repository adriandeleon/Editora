package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;

import org.jspecify.annotations.Nullable;

/**
 * Ties a form's labels to the fields they caption, for a screen reader. A form card lays out "Name" above or
 * beside its field; a sighted person reads the pair, but a screen reader on the field hears only "edit" unless the
 * label's {@code labelFor} points at it. ACC-01 (Pauta 6.0.1): every field is named.
 *
 * <p>The rule is the layout's own: in a {@link GridPane} a label names the control to its right in the same row;
 * in any other pane, the control that follows it. A label that already names something, and a control that
 * already has a name (its own text, or accessible text), are left alone.
 */
final class FormLabels {

    private FormLabels() {}

    /** Links every caption in {@code form} to its field. Returns {@code form}. */
    static <N extends Node> N link(N form) {
        visit(form);
        return form;
    }

    private static void visit(Node node) {
        if (node instanceof GridPane grid) {
            for (Node child : grid.getChildren()) {
                if (child instanceof Label label && caption(label)) {
                    Control field = rightOf(grid, child);
                    if (field != null) {
                        label.setLabelFor(field);
                    }
                }
            }
        } else if (node instanceof Pane pane) {
            List<Node> children = pane.getChildren();
            for (int i = 0; i + 1 < children.size(); i++) {
                if (children.get(i) instanceof Label label && caption(label)) {
                    Control field = firstUnnamed(children.get(i + 1));
                    if (field != null) {
                        label.setLabelFor(field);
                    }
                }
            }
        }
        if (node instanceof Parent parent && !(node instanceof Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                visit(child);
            }
        }
    }

    private static boolean caption(Label label) {
        return label.getLabelFor() == null
                && label.getText() != null
                && !label.getText().isBlank();
    }

    private static @Nullable Control rightOf(GridPane grid, Node label) {
        int row = index(GridPane.getRowIndex(label));
        int column = index(GridPane.getColumnIndex(label));
        Control best = null;
        int bestColumn = Integer.MAX_VALUE;
        for (Node child : grid.getChildren()) {
            int c = index(GridPane.getColumnIndex(child));
            if (index(GridPane.getRowIndex(child)) == row && c > column && c < bestColumn) {
                Control field = firstUnnamed(child);
                if (field != null) {
                    best = field;
                    bestColumn = c;
                }
            }
        }
        return best;
    }

    private static int index(@Nullable Integer index) {
        return index == null ? 0 : index;
    }

    /** Whether a label under {@code root} names {@code control}. */
    static boolean labelled(Node root, Node control) {
        if (root instanceof Label label && label.getLabelFor() == control) {
            return true;
        }
        if (root instanceof Parent parent && !(root instanceof Control)) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (labelled(child, control)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Names a form's {@code field} after the form's {@code title} when nothing else names it: a card that asks
     * for one thing ("New Note") has a title that says what the field is for.
     */
    static void nameAfterTitle(Node form, @Nullable Node field, String title) {
        if (field instanceof Control control && firstUnnamed(control) != null && !labelled(form, control)) {
            control.setAccessibleText(title);
        }
    }

    /** {@code node} when it is a control without a name, or the first such control inside it; else null. */
    static @Nullable Control firstUnnamed(Node node) {
        if (node instanceof Control control) {
            boolean named = control instanceof Label
                    || control.getAccessibleText() != null
                            && !control.getAccessibleText().isBlank()
                    || control instanceof Labeled labeled
                            && labeled.getText() != null
                            && !labeled.getText().isBlank();
            return named ? null : control;
        }
        if (node instanceof Pane pane) {
            for (Node child : pane.getChildren()) {
                Control found = firstUnnamed(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
