package com.editora.terminal;

import javafx.scene.layout.Background;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.paint.Paint;
import javafx.scene.shape.Path;
import javafx.scene.text.Text;

/**
 * Reads the active editor theme's colours out of the scene, for {@link TerminalPalettes#derive}.
 *
 * <p>The editor themes are stylesheets: {@code .editor-area} sets the surface and plain text,
 * {@code .editor-area .caret} the caret, and {@code .text.keyword} and its siblings the token colours. Half of
 * them name no colour at all but an AtlantaFX variable ({@code -color-danger-fg}), which only the window's own
 * stylesheets resolve. So rather than parse CSS, this styles a few invisible probe nodes the way the editor
 * styles its text, lets the scene's CSS run on them, and reads back what it resolved — whatever the theme.
 */
public final class ThemeSampler {

    private ThemeSampler() {}

    /**
     * Samples the theme {@code host}'s scene has applied, or returns {@code null} when the host is not in a
     * scene yet or a rule resolved to something that is not a plain colour.
     */
    public static SyntaxColors sample(Pane host) {
        if (host == null || host.getScene() == null) {
            return null;
        }
        Text plain = probe();
        Text keyword = probe("keyword");
        Text escape = probe("escape");
        Text type = probe("type");
        Text number = probe("number");
        Text function = probe("function");
        Path caret = new Path();
        caret.getStyleClass().add("caret");
        StackPane area = new StackPane(plain, keyword, escape, type, number, function, caret);
        area.getStyleClass().add("editor-area");
        // In the scene for CSS, out of it for everything else: unmanaged (no layout), fully transparent and
        // ignored by the mouse, and removed again before the next pulse could draw it.
        area.setManaged(false);
        area.setOpacity(0);
        area.setMouseTransparent(true);
        host.getChildren().add(area);
        try {
            area.applyCss();
            Color background = backgroundOf(area.getBackground());
            Color foreground = color(plain.getFill());
            if (background == null || foreground == null) {
                return null;
            }
            return new SyntaxColors(
                    background,
                    foreground,
                    orElse(color(caret.getStroke()), foreground),
                    orElse(color(keyword.getFill()), foreground),
                    orElse(color(escape.getFill()), foreground),
                    orElse(color(type.getFill()), foreground),
                    orElse(color(number.getFill()), foreground),
                    orElse(color(function.getFill()), foreground));
        } finally {
            host.getChildren().remove(area);
        }
    }

    /** A {@code .text} probe, with the token class the editor gives text of that kind. */
    private static Text probe(String... tokenClasses) {
        Text text = new Text("x");
        text.getStyleClass().add("text");
        text.getStyleClass().addAll(tokenClasses);
        return text;
    }

    private static Color backgroundOf(Background background) {
        if (background == null || background.getFills().isEmpty()) {
            return null;
        }
        return color(background.getFills().getFirst().getFill());
    }

    /** An opaque colour, or null: a gradient or a translucent fill cannot be a terminal ground or text. */
    private static Color color(Paint paint) {
        return paint instanceof Color c && c.getOpacity() > 0.99 ? c : null;
    }

    private static Color orElse(Color c, Color fallback) {
        return c != null ? c : fallback;
    }
}
