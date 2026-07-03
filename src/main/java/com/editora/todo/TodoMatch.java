package com.editora.todo;

/**
 * One pattern hit found by {@link TodoScanner}: absolute {@code [start,end)} offsets in the text (for the
 * in-editor highlight), the 1-based {@code line} and {@code col} plus the full {@code lineText} (for the
 * tool-window list), the matched {@code patternName} + {@code color} (web hex) it belongs to, and the
 * {@link TodoComment} structured parse of the rest of the comment ({@code [tag] (priority) description}).
 */
public record TodoMatch(
        int start, int end, int line, int col, String lineText, String patternName, String color, TodoComment parsed) {

    /** Back-compat constructor for callers/tests that don't supply a parsed structure ({@code parsed} = null). */
    public TodoMatch(int start, int end, int line, int col, String lineText, String patternName, String color) {
        this(start, end, line, col, lineText, patternName, color, null);
    }
}
