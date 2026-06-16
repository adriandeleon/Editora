package com.editora.completion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Computes which characters of a completion label match the typed query, so the popup can <b>bold</b> them
 * (IntelliJ-style). Pure and unit-tested. Prefers a single contiguous (case-insensitive) substring — the
 * common prefix/substring case — and otherwise falls back to a camelCase/subsequence match, coalescing the
 * matched characters into runs. A query that isn't even a subsequence of the label yields no ranges (the
 * row is shown unhighlighted rather than mis-highlighted).
 */
public final class MatchHighlighter {

    private MatchHighlighter() {}

    /** Half-open {@code [start,end)} character ranges of {@code label} to emphasize for {@code query}. */
    public static int[][] matchRanges(String label, String query) {
        if (label == null || label.isEmpty() || query == null) {
            return new int[0][];
        }
        String q = query.strip();
        if (q.isEmpty()) {
            return new int[0][];
        }
        String ll = label.toLowerCase(Locale.ROOT);
        String lq = q.toLowerCase(Locale.ROOT);

        // 1) Contiguous case-insensitive substring (prefix or internal) — one clean run.
        int idx = ll.indexOf(lq);
        if (idx >= 0) {
            return new int[][] {{idx, idx + lq.length()}};
        }

        // 2) Subsequence (camelCase / fuzzy): match query chars in order, coalescing adjacent hits.
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        int runStart = -1;
        int prev = -2;
        for (int j = 0; j < label.length() && i < lq.length(); j++) {
            if (ll.charAt(j) == lq.charAt(i)) {
                if (j != prev + 1) {
                    if (runStart >= 0) {
                        ranges.add(new int[] {runStart, prev + 1});
                    }
                    runStart = j;
                }
                prev = j;
                i++;
            }
        }
        if (i < lq.length()) {
            return new int[0][]; // not a full subsequence → don't highlight
        }
        if (runStart >= 0) {
            ranges.add(new int[] {runStart, prev + 1});
        }
        return ranges.toArray(new int[0][]);
    }
}
