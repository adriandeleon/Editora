package com.editora.ui;

import java.util.List;

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

    public static final List<String> NAMES = List.of(
            "Primer Light", "Primer Dark", "Nord Light", "Nord Dark", "Cupertino Light", "Cupertino Dark", "Dracula");

    private Themes() {}

    /** Theme instance for a display name, falling back to Primer Light for unknowns. */
    public static Theme themeFor(String name) {
        return switch (name == null ? "" : name) {
            case "Primer Dark" -> new PrimerDark();
            case "Nord Light" -> new NordLight();
            case "Nord Dark" -> new NordDark();
            case "Cupertino Light" -> new CupertinoLight();
            case "Cupertino Dark" -> new CupertinoDark();
            case "Dracula" -> new Dracula();
            default -> new PrimerLight();
        };
    }

    /** Normalized display name (returns {@link #DEFAULT} for unrecognized input). */
    public static String normalize(String name) {
        return NAMES.contains(name) ? name : DEFAULT;
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
}
