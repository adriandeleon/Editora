package com.editora.ui;

import java.util.List;
import java.util.Map;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import atlantafx.base.theme.Theme;

/** Catalog of AtlantaFX themes available in the Settings window. */
public final class Themes {

    public static final String DEFAULT = "Primer Light";

    /**
     * Community themes vendored from the MIT-licensed
     * <a href="https://github.com/dlsc-software-consulting-gmbh/atlantafx-themes">dlsc atlantafx-themes</a>
     * collection: display name -> {css base name (under {@code styles/atlantafx-themes/}), dark flag,
     * base background hex (the theme's {@code -color-bg-default})}. All are served by the single
     * {@code adaptive} editor theme (see {@link EditorThemes}), whose syntax palette is expressed in the
     * theme's own {@code -color-*} variables. The GitHub colorblind/tritanopia variants are deliberately
     * omitted — their point is carefully distinguishable token colors that the adaptive palette can't
     * honor, and {@code github-light-default} duplicates Primer Light.
     */
    static final Map<String, String[]> BUNDLED = Map.ofEntries(
            Map.entry("Army Light", new String[] {"army-light", "false", "#e8eccc"}),
            Map.entry("Army Dark", new String[] {"army-dark", "true", "#1c2214"}),
            Map.entry("Autumn", new String[] {"autumn", "true", "#304c64"}),
            Map.entry("Blacky", new String[] {"blacky", "true", "#000000"}),
            Map.entry("Blue Light", new String[] {"blue-light", "false", "#ffffff"}),
            Map.entry("Blue Dark", new String[] {"blue-dark", "true", "#142342"}),
            Map.entry("Browny", new String[] {"browny", "true", "#332524"}),
            Map.entry("Fall Light", new String[] {"fall-light", "false", "#fdf8f0"}),
            Map.entry("Fall Dark", new String[] {"fall-dark", "true", "#1e0c06"}),
            Map.entry("Navy Light", new String[] {"navy-light", "false", "#ffffff"}),
            Map.entry("Navy Dark", new String[] {"navy-dark", "true", "#1a2744"}),
            Map.entry("News", new String[] {"news", "true", "#0f172a"}),
            Map.entry("Spring Light", new String[] {"spring-light", "false", "#ffffff"}),
            Map.entry("Spring Dark", new String[] {"spring-dark", "true", "#0c1a10"}),
            Map.entry("Summer Light", new String[] {"summer-light", "false", "#ffffff"}),
            Map.entry("Summer Dark", new String[] {"summer-dark", "true", "#081430"}),
            Map.entry("Winter Light", new String[] {"winter-light", "false", "#ffffff"}),
            Map.entry("Winter Dark", new String[] {"winter-dark", "true", "#080c18"}),
            Map.entry("Yacht", new String[] {"yacht", "false", "#f2f0ef"}));

    public static final List<String> NAMES = List.of(
            "Primer Light",
            "Primer Dark",
            "Nord Light",
            "Nord Dark",
            "Cupertino Light",
            "Cupertino Dark",
            "Dracula",
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

    private Themes() {}

    /** Discovers user-defined themes under the config dir ({@code themes/} + {@code editor-themes/}). */
    public static void loadUserThemes(java.nio.file.Path configDir) {
        UserThemes.load(configDir);
    }

    /** All selectable app-theme names: the built-ins plus any user themes in {@code <configDir>/themes/}. */
    public static List<String> names() {
        var user = UserThemes.controlThemes().keySet();
        if (user.isEmpty()) {
            return NAMES;
        }
        List<String> out = new java.util.ArrayList<>(NAMES);
        out.addAll(user);
        return out;
    }

    /** Theme instance for a display name, falling back to Primer Light for unknowns. */
    public static Theme themeFor(String name) {
        String key = name == null ? "" : name;
        String[] bundled = BUNDLED.get(key);
        if (bundled != null) {
            return new BundledTheme(
                    key, "/com/editora/styles/atlantafx-themes/" + bundled[0] + ".css", "true".equals(bundled[1]));
        }
        UserThemes.Entry user = UserThemes.controlThemes().get(key);
        if (user != null) {
            return new BundledTheme(key, user.stylesheetUrl(), user.dark()); // stylesheetUrl is a file: URL
        }
        return switch (key) {
            case "Primer Dark" -> new PrimerDark();
            case "Nord Light" -> new NordLight();
            case "Nord Dark" -> new NordDark();
            case "Cupertino Light" -> new CupertinoLight();
            case "Cupertino Dark" -> new CupertinoDark();
            case "Dracula" -> new Dracula();
            default -> new PrimerLight();
        };
    }

    /** The Primer Light user-agent stylesheet URL — used to force a light render for preview → PDF/print
     *  snapshots (so a dark-theme user still gets a light, ink-friendly export). */
    public static String lightUserAgentStylesheet() {
        return new PrimerLight().getUserAgentStylesheet();
    }

    /** Normalized display name (returns {@link #DEFAULT} for unrecognized input, incl. a since-removed user theme). */
    public static String normalize(String name) {
        return name != null
                        && (NAMES.contains(name) || UserThemes.controlThemes().containsKey(name))
                ? name
                : DEFAULT;
    }

    public static String stylesheetFor(String name) {
        Theme theme = themeFor(name);
        String sheet = theme.getUserAgentStylesheet();
        if (sheet == null) {
            return null;
        }
        // AtlantaFX returns a classpath resource *path* (e.g. "/atlantafx/base/theme/primer-light.css"),
        // not a URL. Application.setUserAgentStylesheet can load that during startup, but when called at
        // runtime (switching the theme from a UI event) the context ClassLoader JavaFX would use to
        // resolve a bare path is null → NullPointerException, which aborts the switch and leaves the UI
        // half-restyled. Resolve it to a full URL ourselves so runtime theme switching works. (If a
        // future AtlantaFX returns a real URL already, pass it through unchanged.)
        if (hasUrlScheme(sheet)) {
            return sheet;
        }
        // Resolve against the theme instance's own class/module: built-in themes live in atlantafx-base,
        // the bundled community themes (BundledTheme) live in this module so their vendored CSS is found.
        java.net.URL url = theme.getClass().getResource(sheet);
        return url != null ? url.toExternalForm() : sheet;
    }

    private static boolean hasUrlScheme(String s) {
        return s.startsWith("jar:")
                || s.startsWith("file:")
                || s.startsWith("http:")
                || s.startsWith("https:")
                || s.startsWith("data:")
                || s.startsWith("jrt:");
    }

    /**
     * The theme's base window background ({@code -color-bg-default}). Used to pre-fill the scene so
     * the first frame paints in the theme color instead of JavaFX's default light gray (avoids a
     * light→dark flash on startup with a dark theme).
     */
    public static javafx.scene.paint.Color backgroundFor(String name) {
        String norm = normalize(name);
        String[] bundled = BUNDLED.get(norm);
        if (bundled != null) {
            return javafx.scene.paint.Color.web(bundled[2]);
        }
        UserThemes.Entry user = UserThemes.controlThemes().get(norm);
        if (user != null) {
            return user.background();
        }
        String hex =
                switch (normalize(name)) {
                    case "Primer Dark" -> "#1c2128";
                    case "Nord Light" -> "#eceff4";
                    case "Nord Dark" -> "#2e3440";
                    case "Cupertino Light" -> "#ffffff";
                    case "Cupertino Dark" -> "#1e1e1e";
                    case "Dracula" -> "#282a36";
                    default -> "#ffffff"; // Primer Light
                };
        return javafx.scene.paint.Color.web(hex);
    }

    /** An AtlantaFX {@link Theme} backed by a stylesheet resource bundled inside this module. */
    private record BundledTheme(String name, String path, boolean dark) implements Theme {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getUserAgentStylesheet() {
            return path;
        }

        @Override
        public String getUserAgentStylesheetBSS() {
            return null; // no precompiled binary variant is bundled; JavaFX compiles the CSS at load
        }

        @Override
        public boolean isDarkMode() {
            return dark;
        }
    }
}
