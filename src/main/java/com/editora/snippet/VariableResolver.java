package com.editora.snippet;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Resolves the TextMate/VS Code snippet variables from the editing context (file name, selection,
 * clipboard, caret line, clock, and — when supplied through the {@code with…} methods — the workspace, the
 * language's comment tokens and the word at the caret). Unknown names resolve to {@code null}; the parser
 * then uses the variable's {@code :default}, or keeps the text as written. Pure given a fixed clock, so it
 * is unit-tested.
 */
public final class VariableResolver implements SnippetParser.Variables {

    /** Every name {@link #resolve} answers — for callers that must tell a variable from ordinary text. */
    public static final Set<String> NAMES = Set.of(
            "TM_FILENAME",
            "TM_FILENAME_BASE",
            "TM_DIRECTORY",
            "TM_FILEPATH",
            "RELATIVE_FILEPATH",
            "TM_SELECTED_TEXT",
            "SELECTION",
            "CLIPBOARD",
            "TM_LINE_INDEX",
            "TM_LINE_NUMBER",
            "TM_CURRENT_LINE",
            "TM_CURRENT_WORD",
            "WORKSPACE_NAME",
            "WORKSPACE_FOLDER",
            "CURSOR_INDEX",
            "CURSOR_NUMBER",
            "CURRENT_YEAR",
            "CURRENT_YEAR_SHORT",
            "CURRENT_MONTH",
            "CURRENT_MONTH_NAME",
            "CURRENT_MONTH_NAME_SHORT",
            "CURRENT_DATE",
            "CURRENT_DAY_NAME",
            "CURRENT_DAY_NAME_SHORT",
            "CURRENT_HOUR",
            "CURRENT_MINUTE",
            "CURRENT_SECOND",
            "CURRENT_SECONDS_UNIX",
            "CURRENT_TIMEZONE_OFFSET",
            "RANDOM",
            "RANDOM_HEX",
            "UUID",
            "LINE_COMMENT",
            "BLOCK_COMMENT_START",
            "BLOCK_COMMENT_END");

    private final String fileName; // bare file name, or "" when untitled
    private final String directory; // absolute parent directory, or ""
    private final String filePath; // absolute file path, or ""
    private final String selectedText; // current selection, or ""
    private final String clipboard; // clipboard text, or ""
    private final int lineIndex; // 0-based caret line
    private final String currentLine; // text of the caret line
    private final LocalDateTime now;
    private ZoneId zone = ZoneId.systemDefault();
    private String workspaceFolder = ""; // absolute project root, or ""
    private String currentWord = "";
    private String lineComment = "";
    private String blockCommentStart = "";
    private String blockCommentEnd = "";

    public VariableResolver(
            String fileName,
            String directory,
            String filePath,
            String selectedText,
            String clipboard,
            int lineIndex,
            String currentLine) {
        this(fileName, directory, filePath, selectedText, clipboard, lineIndex, currentLine, LocalDateTime.now());
    }

    VariableResolver(
            String fileName,
            String directory,
            String filePath,
            String selectedText,
            String clipboard,
            int lineIndex,
            String currentLine,
            LocalDateTime now) {
        this.fileName = fileName == null ? "" : fileName;
        this.directory = directory == null ? "" : directory;
        this.filePath = filePath == null ? "" : filePath;
        this.selectedText = selectedText == null ? "" : selectedText;
        this.clipboard = clipboard == null ? "" : clipboard;
        this.lineIndex = lineIndex;
        this.currentLine = currentLine == null ? "" : currentLine;
        this.now = now;
    }

    /** The project root the file belongs to ({@code WORKSPACE_*}, {@code RELATIVE_FILEPATH}); null = none. */
    public VariableResolver withWorkspace(Path root) {
        this.workspaceFolder =
                root == null ? "" : root.toAbsolutePath().normalize().toString();
        return this;
    }

    /** The word at the caret ({@code TM_CURRENT_WORD}). */
    public VariableResolver withCurrentWord(String word) {
        this.currentWord = word == null ? "" : word;
        return this;
    }

    /** The buffer language's comment tokens ({@code LINE_COMMENT}, {@code BLOCK_COMMENT_START/_END}). */
    public VariableResolver withComments(String line, String blockStart, String blockEnd) {
        this.lineComment = line == null ? "" : line;
        this.blockCommentStart = blockStart == null ? "" : blockStart;
        this.blockCommentEnd = blockEnd == null ? "" : blockEnd;
        return this;
    }

    VariableResolver withZone(ZoneId zone) {
        this.zone = zone;
        return this;
    }

    @Override
    public String resolve(String name) {
        return switch (name) {
            case "TM_FILENAME" -> fileName;
            case "TM_FILENAME_BASE" -> baseName(fileName);
            case "TM_DIRECTORY" -> directory;
            case "TM_FILEPATH" -> filePath;
            case "RELATIVE_FILEPATH" -> relativePath();
            case "TM_SELECTED_TEXT", "SELECTION" -> selectedText;
            case "CLIPBOARD" -> clipboard;
            case "TM_LINE_INDEX" -> String.valueOf(lineIndex);
            case "TM_LINE_NUMBER" -> String.valueOf(lineIndex + 1);
            case "TM_CURRENT_LINE" -> currentLine;
            case "TM_CURRENT_WORD" -> currentWord;
            case "WORKSPACE_FOLDER" -> workspaceFolder;
            case "WORKSPACE_NAME" -> workspaceName();
            case "CURSOR_INDEX" -> "0"; // one snippet per expansion: always the first cursor
            case "CURSOR_NUMBER" -> "1";
            case "CURRENT_YEAR" -> fmt("yyyy");
            case "CURRENT_YEAR_SHORT" -> fmt("yy");
            case "CURRENT_MONTH" -> fmt("MM");
            case "CURRENT_MONTH_NAME" -> fmt("MMMM");
            case "CURRENT_MONTH_NAME_SHORT" -> fmt("MMM");
            case "CURRENT_DATE" -> fmt("dd");
            case "CURRENT_DAY_NAME" -> fmt("EEEE");
            case "CURRENT_DAY_NAME_SHORT" -> fmt("EEE");
            case "CURRENT_HOUR" -> fmt("HH");
            case "CURRENT_MINUTE" -> fmt("mm");
            case "CURRENT_SECOND" -> fmt("ss");
            case "CURRENT_SECONDS_UNIX" -> String.valueOf(now.atZone(zone).toEpochSecond());
            case "CURRENT_TIMEZONE_OFFSET" -> timezoneOffset();
            case "RANDOM" -> String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
            case "RANDOM_HEX" ->
                String.format("%06x", ThreadLocalRandom.current().nextInt(0x1000000));
            case "UUID" -> UUID.randomUUID().toString();
            case "LINE_COMMENT" -> lineComment;
            case "BLOCK_COMMENT_START" -> blockCommentStart;
            case "BLOCK_COMMENT_END" -> blockCommentEnd;
            default -> null;
        };
    }

    private String fmt(String pattern) {
        return now.format(DateTimeFormatter.ofPattern(pattern));
    }

    /** {@code +02:00} / {@code -05:30}; UTC is {@code +00:00}, as VS Code prints it. */
    private String timezoneOffset() {
        ZoneOffset offset = now.atZone(zone).getOffset();
        return offset.getTotalSeconds() == 0 ? "+00:00" : offset.getId();
    }

    private String workspaceName() {
        if (workspaceFolder.isEmpty()) {
            return "";
        }
        Path name = Path.of(workspaceFolder).getFileName();
        return name == null ? workspaceFolder : name.toString();
    }

    /** The file path relative to the workspace; the absolute path when it is outside one (as VS Code does). */
    private String relativePath() {
        if (filePath.isEmpty() || workspaceFolder.isEmpty()) {
            return filePath;
        }
        try {
            Path file = Path.of(filePath).toAbsolutePath().normalize();
            Path root = Path.of(workspaceFolder);
            return file.startsWith(root) ? root.relativize(file).toString() : filePath;
        } catch (RuntimeException e) {
            return filePath;
        }
    }

    /** The run of letters, digits and underscores around column {@code col} of {@code line}; "" if none. Pure. */
    public static String wordAt(String line, int col) {
        if (line == null) {
            return "";
        }
        int at = Math.max(0, Math.min(col, line.length()));
        int start = at;
        while (start > 0 && isWordChar(line.charAt(start - 1))) {
            start--;
        }
        int end = at;
        while (end < line.length() && isWordChar(line.charAt(end))) {
            end++;
        }
        return line.substring(start, end);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static String baseName(String file) {
        int dot = file.lastIndexOf('.');
        return dot > 0 ? file.substring(0, dot) : file;
    }
}
