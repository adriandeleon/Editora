package com.editora.editor.notes;

import javafx.scene.control.Tooltip;

import com.editora.editor.ControlNames;
import com.editora.editor.UiZoom;
import org.jspecify.annotations.Nullable;

/** Creates the shared pale-paper popup shell used by Personal Note previews. */
public final class PersonalNoteTooltip {

    private PersonalNoteTooltip() {}

    public static Tooltip create() {
        return create(null);
    }

    public static Tooltip create(@Nullable String text) {
        Tooltip tooltip = text == null ? ControlNames.tooltip() : ControlNames.tooltip(text);
        tooltip.getStyleClass().add("personal-note-tooltip");
        // Tooltip owns a separate popup scene and may replace it when first shown, so an author stylesheet
        // attached here would be lost. Keep the shell color inline; rich Markdown graphics carry app.css.
        UiZoom.follow(
                tooltip,
                () -> tooltip.setStyle(UiZoom.scaledStyle("-fx-background-color: #fff9c4; -fx-text-fill: #3d3520; "
                        + "-fx-border-color: #eadf9b; -fx-border-width: 1px; -fx-border-radius: 7px;")));
        return tooltip;
    }
}
