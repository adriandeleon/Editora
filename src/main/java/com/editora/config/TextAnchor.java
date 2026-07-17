package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Where a {@link PersonalNote} is anchored within a file: a start and end position (0-based line/column),
 * the captured {@code selectedText}, short {@code prefix}/{@code suffix} context windows used to relocate the
 * note after the file changes, and the original {@code length} of the selection. A Jackson-serialized record.
 *
 * <p>{@code selectedText} is capped ({@link #MAX_TEXT}) to bound {@code notes.json}, but a note can span more
 * than that. {@code length} preserves the <em>full</em> selection length so {@link com.editora.editor.NoteAnchors}
 * highlights the whole span, not just the first {@code MAX_TEXT} characters (#454). An old note deserialized
 * without {@code length} gets {@code 0}, and the relocator falls back to the (capped) needle length.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TextAnchor(
        int line,
        int column,
        int endLine,
        int endColumn,
        String selectedText,
        String prefix,
        String suffix,
        int length) {

    /** Max stored length of the captured selection / context windows. */
    public static final int MAX_TEXT = 200;

    public static final int MAX_CONTEXT = 80;

    public TextAnchor {
        selectedText = cap(selectedText, MAX_TEXT);
        prefix = cap(prefix, MAX_CONTEXT);
        suffix = cap(suffix, MAX_CONTEXT);
        length = Math.max(0, length);
    }

    /**
     * The pre-{@code length} constructor: derives {@code length} from the <em>raw</em> (uncapped)
     * {@code selectedText} argument — so a capture site that passes the full selection text records its true
     * length — before the canonical constructor caps the stored text. Keeps every existing 7-arg call correct.
     */
    public TextAnchor(
            int line, int column, int endLine, int endColumn, String selectedText, String prefix, String suffix) {
        this(
                line,
                column,
                endLine,
                endColumn,
                selectedText,
                prefix,
                suffix,
                selectedText == null ? 0 : selectedText.length());
    }

    /** This anchor relocated to a new start/end position (keeps the captured text + context + length). */
    public TextAnchor at(int line, int column, int endLine, int endColumn) {
        return new TextAnchor(line, column, endLine, endColumn, selectedText, prefix, suffix, length);
    }

    private static String cap(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
