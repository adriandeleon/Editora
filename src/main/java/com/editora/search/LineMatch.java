package com.editora.search;

/**
 * One match within a file: 1-based {@code line} and {@code col} (for jumping via the controller),
 * the match {@code length}, and the full {@code lineText} for a results-panel preview.
 */
public record LineMatch(int line, int col, int length, String lineText) {
}
