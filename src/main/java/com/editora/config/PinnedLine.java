package com.editora.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The values pinned to one line of a file while debugging (Visual Studio's pinned DataTips, IntelliJ's inline
 * watches): each expression is evaluated in the selected frame at every stop and its value painted at the end of
 * the line. A 0-based {@code line} plus a snapshot of its text, so the pins follow their line when the file is edited
 * outside the editor (as {@link Breakpoint} does). Persisted per file, per project, in {@link BreakpointStore}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PinnedLine(int line, List<String> expressions, String lineText) {

    /** Max stored length of the captured line snapshot. */
    public static final int MAX_LINE_TEXT = 200;

    public PinnedLine {
        expressions = expressions == null
                ? List.of()
                : expressions.stream()
                        .filter(e -> e != null && !e.isBlank())
                        .map(String::strip)
                        .distinct()
                        .toList();
        lineText = lineText == null ? "" : lineText;
        if (lineText.length() > MAX_LINE_TEXT) {
            lineText = lineText.substring(0, MAX_LINE_TEXT);
        }
    }

    public PinnedLine withLine(int newLine) {
        return new PinnedLine(newLine, expressions, lineText);
    }

    public PinnedLine withLineText(String newLineText) {
        return new PinnedLine(line, expressions, newLineText);
    }

    public PinnedLine withExpressions(List<String> newExpressions) {
        return new PinnedLine(line, newExpressions, lineText);
    }
}
