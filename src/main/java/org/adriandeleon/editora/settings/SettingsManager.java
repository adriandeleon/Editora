package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.theme.EditorTheme;

import java.util.prefs.Preferences;

public final class SettingsManager {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(SettingsManager.class);
    private static final String THEME_KEY = "theme";
    private static final String WRAP_TEXT_KEY = "wrapText";
    private static final String DIAGNOSTICS_KEY = "diagnosticsEnabled";
    private static final String SEARCH_BAR_VISIBLE_KEY = "searchBarVisible";
    private static final String PROJECT_EXPLORER_VISIBLE_KEY = "projectExplorerVisible";
    private static final String BREADCRUMB_BAR_VISIBLE_KEY = "breadcrumbBarVisible";
    private static final String COMMAND_PALETTE_SHORTCUT_KEY = "commandPaletteShortcut";
    private static final String EDITOR_FONT_FAMILY_KEY = "editorFontFamily";
    private static final String EDITOR_FONT_SIZE_KEY = "editorFontSize";

    private SettingsManager() {
    }

    public static EditorSettings load() {
        EditorTheme theme = loadTheme();
        boolean wrapText = PREFERENCES.getBoolean(WRAP_TEXT_KEY, false);
        boolean diagnosticsEnabled = PREFERENCES.getBoolean(DIAGNOSTICS_KEY, true);
        boolean searchBarVisible = PREFERENCES.getBoolean(SEARCH_BAR_VISIBLE_KEY, EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE);
        boolean projectExplorerVisible = PREFERENCES.getBoolean(PROJECT_EXPLORER_VISIBLE_KEY, EditorSettings.DEFAULT_PROJECT_EXPLORER_VISIBLE);
        boolean breadcrumbBarVisible = PREFERENCES.getBoolean(BREADCRUMB_BAR_VISIBLE_KEY, true);
        String commandPaletteShortcut = PREFERENCES.get(COMMAND_PALETTE_SHORTCUT_KEY, CommandPaletteShortcut.DEFAULT_VALUE);
        String editorFontFamily = PREFERENCES.get(EDITOR_FONT_FAMILY_KEY, EditorSettings.DEFAULT_EDITOR_FONT_FAMILY);
        int editorFontSize = PREFERENCES.getInt(EDITOR_FONT_SIZE_KEY, EditorSettings.DEFAULT_EDITOR_FONT_SIZE);
        return new EditorSettings(
                theme,
                wrapText,
                diagnosticsEnabled,
                searchBarVisible,
                projectExplorerVisible,
                breadcrumbBarVisible,
                commandPaletteShortcut,
                editorFontFamily,
                editorFontSize
        );
    }

    private static EditorTheme loadTheme() {
        String storedTheme = PREFERENCES.get(THEME_KEY, EditorTheme.defaultTheme().name());
        return EditorTheme.fromStoredValue(storedTheme);
    }

    public static void save(EditorSettings settings) {
        PREFERENCES.put(THEME_KEY, settings.theme().name());
        PREFERENCES.putBoolean(WRAP_TEXT_KEY, settings.wrapText());
        PREFERENCES.putBoolean(DIAGNOSTICS_KEY, settings.diagnosticsEnabled());
        PREFERENCES.putBoolean(SEARCH_BAR_VISIBLE_KEY, settings.searchBarVisible());
        PREFERENCES.putBoolean(PROJECT_EXPLORER_VISIBLE_KEY, settings.projectExplorerVisible());
        PREFERENCES.putBoolean(BREADCRUMB_BAR_VISIBLE_KEY, settings.breadcrumbBarVisible());
        PREFERENCES.put(COMMAND_PALETTE_SHORTCUT_KEY, settings.commandPaletteShortcut());
        PREFERENCES.put(EDITOR_FONT_FAMILY_KEY, settings.editorFontFamily());
        PREFERENCES.putInt(EDITOR_FONT_SIZE_KEY, settings.editorFontSize());
    }
}

