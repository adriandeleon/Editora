package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A bookmark on a single line of a file: a 0-based line index, an optional user note/label, a captured
 * snapshot of the line's text (so the Bookmarks panel can label bookmarks in files that aren't open
 * without reading them from disk), and an optional single-character <em>mnemonic</em> that gives it a
 * one-chord jump. Persisted (per file path, bucketed per project) in the {@link BookmarkStore}
 * ({@code bookmarks.json}).
 *
 * <p>A Jackson-serialized record; the {@code com.editora.config} package is already opened to
 * jackson.databind in {@code module-info.java} (see {@link Project}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Bookmark(int line, String note, String lineText, String mnemonic) {

    /** Max stored length of the captured line snapshot. */
    public static final int MAX_LINE_TEXT = 200;

    public Bookmark {
        note = note == null ? "" : note;
        lineText = lineText == null ? "" : lineText;
        // Exactly one character or nothing. A mnemonic is a single keystroke by definition, so a longer
        // string is not a smaller feature — it is a value no chord could ever reach.
        mnemonic = mnemonic == null || mnemonic.length() != 1 ? "" : mnemonic;
        if (lineText.length() > MAX_LINE_TEXT) {
            lineText = lineText.substring(0, MAX_LINE_TEXT);
        }
    }

    /** A bookmark with no mnemonic — the shape every caller predating them used. */
    public Bookmark(int line, String note, String lineText) {
        this(line, note, lineText, "");
    }

    public boolean hasMnemonic() {
        return !mnemonic.isEmpty();
    }

    /** This bookmark moved to a different line (keeps the note, captured text and mnemonic). */
    public Bookmark withLine(int newLine) {
        return new Bookmark(newLine, note, lineText, mnemonic);
    }

    /** This bookmark with a new note (keeps the line, captured text and mnemonic). */
    public Bookmark withNote(String newNote) {
        return new Bookmark(line, newNote, lineText, mnemonic);
    }

    /** This bookmark with a new mnemonic, or {@code ""} to clear it. */
    public Bookmark withMnemonic(String newMnemonic) {
        return new Bookmark(line, note, lineText, newMnemonic);
    }
}
