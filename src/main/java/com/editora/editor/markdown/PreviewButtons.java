package com.editora.editor.markdown;

import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;

import com.editora.i18n.Messages;

/**
 * The glyph buttons floating over a preview (zoom out / zoom in / light-dark). A bare "−", "+" or "☾"
 * says nothing to a screen reader and little at a glance, so each is built with a name that serves as
 * both its tooltip and its accessible text.
 */
public final class PreviewButtons {

    private PreviewButtons() {}

    /** A compact glyph button named {@code name} (pass "" and call {@link #name} when the name varies). */
    public static Button named(String glyph, String name, Runnable action) {
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
    public static String themeActionKey(String theme) {
        return theme.isEmpty()
                ? "markdown.previewTheme.toFollow"
                : theme.equals("light") ? "markdown.previewTheme.toLight" : "markdown.previewTheme.toDark";
    }

    /**
     * The strip shown at the foot of a preview that no longer follows edits (a document too large for the
     * live preview): what is going on, and a button that renders the current text. Such a preview used to
     * render once and then quietly stay as it was.
     */
    public static javafx.scene.Node pausedStrip(Runnable refresh) {
        javafx.scene.control.Label note = new javafx.scene.control.Label(Messages.tr("markdown.preview.paused"));
        note.getStyleClass().add("md-preview-paused-label");
        Button button = new Button(Messages.tr("markdown.preview.refresh"));
        button.getStyleClass().add("md-preview-paused-button");
        button.setOnAction(e -> refresh.run());
        javafx.scene.layout.HBox strip = new javafx.scene.layout.HBox(10, note, button);
        strip.getStyleClass().add("md-preview-paused");
        strip.setAlignment(javafx.geometry.Pos.CENTER);
        strip.setMaxSize(javafx.scene.layout.Region.USE_PREF_SIZE, javafx.scene.layout.Region.USE_PREF_SIZE);
        javafx.scene.layout.StackPane.setAlignment(strip, javafx.geometry.Pos.BOTTOM_CENTER);
        javafx.scene.layout.StackPane.setMargin(strip, new javafx.geometry.Insets(0, 0, 12, 0));
        return strip;
    }

    /** Sets the tooltip and the accessible name together, so the two can never drift apart. */
    public static void name(Button button, String name) {
        button.setAccessibleText(name);
        button.setTooltip(new Tooltip(name));
    }
}
