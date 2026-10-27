package com.editora.terminal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.paint.Color;

import org.jspecify.annotations.Nullable;

/**
 * The terminal's own colour schemes, chosen apart from the editor theme ({@code terminalTheme} in the settings).
 *
 * <p>{@link #FOLLOW_EDITOR} (blank) keeps the palette derived from whatever editor theme is active — see
 * {@link TerminalPalettes#derive}. Every other name is a fixed sixteen-colour scheme, the values its authors
 * publish, so a shell looks the same as in the terminal application the scheme came from. The two Editora schemes
 * are the derived palettes of the flagship editor pair, for someone who wants them whatever the editor shows.
 *
 * <p>Schemes whose bright black is their own background (Solarized) are not altered here: the view raises every
 * cell's text to {@link TerminalPalette#MIN_CONTRAST} as it draws.
 */
public final class TerminalThemes {

    private TerminalThemes() {}

    /** The setting's value for "use the editor theme's colours": the default. */
    public static final String FOLLOW_EDITOR = "";

    private static final Map<String, TerminalPalette> SCHEMES = new LinkedHashMap<>();

    static {
        SCHEMES.put("Editora Dark", TerminalPalettes.EDITORA_DARK);
        SCHEMES.put("Editora Light", TerminalPalettes.EDITORA_LIGHT);
        String[] solarized = {
            "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
            "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3"
        };
        SCHEMES.put("Solarized Dark", scheme("#002b36", "#839496", solarized));
        SCHEMES.put("Solarized Light", scheme("#fdf6e3", "#657b83", solarized));
        SCHEMES.put(
                "Dracula",
                scheme(
                        "#282a36", "#f8f8f2", "#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6",
                        "#8be9fd", "#f8f8f2", "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5", "#d6acff", "#ff92df",
                        "#a4ffff", "#ffffff"));
        SCHEMES.put(
                "Nord",
                scheme(
                        "#2e3440", "#d8dee9", "#3b4252", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead",
                        "#88c0d0", "#e5e9f0", "#4c566a", "#bf616a", "#a3be8c", "#ebcb8b", "#81a1c1", "#b48ead",
                        "#8fbcbb", "#eceff4"));
        SCHEMES.put(
                "Gruvbox Dark",
                scheme(
                        "#282828", "#ebdbb2", "#282828", "#cc241d", "#98971a", "#d79921", "#458588", "#b16286",
                        "#689d6a", "#a89984", "#928374", "#fb4934", "#b8bb26", "#fabd2f", "#83a598", "#d3869b",
                        "#8ec07c", "#ebdbb2"));
        SCHEMES.put(
                "Gruvbox Light",
                scheme(
                        "#fbf1c7", "#3c3836", "#fbf1c7", "#cc241d", "#98971a", "#d79921", "#458588", "#b16286",
                        "#689d6a", "#7c6f64", "#928374", "#9d0006", "#79740e", "#b57614", "#076678", "#8f3f71",
                        "#427b58", "#3c3836"));
        SCHEMES.put(
                "One Half Dark",
                scheme(
                        "#282c34", "#dcdfe4", "#282c34", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd",
                        "#56b6c2", "#dcdfe4", "#5a6374", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd",
                        "#56b6c2", "#dcdfe4"));
        SCHEMES.put(
                "One Half Light",
                scheme(
                        "#fafafa", "#383a42", "#383a42", "#e45649", "#50a14f", "#c18401", "#0184bc", "#a626a4",
                        "#0997b3", "#fafafa", "#4f525e", "#e06c75", "#98c379", "#e5c07b", "#61afef", "#c678dd",
                        "#56b6c2", "#ffffff"));
        String[] tango = {
            "#2e3436", "#cc0000", "#4e9a06", "#c4a000", "#3465a4", "#75507b", "#06989a", "#d3d7cf",
            "#555753", "#ef2929", "#8ae234", "#fce94f", "#729fcf", "#ad7fa8", "#34e2e2", "#eeeeec"
        };
        SCHEMES.put("Tango Dark", scheme("#2e3436", "#d3d7cf", tango));
        SCHEMES.put("Tango Light", scheme("#eeeeec", "#2e3436", tango));
        SCHEMES.put(
                "Campbell",
                scheme(
                        "#0c0c0c", "#cccccc", "#0c0c0c", "#c50f1f", "#13a10e", "#c19c00", "#0037da", "#881798",
                        "#3a96dd", "#cccccc", "#767676", "#e74856", "#16c60c", "#f9f1a5", "#3b78ff", "#b4009e",
                        "#61d6d6", "#f2f2f2"));
    }

    /** The fixed schemes' names, in the order the picker lists them. */
    public static final List<String> NAMES = List.copyOf(SCHEMES.keySet());

    /** The scheme called {@code name}; {@code null} for {@link #FOLLOW_EDITOR} or a name that is not a scheme. */
    public static @Nullable TerminalPalette palette(@Nullable String name) {
        return name == null ? null : SCHEMES.get(name);
    }

    /** {@code name} when it is a scheme, else {@link #FOLLOW_EDITOR}: a name this version does not know follows the editor. */
    public static String normalize(@Nullable String name) {
        return name != null && SCHEMES.containsKey(name) ? name : FOLLOW_EDITOR;
    }

    /** Whether the scheme {@code name} has a dark ground. */
    public static boolean isDark(String name) {
        TerminalPalette p = SCHEMES.get(name);
        return p != null && TerminalPalettes.isDark(p.background());
    }

    private static TerminalPalette scheme(String background, String foreground, String... ansi) {
        Color[] colors = new Color[ansi.length];
        for (int i = 0; i < ansi.length; i++) {
            colors[i] = Color.web(ansi[i]);
        }
        return new TerminalPalette(Color.web(background), Color.web(foreground), colors);
    }
}
