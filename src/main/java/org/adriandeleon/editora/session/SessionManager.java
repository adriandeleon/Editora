package org.adriandeleon.editora.session;

import org.adriandeleon.editora.persistence.EditoraPersistence;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

public final class SessionManager {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(SessionManager.class);
    private static final String SCHEMA_VERSION_KEY = "schemaVersion";
    private static final String RECENT_FILES_KEY = "recentFiles";
    private static final String OPEN_FILES_KEY = "openFiles";
    private static final String SELECTED_FILE_KEY = "selectedFile";
    private static final String WORKSPACE_ROOT_KEY = "workspaceRoot";
    private static final String SEARCH_BAR_VISIBLE_KEY = "searchBarVisible";
    private static final String PROJECT_EXPLORER_VISIBLE_KEY = "projectExplorerVisible";
    private static final String STATUS_BAR_VISIBLE_KEY = "statusBarVisible";
    private static final String PROJECT_EXPLORER_DIVIDER_POSITION_KEY = "projectExplorerDividerPosition";
    private static final String PROJECT_EXPLORER_WIDTH_KEY = "projectExplorerWidth";
    private static final String SEARCH_TEXT_KEY = "searchText";
    private static final String REPLACE_TEXT_KEY = "replaceText";
    private static final String SEARCH_CASE_SENSITIVE_KEY = "searchCaseSensitive";
    private static final String SEARCH_WHOLE_WORD_KEY = "searchWholeWord";
    private static final String SEARCH_REGEX_KEY = "searchRegex";
    private static final String COMMAND_PALETTE_FILTER_KEY = "commandPaletteFilter";
    private static final String FIND_FILE_QUERY_KEY = "findFileQuery";
    private static final String FIND_FILE_HISTORY_KEY = "findFileHistory";
    private static final String WINDOW_WIDTH_KEY = "windowWidth";
    private static final String WINDOW_HEIGHT_KEY = "windowHeight";
    private static final String WINDOW_X_KEY = "windowX";
    private static final String WINDOW_Y_KEY = "windowY";
    private static final String WINDOW_MAXIMIZED_KEY = "windowMaximized";
    private SessionManager() {
    }

    public static List<Path> loadRecentFiles() {
        Optional<Map<String, Object>> storedRecentFiles = EditoraPersistence.readJsonObject(EditoraPersistence.recentFilesFile());
        if (storedRecentFiles.isPresent()) {
            return readPaths(storedRecentFiles.get(), RECENT_FILES_KEY);
        }

        List<Path> legacyRecentFiles = decodeLegacyPaths(PREFERENCES.get(RECENT_FILES_KEY, ""));
        if (!legacyRecentFiles.isEmpty()) {
            saveRecentFiles(legacyRecentFiles);
        }
        return legacyRecentFiles;
    }

    public static void saveRecentFiles(List<Path> recentFiles) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SCHEMA_VERSION_KEY, EditoraPersistence.SCHEMA_VERSION);
        values.put(RECENT_FILES_KEY, encodePaths(recentFiles));
        EditoraPersistence.writeJsonObject(EditoraPersistence.recentFilesFile(), values);
    }

    public static WorkspaceSession loadWorkspaceSession(Path fallbackWorkspaceRoot) {
        Optional<Map<String, Object>> storedSession = EditoraPersistence.readJsonObject(EditoraPersistence.workspaceSessionFile());
        if (storedSession.isPresent()) {
            return workspaceSessionFromJson(storedSession.get(), fallbackWorkspaceRoot);
        }

        WorkspaceSession legacySession = loadLegacyWorkspaceSession(fallbackWorkspaceRoot);
        if (hasLegacyPreferences()) {
            saveWorkspaceSession(legacySession);
        }
        return legacySession;
    }

    public static void saveWorkspaceSession(WorkspaceSession session) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SCHEMA_VERSION_KEY, EditoraPersistence.SCHEMA_VERSION);
        values.put(OPEN_FILES_KEY, encodePaths(session.openFiles()));
        values.put(SELECTED_FILE_KEY, session.selectedFile().map(Path::toString).orElse(""));
        values.put(WORKSPACE_ROOT_KEY, session.workspaceRoot().toString());
        values.put(SEARCH_BAR_VISIBLE_KEY, session.searchBarVisible());
        values.put(PROJECT_EXPLORER_VISIBLE_KEY, session.projectExplorerVisible());
        values.put(STATUS_BAR_VISIBLE_KEY, session.statusBarVisible());
        values.put(PROJECT_EXPLORER_DIVIDER_POSITION_KEY, session.projectExplorerDividerPosition());
        values.put(PROJECT_EXPLORER_WIDTH_KEY, session.projectExplorerWidth());
        values.put(SEARCH_TEXT_KEY, session.searchText());
        values.put(REPLACE_TEXT_KEY, session.replaceText());
        values.put(SEARCH_CASE_SENSITIVE_KEY, session.searchCaseSensitive());
        values.put(SEARCH_WHOLE_WORD_KEY, session.searchWholeWord());
        values.put(SEARCH_REGEX_KEY, session.searchRegex());
        values.put(COMMAND_PALETTE_FILTER_KEY, session.commandPaletteFilter());
        values.put(FIND_FILE_QUERY_KEY, session.findFileQuery());
        values.put(FIND_FILE_HISTORY_KEY, encodeStrings(session.findFileHistory()));
        values.put(WINDOW_WIDTH_KEY, session.windowWidth());
        values.put(WINDOW_HEIGHT_KEY, session.windowHeight());
        values.put(WINDOW_X_KEY, session.windowX());
        values.put(WINDOW_Y_KEY, session.windowY());
        values.put(WINDOW_MAXIMIZED_KEY, session.windowMaximized());
        EditoraPersistence.writeJsonObject(EditoraPersistence.workspaceSessionFile(), values);
    }

    public static Path workspaceSessionFile() {
        return EditoraPersistence.workspaceSessionFile();
    }

    public static Path recentFilesFile() {
        return EditoraPersistence.recentFilesFile();
    }

    private static WorkspaceSession workspaceSessionFromJson(Map<String, Object> values, Path fallbackWorkspaceRoot) {
        Path workspaceRoot = readPath(values, WORKSPACE_ROOT_KEY).orElse(fallbackWorkspaceRoot);
        return new WorkspaceSession(
                readPaths(values, OPEN_FILES_KEY),
                readPath(values, SELECTED_FILE_KEY),
                workspaceRoot,
                readBoolean(values, SEARCH_BAR_VISIBLE_KEY, false),
                readBoolean(values, PROJECT_EXPLORER_VISIBLE_KEY, false),
                readBoolean(values, STATUS_BAR_VISIBLE_KEY, true),
                readDouble(values, PROJECT_EXPLORER_DIVIDER_POSITION_KEY, 0.22d),
                readDouble(values, PROJECT_EXPLORER_WIDTH_KEY, 300d),
                readString(values, SEARCH_TEXT_KEY, ""),
                readString(values, REPLACE_TEXT_KEY, ""),
                readBoolean(values, SEARCH_CASE_SENSITIVE_KEY, false),
                readBoolean(values, SEARCH_WHOLE_WORD_KEY, false),
                readBoolean(values, SEARCH_REGEX_KEY, false),
                readString(values, COMMAND_PALETTE_FILTER_KEY, ""),
                readString(values, FIND_FILE_QUERY_KEY, ""),
                readStrings(values, FIND_FILE_HISTORY_KEY),
                readDouble(values, WINDOW_WIDTH_KEY, 1440d),
                readDouble(values, WINDOW_HEIGHT_KEY, 920d),
                readDouble(values, WINDOW_X_KEY, Double.NaN),
                readDouble(values, WINDOW_Y_KEY, Double.NaN),
                readBoolean(values, WINDOW_MAXIMIZED_KEY, false)
        );
    }

    private static WorkspaceSession loadLegacyWorkspaceSession(Path fallbackWorkspaceRoot) {
        Path workspaceRoot = decodeLegacyPath(PREFERENCES.get(WORKSPACE_ROOT_KEY, "")).orElse(fallbackWorkspaceRoot);
        List<Path> openFiles = decodeLegacyPaths(PREFERENCES.get(OPEN_FILES_KEY, ""));
        Optional<Path> selectedFile = decodeLegacyPath(PREFERENCES.get(SELECTED_FILE_KEY, ""));
        boolean searchBarVisible = PREFERENCES.getBoolean(SEARCH_BAR_VISIBLE_KEY, false);
        boolean projectExplorerVisible = PREFERENCES.getBoolean(PROJECT_EXPLORER_VISIBLE_KEY, false);
        boolean statusBarVisible = PREFERENCES.getBoolean(STATUS_BAR_VISIBLE_KEY, true);
        double projectExplorerDividerPosition = PREFERENCES.getDouble(PROJECT_EXPLORER_DIVIDER_POSITION_KEY, 0.22d);
        double projectExplorerWidth = PREFERENCES.getDouble(PROJECT_EXPLORER_WIDTH_KEY, 300d);
        String searchText = PREFERENCES.get(SEARCH_TEXT_KEY, "");
        String replaceText = PREFERENCES.get(REPLACE_TEXT_KEY, "");
        boolean searchCaseSensitive = PREFERENCES.getBoolean(SEARCH_CASE_SENSITIVE_KEY, false);
        boolean searchWholeWord = PREFERENCES.getBoolean(SEARCH_WHOLE_WORD_KEY, false);
        boolean searchRegex = PREFERENCES.getBoolean(SEARCH_REGEX_KEY, false);
        String commandPaletteFilter = PREFERENCES.get(COMMAND_PALETTE_FILTER_KEY, "");
        String findFileQuery = PREFERENCES.get(FIND_FILE_QUERY_KEY, "");
        List<String> findFileHistory = decodeLegacyStrings(PREFERENCES.get(FIND_FILE_HISTORY_KEY, ""));
        double windowWidth = PREFERENCES.getDouble(WINDOW_WIDTH_KEY, 1440d);
        double windowHeight = PREFERENCES.getDouble(WINDOW_HEIGHT_KEY, 920d);
        double windowX = PREFERENCES.getDouble(WINDOW_X_KEY, Double.NaN);
        double windowY = PREFERENCES.getDouble(WINDOW_Y_KEY, Double.NaN);
        boolean windowMaximized = PREFERENCES.getBoolean(WINDOW_MAXIMIZED_KEY, false);
        return new WorkspaceSession(
                openFiles,
                selectedFile,
                workspaceRoot,
                searchBarVisible,
                projectExplorerVisible,
                statusBarVisible,
                projectExplorerDividerPosition,
                projectExplorerWidth,
                searchText,
                replaceText,
                searchCaseSensitive,
                searchWholeWord,
                searchRegex,
                commandPaletteFilter,
                findFileQuery,
                findFileHistory,
                windowWidth,
                windowHeight,
                windowX,
                windowY,
                windowMaximized
        );
    }

    private static List<String> encodePaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }

        return paths.stream()
                .filter(path -> path != null)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .map(Path::toString)
                .distinct()
                .toList();
    }

    private static List<String> encodeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        return values.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static List<Path> decodeLegacyPaths(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        return List.of(encoded.split("\\R")).stream()
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .distinct()
                .toList();
    }

    private static Optional<Path> decodeLegacyPath(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(Path.of(encoded).toAbsolutePath().normalize());
    }

    private static List<String> decodeLegacyStrings(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }
        return List.of(encoded.split("\\R")).stream()
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .distinct()
                .toList();
    }

    private static List<Path> readPaths(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        for (Object entry : list) {
            if (entry == null) {
                continue;
            }
            String pathValue = String.valueOf(entry).trim();
            if (pathValue.isBlank()) {
                continue;
            }
            paths.add(Path.of(pathValue).toAbsolutePath().normalize());
        }
        return paths.stream().distinct().toList();
    }

    private static Optional<Path> readPath(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null) {
            return Optional.empty();
        }
        String pathValue = String.valueOf(value).trim();
        return pathValue.isBlank() ? Optional.empty() : Optional.of(Path.of(pathValue).toAbsolutePath().normalize());
    }

    private static List<String> readStrings(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(entry -> entry == null ? "" : String.valueOf(entry).trim())
                .filter(entry -> !entry.isBlank())
                .distinct()
                .toList();
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

    private static double readDouble(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String stringValue) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean hasLegacyPreferences() {
        try {
            return PREFERENCES.keys().length > 0;
        } catch (BackingStoreException exception) {
            return false;
        }
    }
}

