package com.editora.editor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure helper for the "Tab re-indents the current line to the LSP convention" feature. Given a line's text
 * and the {@code textDocument/rangeFormatting} edits the server returned for that line, it computes the
 * line's <b>leading whitespace</b> after formatting — so the editor can adopt only the indentation the
 * formatter chose, without reformatting the rest of the line. Unit-tested; no toolkit dependency.
 */
public final class LineIndent {

    private LineIndent() {}

    /**
     * The leading whitespace of {@code line} after applying {@code edits} (all expected to lie on line
     * {@code lineIndex}), or {@code null} when the result is unusable for an indent-only change — an edit
     * touches another line, or inserts a newline (the formatter would merge/split lines). Edits are applied
     * right-to-left so earlier columns stay valid; out-of-range columns are clamped.
     */
    public static String formattedIndent(String line, List<LspTextEdit> edits, int lineIndex) {
        if (line == null) {
            return null;
        }
        if (edits == null || edits.isEmpty()) {
            return leadingWhitespace(line);
        }
        List<LspTextEdit> sameLine = new ArrayList<>();
        for (LspTextEdit e : edits) {
            if (e == null) {
                continue;
            }
            if (e.startLine() != lineIndex
                    || e.endLine() != lineIndex
                    || e.newText().indexOf('\n') >= 0) {
                return null; // multi-line edit → not an indent-only change
            }
            sameLine.add(e);
        }
        // Apply from the rightmost edit to the leftmost so earlier offsets remain valid.
        sameLine.sort(Comparator.comparingInt(LspTextEdit::startCol).reversed());
        StringBuilder sb = new StringBuilder(line);
        for (LspTextEdit e : sameLine) {
            int from = clamp(e.startCol(), 0, sb.length());
            int to = clamp(e.endCol(), from, sb.length());
            sb.replace(from, to, e.newText());
        }
        return leadingWhitespace(sb.toString());
    }

    /** The run of leading spaces/tabs at the start of {@code s}. */
    public static String leadingWhitespace(String s) {
        int i = 0;
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) {
            i++;
        }
        return s.substring(0, i);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
