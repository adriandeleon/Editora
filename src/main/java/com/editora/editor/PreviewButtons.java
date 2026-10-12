package com.editora.editor;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

/**
 * The glyph buttons floating over a preview (zoom out / zoom in / light-dark). A bare "−", "+" or "☾"
 * says nothing to a screen reader and little at a glance, so each is built with a name that serves as
 * both its tooltip and its accessible text.
 */
public final class PreviewButtons {

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

    /**
     * The preview theme a click on the toggle switches to, from {@code mode} ("" = follow the app, "light",
     * "dark"): following → the theme the app is <em>not</em> in (so the first click shows a change) → the
     * app's own theme, pinned → following again. The toggle used to flip between light and dark only, so the
     * first click pinned the preview for good. Pure.
     */
    public static String nextTheme(String mode, boolean appDark) {
        String opposite = appDark ? "light" : "dark";
        String same = appDark ? "dark" : "light";
        if (mode == null || mode.isEmpty()) {
            return opposite;
        }
        return mode.equals(opposite) ? same : "";
    }

    /** The catalog key naming the action that switches to {@code theme} ("" = follow the app). Pure. */
    static String themeActionKey(String theme) {
        return theme.isEmpty()
                ? "markdown.previewTheme.toFollow"
                : theme.equals("light") ? "markdown.previewTheme.toLight" : "markdown.previewTheme.toDark";
    }

    /** Sets the tooltip and the accessible name together, so the two can never drift apart. */
    static void name(Button button, String name) {
        button.setAccessibleText(name);
        button.setTooltip(new Tooltip(name));
    }
}
