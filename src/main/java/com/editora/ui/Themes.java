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
            "Primer Light",
            "Primer Dark",
            "Nord Light",
            "Nord Dark",
            "Cupertino Light",
            "Cupertino Dark",
            "Dracula");

    private Themes() {
    }

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
        return themeFor(name).getUserAgentStylesheet();
    }
}
