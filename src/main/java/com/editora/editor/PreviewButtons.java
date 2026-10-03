package com.editora.editor;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/**
 * The glyph buttons floating over a preview (zoom out / zoom in / light-dark). A bare "−", "+" or "☾"
 * says nothing to a screen reader and little at a glance, so each is built with a name that serves as
 * both its tooltip and its accessible text.
 */
final class PreviewButtons {

    private PreviewButtons() {}

    /** A compact glyph button named {@code name} (pass "" and call {@link #name} when the name varies). */
    static Button named(String glyph, String name, Runnable action) {
        Button b = new Button(glyph);
        b.getStyleClass().addAll("md-zoom-button", "flat");
        b.setFocusTraversable(false);
        b.setOnAction(e -> action.run());
        if (!name.isEmpty()) {
            name(b, name);
        }
        return b;
    }

    /** Sets the tooltip and the accessible name together, so the two can never drift apart. */
    static void name(Button button, String name) {
        button.setAccessibleText(name);
        button.setTooltip(new Tooltip(name));
    }
}
