package com.editora.editor.structured;

import java.util.List;
import java.util.function.Supplier;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.DoubleBinding;
import javafx.beans.value.ObservableDoubleValue;
import javafx.scene.Node;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import com.editora.editor.UiZoom;

/**
 * The two shapes of a structured preview's rows (PERF-08, Pauta 6.0.1). On screen a preview is a virtualized list: a
 * file can hold any number of jobs, hosts, operations or entries, and only the rows on screen have nodes. Print and
 * Export to PDF take a picture of the whole preview, so {@link #column} builds every row once, in a plain column, for
 * them. A row is a {@link Supplier} of its node, so both shapes show the same rows with the same nodes.
 *
 * <p>The list looks as the column did: a read-out that takes no Tab stop and shows no selected or hovered row, its
 * cells padded as the column was spaced ({@code .structured-preview-list} in {@code app.css}). A wrapping label needs
 * a definite width to say how tall it is, so each row's labels are given the list's width, less its own chrome and the
 * padding of the boxes they sit in.
 */
public final class PreviewList {

    /** What the list's own padding and its scroll bar take from a row's width. */
    private static final double ROW_CHROME = 36;

    private PreviewList() {}

    /** The live preview: {@code rows} in a virtualized list with the style classes {@code styleClasses}. */
    public static ListView<Supplier<Node>> of(List<Supplier<Node>> rows, String... styleClasses) {
        ListView<Supplier<Node>> list = new ListView<>();
        list.getStyleClass().add("structured-preview-list");
        list.getStyleClass().addAll(styleClasses);
        list.setFocusTraversable(false);
        list.getItems().setAll(rows);
        DoubleBinding rowWidth = Bindings.createDoubleBinding(
                () -> Math.max(UiZoom.px(60), list.getWidth() - UiZoom.px(ROW_CHROME)),
                list.widthProperty(),
                UiZoom.factorProperty());
        list.setCellFactory(view -> new ListCell<>() {
            {
                setPrefWidth(0); // never wider than the list: the text wraps, it does not scroll sideways
            }

            @Override
            protected void updateItem(Supplier<Node> row, boolean empty) {
                super.updateItem(row, empty);
                setText(null);
                if (empty || row == null) {
                    setGraphic(null);
                    return;
                }
                Node node = row.get();
                if (node instanceof Region region) {
                    region.prefWidthProperty().bind(rowWidth);
                    region.maxWidthProperty().bind(rowWidth);
                    region.setMinHeight(Region.USE_PREF_SIZE);
                }
                fit(node, rowWidth);
                setGraphic(node);
            }
        });
        // A row's height follows its wrapped text, so a new width asks the rows on screen for their height again.
        list.widthProperty().addListener((o, was, now) -> list.refresh());
        return list;
    }

    /** The picture an export or a print takes: every row, in a column; {@code width > 0} pins its layout width. */
    public static VBox column(List<Supplier<Node>> rows, double width, String styleClass) {
        VBox root = new VBox();
        root.getStyleClass().add(styleClass);
        if (width > 0) {
            root.setPrefWidth(width);
        }
        for (Supplier<Node> row : rows) {
            root.getChildren().add(row.get());
        }
        return root;
    }

    /** Gives each wrapping label under {@code node} the width it has: {@code width}, less the boxes' padding. */
    private static void fit(Node node, ObservableDoubleValue width) {
        if (node instanceof Label label) {
            if (label.isWrapText()) {
                label.maxWidthProperty().bind(Bindings.createDoubleBinding(width::get, width));
                label.setMinHeight(Region.USE_PREF_SIZE);
            }
            return;
        }
        if (node instanceof Control || !(node instanceof Region region)) {
            return;
        }
        DoubleBinding inner = Bindings.createDoubleBinding(
                () -> Math.max(
                        0,
                        width.get()
                                - region.getInsets().getLeft()
                                - region.getInsets().getRight()),
                width,
                region.insetsProperty());
        for (Node child : region.getChildrenUnmodifiable()) {
            fit(child, inner);
        }
    }
}
