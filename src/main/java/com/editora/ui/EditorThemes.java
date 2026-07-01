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
 *
 * <p>The bundled AtlantaFX community themes (Blue/Navy/Army, the seasonal set, Blacky, News, Yacht,
 * …) all share the single {@code adaptive} override stylesheet, whose syntax palette is expressed in
 * each theme's own {@code -color-*} variables. Only the code-driven colors below (which JavaFX CSS
 * variables can't reach — startup background, current-line highlight, minimap) are per-theme, taken
 * from that theme's {@code -color-bg-default}/{@code -color-fg-default}/{@code -color-bg-subtle}/
 * {@code -color-fg-muted}/{@code -color-accent-emphasis}.
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
            "Islands Dark",
            "Army Light",
            "Army Dark",
            "Autumn",
            "Blacky",
            "Blue Light",
            "Blue Dark",
            "Browny",
            "Fall Light",
            "Fall Dark",
            "Navy Light",
            "Navy Dark",
            "News",
            "Spring Light",
            "Spring Dark",
            "Summer Light",
            "Summer Dark",
            "Winter Light",
            "Winter Dark",
            "Yacht");

    /** Theme name -> override stylesheet base name; Primer Light is the default (no override). */
    private static final Map<String, String> CSS = Map.ofEntries(
            Map.entry("Primer Dark", "primer-dark"),
            Map.entry("Nord Light", "nord-light"),
            Map.entry("Nord Dark", "nord-dark"),
            Map.entry("Cupertino Light", "cupertino-light"),
            Map.entry("Cupertino Dark", "cupertino-dark"),
            Map.entry("Dracula", "dracula"),
            Map.entry("Islands Light", "islands-light"),
            Map.entry("Islands Dark", "islands-dark"),
            Map.entry("Army Light", "adaptive"),
            Map.entry("Army Dark", "adaptive"),
            Map.entry("Autumn", "adaptive"),
            Map.entry("Blacky", "adaptive"),
            Map.entry("Blue Light", "adaptive"),
            Map.entry("Blue Dark", "adaptive"),
            Map.entry("Browny", "adaptive"),
            Map.entry("Fall Light", "adaptive"),
            Map.entry("Fall Dark", "adaptive"),
            Map.entry("Navy Light", "adaptive"),
            Map.entry("Navy Dark", "adaptive"),
            Map.entry("News", "adaptive"),
            Map.entry("Spring Light", "adaptive"),
            Map.entry("Spring Dark", "adaptive"),
            Map.entry("Summer Light", "adaptive"),
            Map.entry("Summer Dark", "adaptive"),
            Map.entry("Winter Light", "adaptive"),
            Map.entry("Winter Dark", "adaptive"),
            Map.entry("Yacht", "adaptive"));

    /** Theme name -> editor background color (mirrors {@code .editor-area} in each theme's CSS). */
    private static final Map<String, String> EDITOR_BG = Map.ofEntries(
            Map.entry("Primer Light", "#ffffff"),
            Map.entry("Primer Dark", "#0d1117"),
            Map.entry("Nord Light", "#eceff4"),
            Map.entry("Nord Dark", "#2e3440"),
            Map.entry("Cupertino Light", "#ffffff"),
            Map.entry("Cupertino Dark", "#1e1e1e"),
            Map.entry("Dracula", "#282a36"),
            Map.entry("Islands Light", "#ffffff"),
            Map.entry("Islands Dark", "#191a1c"),
            Map.entry("Army Light", "#e8eccc"),
            Map.entry("Army Dark", "#1c2214"),
            Map.entry("Autumn", "#304c64"),
            Map.entry("Blacky", "#000000"),
            Map.entry("Blue Light", "#ffffff"),
            Map.entry("Blue Dark", "#142342"),
            Map.entry("Browny", "#332524"),
            Map.entry("Fall Light", "#fdf8f0"),
            Map.entry("Fall Dark", "#1e0c06"),
            Map.entry("Navy Light", "#ffffff"),
            Map.entry("Navy Dark", "#1a2744"),
            Map.entry("News", "#0f172a"),
            Map.entry("Spring Light", "#ffffff"),
            Map.entry("Spring Dark", "#0c1a10"),
            Map.entry("Summer Light", "#ffffff"),
            Map.entry("Summer Dark", "#081430"),
            Map.entry("Winter Light", "#ffffff"),
            Map.entry("Winter Dark", "#080c18"),
            Map.entry("Yacht", "#f2f0ef"));

    /** Theme name -> editor foreground/text color (mirrors {@code .editor-area .text}). */
    private static final Map<String, String> EDITOR_FG = Map.ofEntries(
            Map.entry("Primer Light", "#24292f"),
            Map.entry("Primer Dark", "#c9d1d9"),
            Map.entry("Nord Light", "#2e3440"),
            Map.entry("Nord Dark", "#d8dee9"),
            Map.entry("Cupertino Light", "#1d1d1f"),
            Map.entry("Cupertino Dark", "#dfdfe0"),
            Map.entry("Dracula", "#f8f8f2"),
            Map.entry("Islands Light", "#080808"),
            Map.entry("Islands Dark", "#bcbec4"),
            Map.entry("Army Light", "#0e1208"),
            Map.entry("Army Dark", "#d4d8b0"),
            Map.entry("Autumn", "#b8dee9"),
            Map.entry("Blacky", "#f2f2f2"),
            Map.entry("Blue Light", "#0a1530"),
            Map.entry("Blue Dark", "#f4f6f9"),
            Map.entry("Browny", "#f5efe8"),
            Map.entry("Fall Light", "#100604"),
            Map.entry("Fall Dark", "#f8e0b8"),
            Map.entry("Navy Light", "#111b33"),
            Map.entry("Navy Dark", "#eef0f6"),
            Map.entry("News", "#f1f5f9"),
            Map.entry("Spring Light", "#081408"),
            Map.entry("Spring Dark", "#c8f0c8"),
            Map.entry("Summer Light", "#04102e"),
            Map.entry("Summer Dark", "#d0e8ff"),
            Map.entry("Winter Light", "#060e22"),
            Map.entry("Winter Dark", "#d0eeff"),
            Map.entry("Yacht", "#262928"));

    /** Theme name -> current-line highlight color (RichTextFX sets this in code, not via CSS). */
    private static final Map<String, String> LINE_HIGHLIGHT = Map.ofEntries(
            Map.entry("Primer Light", "#dfe7f0"),
            Map.entry("Primer Dark", "#161b22"),
            Map.entry("Nord Light", "#e5e9f0"),
            Map.entry("Nord Dark", "#3b4252"),
            Map.entry("Cupertino Light", "#ececec"),
            Map.entry("Cupertino Dark", "#2f2f2f"),
            Map.entry("Dracula", "#44475a"),
            Map.entry("Islands Light", "#fcfaed"),
            Map.entry("Islands Dark", "#1f2024"),
            Map.entry("Army Light", "#d4d8b0"),
            Map.entry("Army Dark", "#383a1a"),
            Map.entry("Autumn", "#1e3a50"),
            Map.entry("Blacky", "#1a1a1a"),
            Map.entry("Blue Light", "#f4f6f9"),
            Map.entry("Blue Dark", "#1d3050"),
            Map.entry("Browny", "#3d2b24"),
            Map.entry("Fall Light", "#f0e8d8"),
            Map.entry("Fall Dark", "#341a02"),
            Map.entry("Navy Light", "#eef0f6"),
            Map.entry("Navy Dark", "#233057"),
            Map.entry("News", "#1e293b"),
            Map.entry("Spring Light", "#dff2df"),
            Map.entry("Spring Dark", "#0f3010"),
            Map.entry("Summer Light", "#e0efff"),
            Map.entry("Summer Dark", "#0a2a58"),
            Map.entry("Winter Light", "#dce8f8"),
            Map.entry("Winter Dark", "#0a2258"),
            Map.entry("Yacht", "#f4f4f3"));

    /** Theme name -> minimap block color (the minimap is canvas-drawn, not CSS). Dark themes use a
     *  brighter block than the gutter text so the overview stays legible on a dark background. */
    private static final Map<String, String> MINIMAP_TEXT = Map.ofEntries(
            Map.entry("Primer Light", "#9aa5b1"),
            Map.entry("Primer Dark", "#9da7b3"),
            Map.entry("Nord Light", "#9aa3b2"),
            Map.entry("Nord Dark", "#aab2c4"),
            Map.entry("Cupertino Light", "#a0a0a8"),
            Map.entry("Cupertino Dark", "#9a9a9e"),
            Map.entry("Dracula", "#a6acd4"),
            Map.entry("Islands Light", "#adadad"),
            Map.entry("Islands Dark", "#8a8e96"),
            Map.entry("Army Light", "#383a1a"),
            Map.entry("Army Dark", "#9c9e68"),
            Map.entry("Autumn", "#91c8da"),
            Map.entry("Blacky", "#d4d4d4"),
            Map.entry("Blue Light", "#2a4260"),
            Map.entry("Blue Dark", "#e3e8f0"),
            Map.entry("Browny", "#e8ddd4"),
            Map.entry("Fall Light", "#381a10"),
            Map.entry("Fall Dark", "#e8c080"),
            Map.entry("Navy Light", "#233057"),
            Map.entry("Navy Dark", "#d0d4e3"),
            Map.entry("News", "#94a3b8"),
            Map.entry("Spring Light", "#163314"),
            Map.entry("Spring Dark", "#98d898"),
            Map.entry("Summer Light", "#0e3070"),
            Map.entry("Summer Dark", "#a0c8f8"),
            Map.entry("Winter Light", "#1e3672"),
            Map.entry("Winter Dark", "#a8d8f8"),
            Map.entry("Yacht", "#575a59"));

    /** Theme name -> minimap viewport-overlay color (8-digit hex incl. alpha). Dark themes use a
     *  stronger alpha so the visible-range box reads against the dark background. */
    private static final Map<String, String> MINIMAP_VIEWPORT = Map.ofEntries(
            Map.entry("Primer Light", "#0969da24"),
            Map.entry("Primer Dark", "#58a6ff40"),
            Map.entry("Nord Light", "#5e81ac29"),
            Map.entry("Nord Dark", "#88c0d040"),
            Map.entry("Cupertino Light", "#0a84ff24"),
            Map.entry("Cupertino Dark", "#0a84ff40"),
            Map.entry("Dracula", "#bd93f940"),
            Map.entry("Islands Light", "#a6d2ff80"),
            Map.entry("Islands Dark", "#56a8f540"),
            Map.entry("Army Light", "#48661024"),
            Map.entry("Army Dark", "#b2d84840"),
            Map.entry("Autumn", "#f0704040"),
            Map.entry("Blacky", "#b86e2840"),
            Map.entry("Blue Light", "#00427324"),
            Map.entry("Blue Dark", "#5aabdc40"),
            Map.entry("Browny", "#edbd2d40"),
            Map.entry("Fall Light", "#98300c24"),
            Map.entry("Fall Dark", "#ffd09040"),
            Map.entry("Navy Light", "#3d4f7324"),
            Map.entry("Navy Dark", "#c9a84c40"),
            Map.entry("News", "#6366f140"),
            Map.entry("Spring Light", "#8c205824"),
            Map.entry("Spring Dark", "#f890cc40"),
            Map.entry("Summer Light", "#005aac24"),
            Map.entry("Summer Dark", "#f8d05040"),
            Map.entry("Winter Light", "#1a4cae24"),
            Map.entry("Winter Dark", "#a0e8ff40"),
            Map.entry("Yacht", "#245f7324"));

    private EditorThemes() {}

    /** All selectable editor-theme names: the built-ins, plus user AtlantaFX themes (adaptive palette) and
     *  user editor-override themes from the config folder. */
    public static List<String> names() {
        if (UserThemes.controlThemes().isEmpty() && UserThemes.editorThemes().isEmpty()) {
            return NAMES;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>(NAMES);
        out.addAll(UserThemes.controlThemes().keySet()); // an AtlantaFX user theme → its adaptive editor theme
        out.addAll(UserThemes.editorThemes().keySet()); // a hand-authored override theme
        return List.copyOf(out);
    }

    /** The user theme entry for {@code name} (a control theme's adaptive palette, or an override theme), or null. */
    private static UserThemes.Entry userEntry(String name) {
        UserThemes.Entry c = UserThemes.controlThemes().get(name);
        return c != null ? c : UserThemes.editorThemes().get(name);
    }

    /** Normalized name (returns {@link #DEFAULT} for unrecognized input, incl. a since-removed user theme). */
    public static String normalize(String name) {
        return name != null && (NAMES.contains(name) || userEntry(name) != null) ? name : DEFAULT;
    }

    /** The editor theme that matches an AtlantaFX theme name (used until the user picks one manually). */
    public static String defaultFor(String atlantaThemeName) {
        if (atlantaThemeName != null
                && (NAMES.contains(atlantaThemeName)
                        || UserThemes.controlThemes().containsKey(atlantaThemeName))) {
            return atlantaThemeName; // a built-in or a user AtlantaFX theme pairs with the same-named editor theme
        }
        return DEFAULT;
    }

    /**
     * External-form URL of the override stylesheet for {@code name}, or {@code null} for the default
     * theme (whose colors already live in {@code app.css} / {@code syntax.css}). User AtlantaFX themes use
     * the shared {@code adaptive.css}; user editor-override themes use their own file.
     */
    public static String stylesheetFor(String name) {
        String norm = normalize(name);
        if (UserThemes.controlThemes().containsKey(norm)) {
            return EditorThemes.class
                    .getResource("/com/editora/styles/editor-themes/adaptive.css")
                    .toExternalForm();
        }
        UserThemes.Entry override = UserThemes.editorThemes().get(norm);
        if (override != null) {
            return override.stylesheetUrl();
        }
        String base = CSS.get(norm);
        if (base == null) {
            return null;
        }
        return EditorThemes.class
                .getResource("/com/editora/styles/editor-themes/" + base + ".css")
                .toExternalForm();
    }

    /** Current-line highlight color for {@code name}. */
    public static Color lineHighlightFor(String name) {
        UserThemes.Entry u = userEntry(normalize(name));
        return u != null ? u.lineHighlight() : Color.web(LINE_HIGHLIGHT.getOrDefault(normalize(name), "#dfe7f0"));
    }

    /** Editor background color for {@code name}. */
    public static Color editorBackgroundFor(String name) {
        UserThemes.Entry u = userEntry(normalize(name));
        return u != null ? u.background() : Color.web(EDITOR_BG.getOrDefault(normalize(name), "#ffffff"));
    }

    /** Editor foreground/text color for {@code name}. */
    public static Color editorForegroundFor(String name) {
        UserThemes.Entry u = userEntry(normalize(name));
        return u != null ? u.foreground() : Color.web(EDITOR_FG.getOrDefault(normalize(name), "#24292f"));
    }

    /** Minimap block color for {@code name}. */
    public static Color minimapTextFor(String name) {
        UserThemes.Entry u = userEntry(normalize(name));
        return u != null ? u.minimapText() : Color.web(MINIMAP_TEXT.getOrDefault(normalize(name), "#9aa5b1"));
    }

    /** Minimap viewport-overlay color for {@code name}. */
    public static Color minimapViewportFor(String name) {
        UserThemes.Entry u = userEntry(normalize(name));
        return u != null ? u.minimapViewport() : Color.web(MINIMAP_VIEWPORT.getOrDefault(normalize(name), "#0969da24"));
    }
}
