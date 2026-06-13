package com.editora.ui;

import java.util.List;
import java.util.Map;

import javafx.scene.paint.Color;

/**
 * Catalog of editor color themes — the syntax-token and editor-surface colors, separate from the
 * AtlantaFX control theme ({@link Themes}). Each theme is an override stylesheet layered after
 * {@code app.css} + {@code syntax.css} (which are the Primer Light defaults), plus a current-line
 * highlight color applied to each buffer in code.
 *
 * <p>Theme names mirror the AtlantaFX theme names so an AtlantaFX selection has a natural matching
 * editor theme ({@link #defaultFor}); the user can still pick a different editor theme.
 */
public final class EditorThemes {

    public static final String DEFAULT = "Primer Light";

    public static final List<String> NAMES = List.of(
            "Primer Light",
            "Primer Dark",
            "Nord Light",
            "Nord Dark",
            "Cupertino Light",
            "Cupertino Dark",
            "Dracula",
            "Islands Light",
            "Islands Dark");

    /** Theme name -> override stylesheet base name; Primer Light is the default (no override). */
    private static final Map<String, String> CSS = Map.of(
            "Primer Dark", "primer-dark",
            "Nord Light", "nord-light",
            "Nord Dark", "nord-dark",
            "Cupertino Light", "cupertino-light",
            "Cupertino Dark", "cupertino-dark",
            "Dracula", "dracula",
            "Islands Light", "islands-light",
            "Islands Dark", "islands-dark");

    /** Theme name -> editor background color (mirrors {@code .editor-area} in each theme's CSS). */
    private static final Map<String, String> EDITOR_BG = Map.of(
            "Primer Light", "#ffffff",
            "Primer Dark", "#0d1117",
            "Nord Light", "#eceff4",
            "Nord Dark", "#2e3440",
            "Cupertino Light", "#ffffff",
            "Cupertino Dark", "#1e1e1e",
            "Dracula", "#282a36",
            "Islands Light", "#ffffff",
            "Islands Dark", "#191a1c");

    /** Theme name -> editor foreground/text color (mirrors {@code .editor-area .text}). */
    private static final Map<String, String> EDITOR_FG = Map.of(
            "Primer Light", "#24292f",
            "Primer Dark", "#c9d1d9",
            "Nord Light", "#2e3440",
            "Nord Dark", "#d8dee9",
            "Cupertino Light", "#1d1d1f",
            "Cupertino Dark", "#dfdfe0",
            "Dracula", "#f8f8f2",
            "Islands Light", "#080808",
            "Islands Dark", "#bcbec4");

    /** Theme name -> current-line highlight color (RichTextFX sets this in code, not via CSS). */
    private static final Map<String, String> LINE_HIGHLIGHT = Map.of(
            "Primer Light", "#dfe7f0",
            "Primer Dark", "#161b22",
            "Nord Light", "#e5e9f0",
            "Nord Dark", "#3b4252",
            "Cupertino Light", "#ececec",
            "Cupertino Dark", "#2f2f2f",
            "Dracula", "#44475a",
            "Islands Light", "#fcfaed",
            "Islands Dark", "#1f2024");

    /** Theme name -> minimap block color (the minimap is canvas-drawn, not CSS). Dark themes use a
     *  brighter block than the gutter text so the overview stays legible on a dark background. */
    private static final Map<String, String> MINIMAP_TEXT = Map.of(
            "Primer Light", "#9aa5b1",
            "Primer Dark", "#9da7b3",
            "Nord Light", "#9aa3b2",
            "Nord Dark", "#aab2c4",
            "Cupertino Light", "#a0a0a8",
            "Cupertino Dark", "#9a9a9e",
            "Dracula", "#a6acd4",
            "Islands Light", "#adadad",
            "Islands Dark", "#8a8e96");

    /** Theme name -> minimap viewport-overlay color (8-digit hex incl. alpha). Dark themes use a
     *  stronger alpha so the visible-range box reads against the dark background. */
    private static final Map<String, String> MINIMAP_VIEWPORT = Map.of(
            "Primer Light", "#0969da24",
            "Primer Dark", "#58a6ff40",
            "Nord Light", "#5e81ac29",
            "Nord Dark", "#88c0d040",
            "Cupertino Light", "#0a84ff24",
            "Cupertino Dark", "#0a84ff40",
            "Dracula", "#bd93f940",
            "Islands Light", "#a6d2ff80",
            "Islands Dark", "#56a8f540");

    private EditorThemes() {}

    /** Normalized name (returns {@link #DEFAULT} for unrecognized input). */
    public static String normalize(String name) {
        return NAMES.contains(name) ? name : DEFAULT;
    }

    /** The editor theme that matches an AtlantaFX theme name (used until the user picks one manually). */
    public static String defaultFor(String atlantaThemeName) {
        return NAMES.contains(atlantaThemeName) ? atlantaThemeName : DEFAULT;
    }

    /**
     * External-form URL of the override stylesheet for {@code name}, or {@code null} for the default
     * theme (whose colors already live in {@code app.css} / {@code syntax.css}).
     */
    public static String stylesheetFor(String name) {
        String base = CSS.get(normalize(name));
        if (base == null) {
            return null;
        }
        return EditorThemes.class
                .getResource("/com/editora/styles/editor-themes/" + base + ".css")
                .toExternalForm();
    }

    /** Current-line highlight color for {@code name}. */
    public static Color lineHighlightFor(String name) {
        return Color.web(LINE_HIGHLIGHT.getOrDefault(normalize(name), "#dfe7f0"));
    }

    /** Editor background color for {@code name}. */
    public static Color editorBackgroundFor(String name) {
        return Color.web(EDITOR_BG.getOrDefault(normalize(name), "#ffffff"));
    }

    /** Editor foreground/text color for {@code name}. */
    public static Color editorForegroundFor(String name) {
        return Color.web(EDITOR_FG.getOrDefault(normalize(name), "#24292f"));
    }

    /** Minimap block color for {@code name}. */
    public static Color minimapTextFor(String name) {
        return Color.web(MINIMAP_TEXT.getOrDefault(normalize(name), "#9aa5b1"));
    }

    /** Minimap viewport-overlay color for {@code name}. */
    public static Color minimapViewportFor(String name) {
        return Color.web(MINIMAP_VIEWPORT.getOrDefault(normalize(name), "#0969da24"));
    }
}
