package com.editora.editor;

import java.util.List;

/**
 * One change of a buffer's file against {@code HEAD}, as the gutter, the minimap and the hunk commands see
 * it: where it sits in the file <em>on disk</em> and the exact lines on both sides. Kept in {@code editor}
 * (like {@link BlameInfo}) so the package stays free of any {@code com.editora.git} dependency; the window
 * maps git's parsed hunks into these.
 *
 * @param line 0-based on-disk line the change is marked on: the first of the {@code count} changed lines, or
 *     for a pure deletion the line just below the gap
 * @param count how many on-disk lines the change covers; 0 for a pure deletion
 * @param oldLines the {@code HEAD} lines the change replaced, without terminators (empty for an addition)
 * @param newLines the on-disk lines, without terminators (empty for a deletion)
 * @param oldUnterminated the last of {@code oldLines} was the last line of the {@code HEAD} file and had no
 *     final newline
 */
public record GitHunk(int line, int count, List<String> oldLines, List<String> newLines, boolean oldUnterminated) {

    /** What a hunk did, in the order the minimap paints them. */
    public enum Kind {
        ADDED,
        MODIFIED,
        DELETED
    }

    public GitHunk {
        oldLines = List.copyOf(oldLines);
        newLines = List.copyOf(newLines);
    }

    public Kind kind() {
        return count == 0 ? Kind.DELETED : oldLines.isEmpty() ? Kind.ADDED : Kind.MODIFIED;
    }

    /** Lines the gutter marks: the changed lines, or the one line below a pure deletion. */
    public int markerCount() {
        return Math.max(1, count);
    }
}
