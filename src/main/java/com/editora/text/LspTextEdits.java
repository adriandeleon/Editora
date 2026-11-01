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

    /**
     * {@code edits} (a format of {@code text}) made to leave the text ending with a line break when {@code want} is
     * true, and without one when it is false. The HTML server is told never to add the final newline itself — its
     * option also reached the CSS beautifier of every {@code <style>}, which put a blank line before each
     * {@code </style>} (HTML review 2, B104) — so Editora keeps it: the edit reaching the end of the text gets the
     * line break added to (or taken off) its new text; when no edit reaches the end, one is added there if
     * {@code whole} (a Format Document; a Format Selection that stops short of the end leaves the end alone). No
     * edits, or an edit outside the text, leave {@code edits} as they are.
     */
    public static List<LspTextEdit> withFinalNewline(
            String text, List<LspTextEdit> edits, boolean want, boolean whole) {
        if (text == null || edits == null || edits.isEmpty()) {
            return edits;
        }
        int[] starts = lineStarts(text);
        int end = text.length();
        int last = -1;
        int reach = -1;
        for (int i = 0; i < edits.size(); i++) {
            LspTextEdit e = edits.get(i);
            int from = offset(text, starts, e.startLine(), e.startCol());
            int to = offset(text, starts, e.endLine(), e.endCol());
            if (from < 0 || to < from) {
                return edits;
            }
            reach = Math.max(reach, to);
            if (to == end) {
                last = i;
            }
        }
        List<LspTextEdit> out = new java.util.ArrayList<>(edits);
        if (last >= 0) {
            LspTextEdit e = edits.get(last);
            String newText = e.newText() == null ? "" : e.newText();
            int from = offset(text, starts, e.startLine(), e.startCol());
            // an edit that adds nothing leaves the text ending as what is before it does
            boolean ends = newText.isEmpty() ? from > 0 && text.charAt(from - 1) == '\n' : newText.endsWith("\n");
            if (want && !ends) {
                newText = newText + "\n";
            } else if (!want && ends && !newText.isEmpty()) {
                newText = newText.replaceFirst("[\\r\\n]+$", "");
            }
            out.set(last, new LspTextEdit(e.startLine(), e.startCol(), e.endLine(), e.endCol(), newText));
            return out;
        }
        if (!whole) {
            return edits;
        }
        int lastLine = starts.length - 1;
        int lastCol = end - starts[lastLine];
        boolean ends = text.endsWith("\n");
        if (want && !ends) {
            out.add(new LspTextEdit(lastLine, lastCol, lastLine, lastCol, "\n"));
        } else if (!want && ends) {
            int from = end;
            while (from > 0 && (text.charAt(from - 1) == '\n' || text.charAt(from - 1) == '\r')) {
                from--;
            }
            if (from >= reach) { // no other edit touches the line breaks
                int line = java.util.Arrays.binarySearch(starts, from);
                line = line >= 0 ? line : -line - 2;
                out.add(new LspTextEdit(line, from - starts[line], lastLine, lastCol, ""));
            }
        }
        return out;
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
