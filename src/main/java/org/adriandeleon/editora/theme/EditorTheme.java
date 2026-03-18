package org.adriandeleon.editora.theme;

import atlantafx.base.theme.CupertinoDark;
import atlantafx.base.theme.CupertinoLight;
import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordDark;
import atlantafx.base.theme.NordLight;
import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;

import java.util.List;
import java.util.Locale;

public enum EditorTheme {
    PRIMER_LIGHT("Primer Light", false, "theme-primer", new PrimerLight().getUserAgentStylesheet()),
    PRIMER_DARK("Primer Dark", true, "theme-primer", new PrimerDark().getUserAgentStylesheet()),
    NORD_LIGHT("Nord Light", false, "theme-nord", new NordLight().getUserAgentStylesheet()),
    NORD_DARK("Nord Dark", true, "theme-nord", new NordDark().getUserAgentStylesheet()),
    CUPERTINO_LIGHT("Cupertino Light", false, "theme-cupertino", new CupertinoLight().getUserAgentStylesheet()),
    CUPERTINO_DARK("Cupertino Dark", true, "theme-cupertino", new CupertinoDark().getUserAgentStylesheet()),
    DRACULA("Dracula", true, "theme-dracula", new Dracula().getUserAgentStylesheet());

    private static final EditorTheme DEFAULT_THEME = PRIMER_DARK;

    private final String displayName;
    private final boolean dark;
    private final String familyStyleClass;
    private final String userAgentStylesheet;

    EditorTheme(String displayName, boolean dark, String familyStyleClass, String userAgentStylesheet) {
        this.displayName = displayName;
        this.dark = dark;
        this.familyStyleClass = familyStyleClass;
        this.userAgentStylesheet = userAgentStylesheet;
    }

    public static EditorTheme defaultTheme() {
        return DEFAULT_THEME;
    }

    public static EditorTheme fromStoredValue(String storedValue) {
        if (storedValue == null || storedValue.isBlank()) {
            return DEFAULT_THEME;
        }

        return switch (storedValue.strip().toUpperCase(Locale.ROOT)) {
            case "LIGHT" -> PRIMER_LIGHT;
            case "DARK" -> PRIMER_DARK;
            default -> {
                try {
                    yield EditorTheme.valueOf(storedValue.strip().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException exception) {
                    yield DEFAULT_THEME;
                }
            }
        };
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFamilyDisplayName() {
        return switch (this) {
            case PRIMER_LIGHT, PRIMER_DARK -> "Primer";
            case NORD_LIGHT, NORD_DARK -> "Nord";
            case CUPERTINO_LIGHT, CUPERTINO_DARK -> "Cupertino";
            case DRACULA -> "Dracula";
        };
    }

    public String getVariantDisplayName() {
        return switch (this) {
            case PRIMER_LIGHT, NORD_LIGHT, CUPERTINO_LIGHT -> "Light";
            case PRIMER_DARK, NORD_DARK, CUPERTINO_DARK -> "Dark";
            case DRACULA -> "Theme";
        };
    }

    public String getSettingsDisplayName() {
        return this == DRACULA ? displayName : getFamilyDisplayName() + " · " + getVariantDisplayName();
    }

    public String getCommandName() {
        return "Set Theme: " + displayName;
    }

    public boolean isDark() {
        return dark;
    }

    public String getUserAgentStylesheet() {
        return userAgentStylesheet;
    }

    public List<String> getRootStyleClasses() {
        return List.of(dark ? "theme-dark" : "theme-light", familyStyleClass);
    }

    public EditorTheme next() {
        EditorTheme[] themes = values();
        return themes[(ordinal() + 1) % themes.length];
    }

    @Override
    public String toString() {
        return displayName;
    }
}

