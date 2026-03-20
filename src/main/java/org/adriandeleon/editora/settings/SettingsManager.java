package org.adriandeleon.editora.settings;

import org.adriandeleon.editora.persistence.EditoraPersistence;
import org.adriandeleon.editora.theme.EditorTheme;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SettingsManager {
    private static final String SCHEMA_VERSION_KEY = "schemaVersion";
    private static final String THEME_KEY = "theme";
    private static final String WRAP_TEXT_KEY = "wrapText";
    private static final String DIAGNOSTICS_KEY = "diagnosticsEnabled";
    private static final String MINI_MAP_VISIBLE_KEY = "miniMapVisible";
    private static final String SEARCH_BAR_VISIBLE_KEY = "searchBarVisible";
    private static final String TOOL_DOCK_VISIBLE_KEY = "toolDockVisible";
    private static final String BOOKMARK_WINDOW_VISIBLE_KEY = "bookmarkWindowVisible";
    private static final String BREADCRUMB_BAR_VISIBLE_KEY = "breadcrumbBarVisible";
    private static final String TOOL_DOCK_SIDE_KEY = "toolDockSide";
    private static final String COMMAND_PALETTE_SHORTCUT_KEY = "commandPaletteShortcut";
    private static final String EDITOR_FONT_FAMILY_KEY = "editorFontFamily";
    private static final String EDITOR_FONT_SIZE_KEY = "editorFontSize";
    private static final String READ_ONLY_OPEN_ENABLED_KEY = "readOnlyOpenEnabled";
    private static final String READ_ONLY_OPEN_PATTERNS_KEY = "readOnlyOpenPatterns";

    private SettingsManager() {
    }

    public static EditorSettings load() {
        Optional<Map<String, Object>> storedSettings = EditoraPersistence.readJsonObject(EditoraPersistence.settingsFile());
        return storedSettings.map(SettingsManager::settingsFromJson)
                .orElseGet(SettingsManager::defaultSettings);
    }

    public static Path persistenceFile() {
        return EditoraPersistence.settingsFile();
    }

    private static EditorSettings settingsFromJson(Map<String, Object> values) {
        EditorTheme theme = EditorTheme.fromStoredValue(readString(values, THEME_KEY, EditorTheme.defaultTheme().name()));
        boolean wrapText = readBoolean(values, WRAP_TEXT_KEY, false);
        boolean diagnosticsEnabled = readBoolean(values, DIAGNOSTICS_KEY, true);
        boolean miniMapVisible = readBoolean(values, MINI_MAP_VISIBLE_KEY, EditorSettings.DEFAULT_MINI_MAP_VISIBLE);
        boolean searchBarVisible = readBoolean(values, SEARCH_BAR_VISIBLE_KEY, EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE);
        boolean toolDockVisible = readBoolean(values, TOOL_DOCK_VISIBLE_KEY, EditorSettings.DEFAULT_TOOL_DOCK_VISIBLE);
        boolean bookmarkWindowVisible = readBoolean(values, BOOKMARK_WINDOW_VISIBLE_KEY, EditorSettings.DEFAULT_BOOKMARK_WINDOW_VISIBLE);
        boolean breadcrumbBarVisible = readBoolean(values, BREADCRUMB_BAR_VISIBLE_KEY, true);
        ToolWindowSide toolDockSide = ToolWindowSide.fromStoredValue(readString(values, TOOL_DOCK_SIDE_KEY, EditorSettings.DEFAULT_TOOL_DOCK_SIDE.storedValue()));
        String commandPaletteShortcut = readString(values, COMMAND_PALETTE_SHORTCUT_KEY, CommandPaletteShortcut.DEFAULT_VALUE);
        String editorFontFamily = readString(values, EDITOR_FONT_FAMILY_KEY, EditorSettings.DEFAULT_EDITOR_FONT_FAMILY);
        int editorFontSize = readInt(values, EDITOR_FONT_SIZE_KEY, EditorSettings.DEFAULT_EDITOR_FONT_SIZE);
        boolean readOnlyOpenEnabled = readBoolean(values, READ_ONLY_OPEN_ENABLED_KEY, EditorSettings.DEFAULT_READ_ONLY_OPEN_ENABLED);
        List<String> readOnlyOpenPatterns = readStringList(
                values,
                READ_ONLY_OPEN_PATTERNS_KEY,
                EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS
        );
        return new EditorSettings(
                theme,
                wrapText,
                diagnosticsEnabled,
                miniMapVisible,
                searchBarVisible,
                toolDockVisible,
                bookmarkWindowVisible,
                breadcrumbBarVisible,
                toolDockSide,
                commandPaletteShortcut,
                editorFontFamily,
                editorFontSize,
                readOnlyOpenEnabled,
                readOnlyOpenPatterns
        );
    }

    private static EditorSettings defaultSettings() {
        return new EditorSettings(
                EditorTheme.defaultTheme(),
                false,
                true,
                EditorSettings.DEFAULT_MINI_MAP_VISIBLE,
                EditorSettings.DEFAULT_SEARCH_BAR_VISIBLE,
                EditorSettings.DEFAULT_TOOL_DOCK_VISIBLE,
                EditorSettings.DEFAULT_BOOKMARK_WINDOW_VISIBLE,
                true,
                EditorSettings.DEFAULT_TOOL_DOCK_SIDE,
                CommandPaletteShortcut.DEFAULT_VALUE,
                EditorSettings.DEFAULT_EDITOR_FONT_FAMILY,
                EditorSettings.DEFAULT_EDITOR_FONT_SIZE,
                EditorSettings.DEFAULT_READ_ONLY_OPEN_ENABLED,
                EditorSettings.DEFAULT_READ_ONLY_OPEN_PATTERNS
        );
    }

    public static void save(EditorSettings settings) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SCHEMA_VERSION_KEY, EditoraPersistence.SCHEMA_VERSION);
        values.put(THEME_KEY, settings.theme().name());
        values.put(WRAP_TEXT_KEY, settings.wrapText());
        values.put(DIAGNOSTICS_KEY, settings.diagnosticsEnabled());
        values.put(MINI_MAP_VISIBLE_KEY, settings.miniMapVisible());
        values.put(SEARCH_BAR_VISIBLE_KEY, settings.searchBarVisible());
        values.put(TOOL_DOCK_VISIBLE_KEY, settings.toolDockVisible());
        values.put(BOOKMARK_WINDOW_VISIBLE_KEY, settings.bookmarkWindowVisible());
        values.put(BREADCRUMB_BAR_VISIBLE_KEY, settings.breadcrumbBarVisible());
        values.put(TOOL_DOCK_SIDE_KEY, settings.toolDockSide().storedValue());
        values.put(COMMAND_PALETTE_SHORTCUT_KEY, settings.commandPaletteShortcut());
        values.put(EDITOR_FONT_FAMILY_KEY, settings.editorFontFamily());
        values.put(EDITOR_FONT_SIZE_KEY, settings.editorFontSize());
        values.put(READ_ONLY_OPEN_ENABLED_KEY, settings.readOnlyOpenEnabled());
        values.put(READ_ONLY_OPEN_PATTERNS_KEY, settings.readOnlyOpenPatterns());
        EditoraPersistence.writeJsonObject(EditoraPersistence.settingsFile(), values);
    }


    private static String readString(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static boolean readBoolean(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        if (value instanceof String stringValue) {
            return Boolean.parseBoolean(stringValue);
        }
        return fallback;
    }

    private static int readInt(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Integer.parseInt(stringValue);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> readStringList(Map<String, Object> values, String key, List<String> fallback) {
        if (!values.containsKey(key)) {
            return fallback == null ? List.of() : List.copyOf(fallback);
        }

        Object value = values.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item).strip())
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        if (value instanceof String stringValue) {
            String normalized = stringValue.strip();
            return normalized.isBlank() ? List.of() : List.of(normalized);
        }
        return List.of();
    }
}

