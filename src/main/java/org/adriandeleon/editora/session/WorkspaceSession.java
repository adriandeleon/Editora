package org.adriandeleon.editora.session;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public record WorkspaceSession(
        List<Path> openFiles,
        Optional<Path> selectedFile,
        Path workspaceRoot,
        boolean searchBarVisible,
        boolean toolDockVisible,
        boolean statusBarVisible,
        double toolDockDividerPosition,
        double toolDockWidth,
        String searchText,
        String replaceText,
        boolean searchCaseSensitive,
        boolean searchWholeWord,
        boolean searchRegex,
        String commandPaletteFilter,
        String findFileQuery,
        List<String> findFileHistory,
        double windowWidth,
        double windowHeight,
        double windowX,
        double windowY,
        boolean windowMaximized
) {
    public WorkspaceSession {
        openFiles = List.copyOf(openFiles);
        selectedFile = selectedFile == null ? Optional.empty() : selectedFile;
        searchText = searchText == null ? "" : searchText;
        replaceText = replaceText == null ? "" : replaceText;
        commandPaletteFilter = commandPaletteFilter == null ? "" : commandPaletteFilter;
        findFileQuery = findFileQuery == null ? "" : findFileQuery;
        findFileHistory = findFileHistory == null ? List.of() : List.copyOf(findFileHistory);
    }
}

