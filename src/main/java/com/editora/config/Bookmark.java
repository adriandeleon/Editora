package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A bookmark on a single line of a file: a 0-based line index, an optional user note/label, and a
 * captured snapshot of the line's text (so the global Bookmarks panel can label bookmarks in files
 * that aren't open without reading them from disk). Persisted per file in {@link WorkspaceState}.
 *
 * <p>A Jackson-serialized record; the {@code com.editora.config} package is already opened to
 * jackson.databind in {@code module-info.java} (see {@link Project}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bookmark(int line, String note, String lineText) {

    /** Max stored length of the captured line snapshot. */
    public static final int MAX_LINE_TEXT = 200;

    public Bookmark {
        note = note == null ? "" : note;
        lineText = lineText == null ? "" : lineText;
        if (lineText.length() > MAX_LINE_TEXT) {
            lineText = lineText.substring(0, MAX_LINE_TEXT);
        }
    }

    /** This bookmark moved to a different line (keeps the note + captured text). */
    public Bookmark withLine(int newLine) {
        return new Bookmark(newLine, note, lineText);
    }

    /** This bookmark with a new note (keeps the line + captured text). */
    public Bookmark withNote(String newNote) {
        return new Bookmark(line, newNote, lineText);
    }
}
