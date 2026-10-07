package com.editora.ui;

/**
 * Which change "next" and "previous" mean from a given line. Pure; unit-tested.
 *
 * <p>The changes are the flat {@code {line, count, kind}} triples of
 * {@link com.editora.editor.GitGutterLines#marks()}, top to bottom.
 */
final class GitHunkNav {

    private GitHunkNav() {}

    /** Number of changes in {@code marks}. */
    static int count(int[] marks) {
        return marks.length / 3;
    }

    /** Index of the change marked on {@code line}, or -1. */
    static int at(int[] marks, int line) {
        for (int i = 0; i + 2 < marks.length; i += 3) {
            if (line >= marks[i] && line < marks[i] + marks[i + 1]) {
                return i / 3;
            }
        }
        return -1;
    }

    /**
     * The change to move to from {@code line}: its index, or {@code -(index + 1)} when getting there wrapped
     * around the end of the document. From inside a change, "previous" is the change before it, not its own
     * first line. {@link Integer#MIN_VALUE} when there are no changes.
     */
    static int target(int[] marks, int line, boolean forward) {
        int n = count(marks);
        if (n == 0) {
            return Integer.MIN_VALUE;
        }
        int current = at(marks, line);
        if (forward) {
            for (int i = 0; i < n; i++) {
                if (marks[i * 3] > line) {
                    return i;
                }
            }
            return -1; // wrapped to the first
        }
        int from = current >= 0 ? marks[current * 3] : line;
        for (int i = n - 1; i >= 0; i--) {
            if (marks[i * 3] < from) {
                return i;
            }
        }
        return -n; // wrapped to the last
    }

    /** The index {@link #target} encodes, whether or not it wrapped. */
    static int index(int target) {
        return target < 0 ? -target - 1 : target;
    }
}
