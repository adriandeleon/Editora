package com.editora.lsp;

import org.eclipse.lsp4j.Position;

/**
 * Pure conversions between a document's flat character offset and LSP {@link Position} (0-based
 * line + character). LSP's default position encoding is UTF-16 code units, which is exactly what a Java
 * {@code String}/{@code CharSequence} index is, so the editor's column (a RichTextFX paragraph column,
 * also a Java char index) maps to an LSP character with no re-encoding.
 *
 * <p>All methods are static and side-effect-free so they can be unit-tested without a toolkit. Line
 * breaks are counted on {@code '\n'} (the editor normalizes CRLF on load), and out-of-range inputs are
 * clamped rather than throwing.
 */
public final class LspPositions {

    private LspPositions() {}

    /** An LSP position for a 0-based line + character (both clamped to {@code >= 0}). */
    public static Position position(int line, int character) {
        return new Position(Math.max(0, line), Math.max(0, character));
    }

    /**
     * The {@code [line, character]} (0-based) of a flat {@code offset} into {@code text}. The offset is
     * clamped to {@code [0, text.length()]}; {@code character} is the count of chars since the last
     * {@code '\n'}.
     */
    public static int[] lineChar(String text, int offset) {
        if (text == null) {
            return new int[] {0, 0};
        }
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < clamped; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new int[] {line, clamped - lineStart};
    }

    /** An LSP {@link Position} for a flat {@code offset} into {@code text}. */
    public static Position toPosition(String text, int offset) {
        int[] lc = lineChar(text, offset);
        return new Position(lc[0], lc[1]);
    }

    /**
     * The flat offset into {@code text} for a 0-based {@code line} + {@code character}. A line beyond the
     * end clamps to the text length; a character beyond the line's length clamps to the line end. Pure.
     */
    public static int offset(String text, int line, int character) {
        if (text == null || line < 0) {
            return 0;
        }
        int idx = 0;
        int currentLine = 0;
        int len = text.length();
        while (currentLine < line && idx < len) {
            if (text.charAt(idx) == '\n') {
                currentLine++;
            }
            idx++;
        }
        if (currentLine < line) {
            return len; // requested line is past the end
        }
        int lineEnd = text.indexOf('\n', idx);
        if (lineEnd < 0) {
            lineEnd = len;
        }
        return Math.min(idx + Math.max(0, character), lineEnd);
    }

    /** The flat offset into {@code text} for an LSP {@link Position}. */
    public static int offset(String text, Position position) {
        return position == null ? 0 : offset(text, position.getLine(), position.getCharacter());
    }
}
