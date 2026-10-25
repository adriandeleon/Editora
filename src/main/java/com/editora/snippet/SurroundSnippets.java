package com.editora.snippet;

import java.util.List;

/**
 * Which snippets fit a selection (Surround With, Java review E4 / re-review AN4): with nothing selected, every
 * snippet; with a selection, only those whose body takes it ({@code $TM_SELECTED_TEXT}), since any other one
 * replaces it. A selection inside a line is an expression, so only a snippet that uses the selection within a
 * line fits it — a statement snippet wrapped around one ({@code String s = if (c) {…};}) does not compile. Pure.
 */
public final class SurroundSnippets {

    private SurroundSnippets() {}

    public static List<Snippet> forSelection(List<Snippet> snippets, String text, int start, int end) {
        if (start == end) {
            return snippets;
        }
        boolean wholeLines = wholeLines(text, Math.min(start, end), Math.max(start, end));
        return snippets.stream()
                .filter(s -> wholeLines ? takesSelection(s) : takesSelectionInline(s))
                .toList();
    }

    /** Whether the snippet's body uses the selection. */
    static boolean takesSelection(Snippet s) {
        return s.body().contains("TM_SELECTED_TEXT");
    }

    /** Whether it uses the selection with other text on the same line of the body: {@code !(${TM_SELECTED_TEXT})}. */
    static boolean takesSelectionInline(Snippet s) {
        for (String line : s.body().split("\n", -1)) {
            int at = line.indexOf("TM_SELECTED_TEXT");
            if (at < 0) {
                continue;
            }
            String without = line.replaceAll("\\$\\{TM_SELECTED_TEXT[^}]*}|\\$TM_SELECTED_TEXT", "")
                    .replaceAll("\\$\\{\\d+(?::[^}]*)?}|\\$\\d+", ""); // tab stops are not text around it
            if (!without.isBlank()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code [start, end)} covers whole lines: nothing but indentation before it, nothing after it. */
    static boolean wholeLines(String text, int start, int end) {
        int ls = text.lastIndexOf('\n', start - 1) + 1;
        if (!text.substring(ls, start).isBlank()) {
            return false;
        }
        if (end > 0 && text.charAt(end - 1) == '\n') {
            return true;
        }
        int le = text.indexOf('\n', end);
        return text.substring(end, le < 0 ? text.length() : le).isBlank();
    }
}
