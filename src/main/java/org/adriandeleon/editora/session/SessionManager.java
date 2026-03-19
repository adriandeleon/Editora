package org.adriandeleon.editora.session;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.prefs.Preferences;

public final class SessionManager {
    private static final Preferences PREFERENCES = Preferences.userNodeForPackage(SessionManager.class);
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
    private static final String SEPARATOR = System.lineSeparator();

    private SessionManager() {
    }

    public static List<Path> loadRecentFiles() {
        return decodePaths(PREFERENCES.get(RECENT_FILES_KEY, ""));
    }

    public static void saveRecentFiles(List<Path> recentFiles) {
        PREFERENCES.put(RECENT_FILES_KEY, encodePaths(recentFiles));
    }

    public static WorkspaceSession loadWorkspaceSession(Path fallbackWorkspaceRoot) {
        Path workspaceRoot = decodePath(PREFERENCES.get(WORKSPACE_ROOT_KEY, "")).orElse(fallbackWorkspaceRoot);
        List<Path> openFiles = decodePaths(PREFERENCES.get(OPEN_FILES_KEY, ""));
        Optional<Path> selectedFile = decodePath(PREFERENCES.get(SELECTED_FILE_KEY, ""));
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
        List<String> findFileHistory = decodeStrings(PREFERENCES.get(FIND_FILE_HISTORY_KEY, ""));
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

    public static void saveWorkspaceSession(WorkspaceSession session) {
        PREFERENCES.put(OPEN_FILES_KEY, encodePaths(session.openFiles()));
        PREFERENCES.put(SELECTED_FILE_KEY, session.selectedFile().map(Path::toString).orElse(""));
        PREFERENCES.put(WORKSPACE_ROOT_KEY, session.workspaceRoot().toString());
        PREFERENCES.putBoolean(SEARCH_BAR_VISIBLE_KEY, session.searchBarVisible());
        PREFERENCES.putBoolean(PROJECT_EXPLORER_VISIBLE_KEY, session.projectExplorerVisible());
        PREFERENCES.putBoolean(STATUS_BAR_VISIBLE_KEY, session.statusBarVisible());
        PREFERENCES.putDouble(PROJECT_EXPLORER_DIVIDER_POSITION_KEY, session.projectExplorerDividerPosition());
        PREFERENCES.putDouble(PROJECT_EXPLORER_WIDTH_KEY, session.projectExplorerWidth());
        PREFERENCES.put(SEARCH_TEXT_KEY, session.searchText());
        PREFERENCES.put(REPLACE_TEXT_KEY, session.replaceText());
        PREFERENCES.putBoolean(SEARCH_CASE_SENSITIVE_KEY, session.searchCaseSensitive());
        PREFERENCES.putBoolean(SEARCH_WHOLE_WORD_KEY, session.searchWholeWord());
        PREFERENCES.putBoolean(SEARCH_REGEX_KEY, session.searchRegex());
        PREFERENCES.put(COMMAND_PALETTE_FILTER_KEY, session.commandPaletteFilter());
        PREFERENCES.put(FIND_FILE_QUERY_KEY, session.findFileQuery());
        PREFERENCES.put(FIND_FILE_HISTORY_KEY, encodeStrings(session.findFileHistory()));
        PREFERENCES.putDouble(WINDOW_WIDTH_KEY, session.windowWidth());
        PREFERENCES.putDouble(WINDOW_HEIGHT_KEY, session.windowHeight());
        PREFERENCES.putDouble(WINDOW_X_KEY, session.windowX());
        PREFERENCES.putDouble(WINDOW_Y_KEY, session.windowY());
        PREFERENCES.putBoolean(WINDOW_MAXIMIZED_KEY, session.windowMaximized());
    }

    private static String encodePaths(List<Path> paths) {
        return paths.stream()
                .map(Path::toString)
                .distinct()
                .reduce((left, right) -> left + SEPARATOR + right)
                .orElse("");
    }

    private static List<Path> decodePaths(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }

        return Arrays.stream(encoded.split("\\R"))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .toList();
    }

    private static Optional<Path> decodePath(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return Optional.empty();
        }

        return Optional.of(Path.of(encoded).toAbsolutePath().normalize());
    }

    private static String encodeStrings(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }

        return values.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isBlank())
                .distinct()
                .reduce((left, right) -> left + SEPARATOR + right)
                .orElse("");
    }

    private static List<String> decodeStrings(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return List.of();
        }

        return Arrays.stream(encoded.split("\\R"))
                .map(String::trim)
                .filter(entry -> !entry.isBlank())
                .distinct()
                .toList();
    }
}

