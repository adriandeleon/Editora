package com.editora.text;

import java.util.List;

/** Pure checks over a server's {@link LspTextEdit}s against the text they were computed for. */
public final class LspTextEdits {

    private LspTextEdits() {}

    /**
     * Whether applying {@code edits} to {@code text} would leave it as it is: every edit replaces its range with
     * the text already there. bash-language-server answers Format Document with one whole-file edit even when shfmt
     * changed nothing, and Editora said "Document formatted" over an untouched file (Bash review B14). An edit
     * outside the text counts as a change, so a doubtful answer is applied as before.
     */
    public static boolean changesNothing(String text, List<LspTextEdit> edits) {
        if (text == null || edits == null) {
            return false;
        }
        int[] starts = lineStarts(text);
        for (LspTextEdit e : edits) {
            int from = offset(text, starts, e.startLine(), e.startCol());
            int to = offset(text, starts, e.endLine(), e.endCol());
            if (from < 0 || to < from) {
                return false;
            }
            String newText = e.newText() == null ? "" : e.newText();
            if (!text.substring(from, to).equals(newText.replace("\r\n", "\n"))) {
                return false;
            }
        }
        return true;
    }

    private static int[] lineStarts(String text) {
        int count = 1;
        for (int i = text.indexOf('\n'); i >= 0; i = text.indexOf('\n', i + 1)) {
            count++;
        }
        int[] starts = new int[count];
        for (int i = text.indexOf('\n'), k = 1; i >= 0; i = text.indexOf('\n', i + 1)) {
            starts[k++] = i + 1;
        }
        return starts;
    }

    /**
     * The offset of {@code (line, col)}, or -1 when it is not in {@code text}. A position past the last line is the
     * end of the text, as servers address "the end" of a whole-document edit ({@code line = lineCount}).
     */
    private static int offset(String text, int[] starts, int line, int col) {
        if (line < 0 || col < 0) {
            return -1;
        }
        if (line >= starts.length) {
            return col == 0 || line == starts.length ? text.length() : -1;
        }
        int lineEnd = line + 1 < starts.length ? starts[line + 1] - 1 : text.length();
        return Math.min(starts[line] + col, lineEnd);
    }
}
