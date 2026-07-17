package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import javafx.scene.paint.Color;

/**
 * User-defined themes loaded from the config folder (first pass — discovered once at startup, plus a
 * palette "reload" command). Two folders:
 *
 * <ul>
 *   <li><b>{@code <configDir>/themes/*.css}</b> — full AtlantaFX control themes (self-contained
 *       user-agent stylesheets defining {@code -color-*}). Each becomes an app/control theme
 *       ({@link Themes}) <em>and</em> an editor theme ({@link EditorThemes}) using the shared adaptive
 *       syntax palette; the code-driven colors (startup bg, current-line highlight, minimap) are parsed
 *       from the CSS's {@code -color-*} values.</li>
 *   <li><b>{@code <configDir>/editor-themes/*.css}</b> — hand-authored editor override stylesheets
 *       (like the bundled {@code styles/editor-themes/*.css}: {@code .editor-area} + {@code .text.<class>}
 *       rules). Each becomes an editor theme only; the app theme is unchanged.</li>
 * </ul>
 *
 * <p>The theme name is derived from the file name (kebab/underscore → Title Case). A user name that
 * collides with a built-in is skipped (the built-in wins). Pure helpers ({@link #titleCase},
 * {@link #parseColor}) are unit-tested; {@link Color#web} needs no toolkit.
 */
final class UserThemes {

    /** A discovered user theme: its picker name, stylesheet URL, and the code-driven colors. */
    record Entry(
            String name,
            String stylesheetUrl,
            boolean dark,
            Color background,
            Color foreground,
            Color lineHighlight,
            Color minimapText,
            Color minimapViewport) {}

    private static final long MAX_CSS_BYTES = 4L * 1024 * 1024; // ignore absurdly large files

    private static Map<String, Entry> controlThemes = Map.of();
    private static Map<String, Entry> editorThemes = Map.of();

    private UserThemes() {}

    /** Rescans {@code <configDir>/themes} and {@code <configDir>/editor-themes}. Safe if the dirs are absent. */
    static void load(Path configDir) {
        if (configDir == null) {
            controlThemes = Map.of();
            editorThemes = Map.of();
            return;
        }
        controlThemes = scan(configDir.resolve("themes"), true);
        editorThemes = scan(configDir.resolve("editor-themes"), false);
    }

    /** User AtlantaFX control themes (name → entry); the entry's stylesheet is the control CSS file. */
    static Map<String, Entry> controlThemes() {
        return controlThemes;
    }

    /** User editor override themes (name → entry); the entry's stylesheet is the override CSS file. */
    static Map<String, Entry> editorThemes() {
        return editorThemes;
    }

    private static Map<String, Entry> scan(Path dir, boolean controlKind) {
        Map<String, Entry> out = new LinkedHashMap<>();
        if (dir == null || !Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(p -> p.getFileName()
                            .toString()
                            .toLowerCase(java.util.Locale.ROOT)
                            .endsWith(".css"))
                    .sorted()
                    .forEach(p -> {
                        Entry e = parse(p, controlKind);
                        if (e != null && !isBuiltIn(e.name())) {
                            out.putIfAbsent(e.name(), e);
                        }
                    });
        } catch (IOException ignored) {
            // Unreadable dir → no user themes from it.
        }
        return out;
    }

    private static boolean isBuiltIn(String name) {
        return Themes.NAMES.contains(name) || EditorThemes.NAMES.contains(name);
    }

    private static Entry parse(Path css, boolean controlKind) {
        String text;
        try {
            if (Files.size(css) > MAX_CSS_BYTES) {
                return null;
            }
            text = Files.readString(css);
        } catch (IOException e) {
            return null;
        }
        String name = titleCase(baseName(css.getFileName().toString()));
        if (name.isBlank() || !looksLikeStylesheet(text)) {
            return null;
        }
        java.net.URI uri = css.toUri();
        String url = uri.toString();

        // Background/foreground: prefer the AtlantaFX -color-* vars (control themes + adaptive-style editor
        // themes); fall back to the editor-surface CSS props for hand-authored overrides, else defaults.
        Color bg = firstNonNull(parseColor(text, "-color-bg-default"), parseColor(text, "-fx-background-color"));
        boolean dark = bg != null ? isDark(bg) : false;
        if (bg == null) {
            bg = dark ? Color.web("#1e1e1e") : Color.web("#ffffff");
        }
        Color fg = firstNonNull(parseColor(text, "-color-fg-default"), parseColor(text, "-fx-fill"));
        if (fg == null) {
            fg = dark ? Color.web("#dddddd") : Color.web("#24292f");
        }
        Color sub = parseColor(text, "-color-bg-subtle");
        Color line = sub != null ? sub : bg.interpolate(dark ? Color.WHITE : Color.BLACK, 0.08);
        Color fgMuted = parseColor(text, "-color-fg-muted");
        Color minimapText = fgMuted != null ? fgMuted : fg.interpolate(bg, 0.45);
        Color accent = parseColor(text, "-color-accent-emphasis");
        Color minimapViewport =
                accent != null ? accent.deriveColor(0, 1, 1, 0.25) : Color.web(dark ? "#58a6ff40" : "#0969da24");

        // Control themes are served by the shared adaptive editor palette; their entry still carries the
        // control CSS url (used by Themes). Editor override themes carry their own CSS url (used by
        // EditorThemes.stylesheetFor). controlKind selects which url the caller ultimately uses.
        return new Entry(name, url, dark, bg, fg, line, minimapText, minimapViewport);
    }

    /**
     * Whether {@code css} actually declares anything — it must set at least one AtlantaFX {@code -color-*}
     * token or one JavaFX {@code -fx-*} property.
     *
     * <p>Anything ending in {@code .css} in the folder is offered as a theme, so without this an empty file,
     * a half-written one, or a stray text file becomes a selectable theme named after it — and picking it
     * applies an empty user-agent stylesheet, stripping AtlantaFX from the whole app.
     */
    private static boolean looksLikeStylesheet(String css) {
        return css.contains("-color-") || css.contains("-fx-");
    }

    private static Color firstNonNull(Color a, Color b) {
        return a != null ? a : b;
    }

    /** Relative luminance test (Rec. 601) — used to pick sensible fallback fg / accents. */
    private static boolean isDark(Color c) {
        double l = 0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue();
        return l < 0.5;
    }

    /** The file name without its extension. */
    static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** {@code my-cool-theme} / {@code my_cool_theme} → {@code My Cool Theme}. */
    static String titleCase(String base) {
        String[] parts = base.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    /** How many {@code -a: -b;} hops {@link #parseColor} will follow before giving up (also breaks cycles). */
    private static final int MAX_VAR_HOPS = 4;

    private static final Pattern HEX = Pattern.compile("#([0-9a-fA-F]{6}([0-9a-fA-F]{2})?)\\b");

    /** A value that is just another variable name, e.g. {@code -color-bg-default: -color-dark-1;}. */
    private static final Pattern VAR_REF = Pattern.compile("^\\s*(-[A-Za-z0-9_-]+)\\s*$");

    /**
     * The colour {@code token} resolves to: the first {@code #rrggbb}/{@code #rrggbbaa} hex in its value, or —
     * when the value is just another variable — that variable's colour, up to {@link #MAX_VAR_HOPS} hops.
     * Returns null if it resolves to no literal colour.
     *
     * <p>Following the indirection matters because a token defined by reference is ordinary CSS authoring
     * (define a palette, map the tokens onto it). Reporting "no colour" for one silently defaults the editor
     * to a white background and the light syntax palette — so a dark theme rendered dark chrome around a
     * glaring white editor. The bundled themes and AtlantaFX's own all use literal hex, which is why the
     * happy path never showed it.
     */
    static Color parseColor(String css, String token) {
        return parseColor(css, token, MAX_VAR_HOPS);
    }

    private static Color parseColor(String css, String token, int hopsLeft) {
        // The lookahead keeps `-color-bg-default` from matching a longer token that merely starts with it.
        Matcher m = Pattern.compile(Pattern.quote(token) + "(?![A-Za-z0-9_-])\\s*:?\\s*([^;{}]*)")
                .matcher(css);
        while (m.find()) {
            String value = m.group(1);
            Matcher hex = HEX.matcher(value);
            if (hex.find()) {
                try {
                    return Color.web(hex.group());
                } catch (IllegalArgumentException ignored) {
                    // keep scanning
                }
            }
            Matcher ref = VAR_REF.matcher(value);
            if (hopsLeft > 0 && ref.find() && !ref.group(1).equals(token)) {
                Color referenced = parseColor(css, ref.group(1), hopsLeft - 1);
                if (referenced != null) {
                    return referenced;
                }
            }
        }
        return null;
    }
}
