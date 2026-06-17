package com.editora.todo;

/**
 * One pattern hit found by {@link TodoScanner}: absolute {@code [start,end)} offsets in the text (for the
 * in-editor highlight), the 1-based {@code line} and {@code col} plus the full {@code lineText} (for the
 * tool-window list), and the matched {@code patternName} + {@code color} (web hex) it belongs to.
 */
public record TodoMatch(int start, int end, int line, int col, String lineText, String patternName, String color) {}
