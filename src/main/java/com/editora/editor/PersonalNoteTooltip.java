package com.editora.editor;

import javafx.scene.control.Tooltip;

/** Creates the shared pale-paper popup shell used by Personal Note previews. */
public final class PersonalNoteTooltip {

    private PersonalNoteTooltip() {}

    public static Tooltip create() {
        return create(null);
    }

    public static Tooltip create(String text) {
        Tooltip tooltip = text == null ? new Tooltip() : new Tooltip(text);
        tooltip.getStyleClass().add("personal-note-tooltip");
        // Tooltip owns a separate popup scene and may replace it when first shown, so an author stylesheet
        // attached here would be lost. Keep the shell color inline; rich Markdown graphics carry app.css.
        tooltip.setStyle("-fx-background-color: #fff9c4; -fx-text-fill: #3d3520; "
                + "-fx-border-color: #eadf9b; -fx-border-width: 1px; -fx-border-radius: 7px;");
        return tooltip;
    }
}
