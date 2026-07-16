package com.editora.completion;

import java.util.ArrayList;
import java.util.List;

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
        // Everything below indexes `label` directly and folds case per character. Matching against a
        // lowercased *copy* would be wrong: String.toLowerCase is not length-preserving ("İ" U+0130 → "i̇",
        // one char becoming two), so a copy's indexes drift past such a char — mis-highlighting the label,
        // and overrunning its end (the popup cell substrings these ranges) when the copy is longer.

        // 1) Contiguous case-insensitive substring (prefix or internal) — one clean run.
        int idx = indexOfIgnoreCase(label, q);
        if (idx >= 0) {
            return new int[][] {{idx, idx + q.length()}};
        }

        // 2) Subsequence (camelCase / fuzzy): match query chars in order, coalescing adjacent hits.
        List<int[]> ranges = new ArrayList<>();
        int i = 0;
        int runStart = -1;
        int prev = -2;
        for (int j = 0; j < label.length() && i < q.length(); j++) {
            if (equalsIgnoreCase(label.charAt(j), q.charAt(i))) {
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
        if (i < q.length()) {
            return new int[0][]; // not a full subsequence → don't highlight
        }
        if (runStart >= 0) {
            ranges.add(new int[] {runStart, prev + 1});
        }
        return ranges.toArray(new int[0][]);
    }

    /** First index in {@code s} where {@code sub} occurs case-insensitively, or -1. Length-preserving. */
    private static int indexOfIgnoreCase(String s, String sub) {
        for (int i = 0; i + sub.length() <= s.length(); i++) {
            if (s.regionMatches(true, i, sub, 0, sub.length())) {
                return i;
            }
        }
        return -1;
    }

    private static boolean equalsIgnoreCase(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }
}
