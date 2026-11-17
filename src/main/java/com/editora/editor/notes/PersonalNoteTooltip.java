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
        // attached here would be lost. Keep the shell inline, in the theme's own roles (the user-agent theme
        // reaches the popup): the overlay ground and ink, ruled in the accent wash a note wears everywhere,
        // never a fixed paper colour (ICON-08, ICON-18). Rich Markdown graphics carry app.css.
        UiZoom.follow(
                tooltip,
                () -> tooltip.setStyle(
                        UiZoom.scaledStyle(
                                "-fx-background-color: -color-bg-overlay; -fx-text-fill: -color-fg-default; "
                                        + "-fx-border-color: -color-accent-muted; -fx-border-width: 1px; -fx-border-radius: 7px;")));
        return tooltip;
    }
}
