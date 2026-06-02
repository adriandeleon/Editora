package com.editora.snippet;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Resolves the common TextMate/VS Code snippet variables from the editing context (file name,
 * selection, clipboard, caret line, clock). Unknown names resolve to {@code null} so the parser falls
 * back to a variable's {@code :default} (or empty). Pure given a fixed clock, so it is unit-tested.
 */
public final class VariableResolver implements SnippetParser.Variables {

    private final String fileName;     // bare file name, or "" when untitled
    private final String directory;    // absolute parent directory, or ""
    private final String filePath;     // absolute file path, or ""
    private final String selectedText; // current selection, or ""
    private final String clipboard;    // clipboard text, or ""
    private final int lineIndex;       // 0-based caret line
    private final String currentLine;  // text of the caret line
    private final LocalDateTime now;

    public VariableResolver(String fileName, String directory, String filePath, String selectedText,
            String clipboard, int lineIndex, String currentLine) {
        this(fileName, directory, filePath, selectedText, clipboard, lineIndex, currentLine,
                LocalDateTime.now());
    }

    VariableResolver(String fileName, String directory, String filePath, String selectedText,
            String clipboard, int lineIndex, String currentLine, LocalDateTime now) {
        this.fileName = fileName == null ? "" : fileName;
        this.directory = directory == null ? "" : directory;
        this.filePath = filePath == null ? "" : filePath;
        this.selectedText = selectedText == null ? "" : selectedText;
        this.clipboard = clipboard == null ? "" : clipboard;
        this.lineIndex = lineIndex;
        this.currentLine = currentLine == null ? "" : currentLine;
        this.now = now;
    }

    @Override
    public String resolve(String name) {
        return switch (name) {
            case "TM_FILENAME" -> fileName;
            case "TM_FILENAME_BASE" -> baseName(fileName);
            case "TM_DIRECTORY" -> directory;
            case "TM_FILEPATH" -> filePath;
            case "TM_SELECTED_TEXT", "SELECTION" -> selectedText;
            case "CLIPBOARD" -> clipboard;
            case "TM_LINE_INDEX" -> String.valueOf(lineIndex);
            case "TM_LINE_NUMBER" -> String.valueOf(lineIndex + 1);
            case "TM_CURRENT_LINE" -> currentLine;
            case "CURRENT_YEAR" -> fmt("yyyy");
            case "CURRENT_YEAR_SHORT" -> fmt("yy");
            case "CURRENT_MONTH" -> fmt("MM");
            case "CURRENT_MONTH_NAME" -> fmt("MMMM");
            case "CURRENT_DATE" -> fmt("dd");
            case "CURRENT_HOUR" -> fmt("HH");
            case "CURRENT_MINUTE" -> fmt("mm");
            case "CURRENT_SECOND" -> fmt("ss");
            default -> null;
        };
    }

    private String fmt(String pattern) {
        return now.format(DateTimeFormatter.ofPattern(pattern));
    }

    private static String baseName(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
