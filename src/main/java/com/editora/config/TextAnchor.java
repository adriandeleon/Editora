package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Where a {@link PersonalNote} is anchored within a file: a start and end position (0-based line/column),
 * the captured {@code selectedText}, and short {@code prefix}/{@code suffix} context windows used to
 * relocate the note after the file changes. A Jackson-serialized record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TextAnchor(
        int line, int column, int endLine, int endColumn, String selectedText, String prefix, String suffix) {

    /** Max stored length of the captured selection / context windows. */
    public static final int MAX_TEXT = 200;

    public static final int MAX_CONTEXT = 80;

    public TextAnchor {
        selectedText = cap(selectedText, MAX_TEXT);
        prefix = cap(prefix, MAX_CONTEXT);
        suffix = cap(suffix, MAX_CONTEXT);
    }

    /** This anchor relocated to a new start/end position (keeps the captured text + context). */
    public TextAnchor at(int line, int column, int endLine, int endColumn) {
        return new TextAnchor(line, column, endLine, endColumn, selectedText, prefix, suffix);
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
