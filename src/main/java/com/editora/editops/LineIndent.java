package com.editora.editops;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.editora.text.LspTextEdit;
import org.jspecify.annotations.Nullable;

/**
 * Pure helper for the "Tab re-indents the current line to the LSP convention" feature. Given a line's text
 * and the {@code textDocument/rangeFormatting} edits the server returned for that line, it computes the
 * line's <b>leading whitespace</b> after formatting — so the editor can adopt only the indentation the
 * formatter chose, without reformatting the rest of the line. Unit-tested; no toolkit dependency.
 */
public final class LineIndent {

    private LineIndent() {}

    /**
     * The leading whitespace of {@code line} after applying {@code edits}, or {@code null} when the result is
     * unusable for an indent-only change — an edit reaches further back, joins or splits lines, or rewrites
     * text past the indent. Edits on line {@code lineIndex} apply as they are. lemminx (and others) express an
     * indent as one edit from the previous line's end, {@code "\n" + indent} replacing the line break and the
     * old indent: that one is read as the new indent (XML review B4). Edits that cannot touch this line's indent
     * are ignored: those wholly on the previous line (its trailing whitespace, its attributes), those on later
     * lines, and a line break the formatter puts after this line's start tag — lemminx formats the whole
     * element the line opens, so Tab on {@code <d>} came back with the {@code <d>…</d>} split too, and the
     * fallback typed spaces inside the tag (XML review 2 B4). Edits are applied right-to-left so earlier columns
     * stay valid; out-of-range columns are clamped.
     */
    public static @Nullable String formattedIndent(String line, List<LspTextEdit> edits, int lineIndex) {
        if (line == null) {
            return null;
        }
        if (edits == null || edits.isEmpty()) {
            return leadingWhitespace(line);
        }
        List<LspTextEdit> sameLine = new ArrayList<>();
        int indentLength = leadingWhitespace(line).length();
        boolean lineBreak = false;
        for (LspTextEdit e : edits) {
            if (e == null) {
                continue;
            }
            if ((e.startLine() == lineIndex - 1 && e.endLine() == lineIndex - 1) || e.startLine() > lineIndex) {
                continue; // the previous line's own whitespace or attributes; a later line's indent
            }
            if (e.startLine() == lineIndex
                    && e.startCol() > indentLength
                    && (e.endLine() != lineIndex || e.newText().indexOf('\n') >= 0)) {
                continue; // a break after this line's indent and text began: it splits the line, its indent stays
            }
            int newline = e.newText().lastIndexOf('\n');
            if (e.startLine() == lineIndex - 1
                    && e.endLine() == lineIndex
                    && newline >= 0
                    && e.endCol() <= indentLength
                    && !lineBreak
                    && e.newText().substring(0, newline).isBlank()) {
                lineBreak = true; // the break and this line's indent, rewritten: keep what follows the break
                sameLine.add(new LspTextEdit(
                        lineIndex, 0, lineIndex, e.endCol(), e.newText().substring(newline + 1)));
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
