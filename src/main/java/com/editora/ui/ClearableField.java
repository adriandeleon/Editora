package com.editora.ui;

import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;

import static com.editora.i18n.Messages.tr;

/**
 * Builds the trailing clear ("✕") button shared by the app's filter/search fields (Project, Notes,
 * Structure, Bookmarks, and — via this helper — the Find bar, Find in Files, Git Log and TODO
 * windows). The button is styled {@code project-filter-clear}, is not focus-traversable, shows only
 * while the field has text, and on click empties the field and returns focus to it. Callers place the
 * returned button in their own layout (usually inside a {@code project-filter-bar} HBox beside the
 * field).
 */
final class ClearableField {

    private ClearableField() {}

    /** A clear ("✕") button bound to {@code field}: visible/managed only while the field has text. */
    static Button clearButton(TextField field) {
        Button clear = new Button("✕");
        clear.getStyleClass().add("project-filter-clear");
        clear.setFocusTraversable(false);
        clear.setTooltip(new Tooltip(tr("project.filterClear")));
        clear.setOnAction(e -> {
            field.clear();
            field.requestFocus();
        });
        clear.visibleProperty().bind(field.textProperty().isEmpty().not());
        clear.managedProperty().bind(clear.visibleProperty());
        return clear;
    }
}
