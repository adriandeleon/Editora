package com.editora.editor;

import java.util.Map;

import javafx.geometry.Pos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

import com.editora.completion.CompletionIconKind;

/**
 * Per-kind glyphs for the completion popup (IntelliJ-style icon column). A package-local helper in the
 * {@code editor} package — mirroring {@code editor/MenuIcons} (the {@code of(content)} idiom) and
 * {@code ui/FileIcons} (the key→path map + fixed-size {@link #boxed} so labels line up) — so the editor
 * keeps its independence from the {@code ui} package.
 *
 * <p>Glyphs are Material Design 24dp single-path SVGs, themed via the {@code toolbar-icon} style class
 * (tracks the light/dark theme for free). Each call returns a fresh {@link Node}.
 */
final class CompletionIcons {

    private static final double ICON_SCALE = 0.7;
    private static final double ICON_BOX = 18;

    private CompletionIcons() {}

    /** Glyph key (from {@link CompletionIconKind#iconKey()}) → SVG path content. */
    private static final Map<String, String> PATHS = Map.ofEntries(
            // method / function — Material "functions" (Σ).
            Map.entry("method", "M18 4H6v2l6.5 6L6 18v2h12v-3h-7l5-5-5-5h7z"),
            // field / property — Material "label" (tag).
            Map.entry(
                    "field",
                    "M17.63 5.84C17.27 5.33 16.67 5 16 5L5 5.01C3.9 5.01 3 5.9 3 7v10c0 1.1.9 1.99 2 "
                            + "1.99L16 19c.67 0 1.27-.33 1.63-.84L22 12l-4.37-6.16z"),
            // variable — Material "data_object" ({ }).
            Map.entry(
                    "variable",
                    "M4 7v2c0 .55-.45 1-1 1H2v4h1c.55 0 1 .45 1 1v2c0 1.65 1.35 3 3 3h3v-2H7c-.55 0-1-.45-1"
                            + "-1v-2c0-1.3-.84-2.42-2-2.83v-.34C5.16 11.42 6 10.3 6 9V7c0-.55.45-1 1-1h3V4H7C5.35 "
                            + "4 4 5.35 4 7zm17 3c-.55 0-1-.45-1-1V7c0-1.65-1.35-3-3-3h-3v2h3c.55 0 1 .45 1 1v2c0 "
                            + "1.3.84 2.42 2 2.83v.34c-1.16.41-2 1.52-2 2.83v2c0 .55-.45 1-1 1h-3v2h3c1.65 0 3-1.35 "
                            + "3-3v-2c0-.55.45-1 1-1h1v-4h-1z"),
            // class / struct — Material "crop_square" (a type box).
            Map.entry(
                    "class",
                    "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V5h14v14z"),
            // interface — Material "panorama_fish_eye" (a ring).
            Map.entry(
                    "interface",
                    "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3"
                            + ".59-8 8-8 8 3.59 8 8-3.59 8-8 8z"),
            // enum — Material "list".
            Map.entry(
                    "enum",
                    "M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 "
                            + "0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 "
                            + "1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0"
                            + "-8v2h14V5H7z"),
            // module / namespace — Material "inventory_2"-like box (reuses the archive glyph).
            Map.entry(
                    "module",
                    "M20 6h-8l-2-2H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2z"),
            // keyword — Material "vpn_key".
            Map.entry(
                    "keyword",
                    "M12.65 10C11.83 7.67 9.61 6 7 6c-3.31 0-6 2.69-6 6s2.69 6 6 6c2.61 0 4.83-1.67 5.65-4H17v4h4v"
                            + "-4h2v-4H12.65zM7 14c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z"),
            // snippet — Material "code" (<>).
            Map.entry(
                    "snippet",
                    "M9.4 16.6 4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0 4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"),
            // text / word — Material "text_fields".
            Map.entry("text", "M2.5 4v3h5v12h3V7h5V4h-13zm19 5h-9v3h3v7h3v-7h3V9z"),
            // file — Material "description".
            Map.entry(
                    "file",
                    "M14 2H6c-1.1 0-1.99.9-1.99 2L4 20c0 1.1.89 2 1.99 2H18c1.1 0 2-.9 2-2V8l-6-6zm2 16H8v-2h8v2zm0"
                            + "-4H8v-2h8v2zm-3-5V3.5L18.5 9H13z"),
            // folder — Material "folder".
            Map.entry(
                    "folder",
                    "M10 4H4c-1.1 0-1.99.9-1.99 2L2 18c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V8c0-1.1-.9-2-2-2h-8l-2-2z"),
            // color — Material "palette".
            Map.entry(
                    "color",
                    "M12 2C6.49 2 2 6.49 2 12s4.49 10 10 10c1.38 0 2.5-1.12 2.5-2.5 0-.61-.23-1.2-.64-1.67-.08-.1"
                            + "-.13-.21-.13-.33 0-.28.22-.5.5-.5H16c3.31 0 6-2.69 6-6 0-4.96-4.49-9-10-9zm5.5 11c-.83 "
                            + "0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zm-3-4c-.83 0-1.5-.67-1.5"
                            + "-1.5S13.67 6 14.5 6s1.5.67 1.5 1.5S15.33 9 14.5 9zM5 11.5C5 10.67 5.67 10 6.5 10s1.5.67 "
                            + "1.5 1.5S7.33 13 6.5 13 5 12.33 5 11.5zm6-4C11 6.67 10.33 6 9.5 6S8 6.67 8 7.5 8.67 9 9.5 "
                            + "9 11 8.33 11 7.5z"),
            // other / fallback — a small filled dot.
            Map.entry("other", "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4z"));

    /** A fresh glyph node for {@code kind}, centered in a fixed-size box so labels line up. */
    static Node forKind(CompletionIconKind kind) {
        String key = kind == null ? "other" : kind.iconKey();
        String path = PATHS.getOrDefault(key, PATHS.get("other"));
        return boxed(of(path));
    }

    private static Node of(String content) {
        SVGPath svg = new SVGPath();
        svg.setContent(content);
        svg.getStyleClass().add("toolbar-icon");
        svg.setScaleX(ICON_SCALE);
        svg.setScaleY(ICON_SCALE);
        return new Group(svg);
    }

    private static Node boxed(Node icon) {
        StackPane box = new StackPane(icon);
        box.getStyleClass().add("completion-icon");
        box.setMinSize(ICON_BOX, ICON_BOX);
        box.setPrefSize(ICON_BOX, ICON_BOX);
        box.setMaxSize(ICON_BOX, ICON_BOX);
        box.setAlignment(Pos.CENTER);
        box.setMouseTransparent(true);
        return box;
    }
}
