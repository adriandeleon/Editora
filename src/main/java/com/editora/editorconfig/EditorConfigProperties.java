package com.editora.editorconfig;

/**
 * The resolved EditorConfig properties that apply to a file, as a set of nullable fields ({@code null} =
 * unset, so "no override" is well-defined). Pure data; produced by {@link EditorConfigParser#toProperties}
 * from the raw values {@link EditorConfig} overlays across sections and directories (see
 * {@link EditorConfigParser#overlay}, which is where {@code key = unset} clears an inherited value).
 *
 * <p>{@code maxLineLength} uses the {@link #OFF} sentinel for {@code max_line_length=off}. {@code endOfLine}
 * is {@code "lf"}/{@code "crlf"}/{@code "cr"}; {@code charset} one of {@code utf-8}/{@code utf-8-bom}/
 * {@code latin1}/{@code utf-16le}/{@code utf-16be}; {@code insertSpaces} is derived from {@code indent_style}.
 */
public record EditorConfigProperties(
        Boolean insertSpaces,
        Integer indentSize,
        Integer tabWidth,
        String endOfLine,
        String charset,
        Boolean trimTrailingWhitespace,
        Boolean insertFinalNewline,
        Integer maxLineLength) {

    /** Sentinel {@code maxLineLength} value for {@code max_line_length = off}. */
    public static final int OFF = -1;

    public static final EditorConfigProperties EMPTY =
            new EditorConfigProperties(null, null, null, null, null, null, null, null);

    public boolean isEmpty() {
        return insertSpaces == null
                && indentSize == null
                && tabWidth == null
                && endOfLine == null
                && charset == null
                && trimTrailingWhitespace == null
                && insertFinalNewline == null
                && maxLineLength == null;
    }

    /** Number of columns per indent: {@code indent_size}, else {@code tab_width}, else {@code fallback}. */
    public int effectiveIndentSize(int fallback) {
        if (indentSize != null && indentSize > 0) {
            return indentSize;
        }
        if (tabWidth != null && tabWidth > 0) {
            return tabWidth;
        }
        return fallback;
    }

    /** Visual tab width: {@code tab_width}, else {@code indent_size}, else {@code fallback}. */
    public int effectiveTabWidth(int fallback) {
        if (tabWidth != null && tabWidth > 0) {
            return tabWidth;
        }
        if (indentSize != null && indentSize > 0) {
            return indentSize;
        }
        return fallback;
    }
}
