package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure, allocation-light search core shared by the in-editor find bar and multi-file search. Finds all
 * non-overlapping matches of a query in a text with literal or regex matching, optional case sensitivity
 * and whole-word boundaries. No JavaFX, so it is unit-tested.
 */
public final class SearchMatcher {

    private SearchMatcher() {}

    /**
     * All non-overlapping matches of {@code query} in {@code text} as {@code [start, end)} offset pairs.
     * Empty for a null/empty query or an invalid regex.
     */
    public static List<int[]> matches(
            String text, String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
        if (text == null || query == null || query.isEmpty()) {
            return List.of();
        }
        return regex
                ? regexMatches(text, query, caseSensitive, wholeWord)
                : literalMatches(text, query, caseSensitive, wholeWord);
    }

    /** The regex compile error description, or {@code null} if {@code query} is a valid pattern. */
    public static String regexError(String query) {
        try {
            Pattern.compile(query == null ? "" : query);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    /**
     * Index of the match to jump to from caret offset {@code fromOffset}, wrapping around: forward → the
     * first match starting at/after {@code fromOffset} (else the first match); backward → the last match
     * starting before it (else the last match). Returns -1 when there are no matches.
     */
    public static int nextIndex(List<int[]> matches, int fromOffset, boolean forward) {
        if (matches.isEmpty()) {
            return -1;
        }
        if (forward) {
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i)[0] >= fromOffset) {
                    return i;
                }
            }
            return 0;
        }
        for (int i = matches.size() - 1; i >= 0; i--) {
            if (matches.get(i)[0] < fromOffset) {
                return i;
            }
        }
        return matches.size() - 1;
    }

    /** Index of the match that contains or starts at {@code caret}, else -1. */
    public static int indexAt(List<int[]> matches, int caret) {
        for (int i = 0; i < matches.size(); i++) {
            if (caret >= matches.get(i)[0] && caret <= matches.get(i)[1]) {
                return i;
            }
        }
        return -1;
    }

    private static List<int[]> literalMatches(String text, String query, boolean caseSensitive, boolean wholeWord) {
        List<int[]> out = new ArrayList<>();
        int n = text.length();
        int m = query.length();
        for (int i = 0; i + m <= n; ) {
            if (text.regionMatches(!caseSensitive, i, query, 0, m)) {
                int end = i + m;
                if (!wholeWord || isWordBounded(text, i, end)) {
                    out.add(new int[] {i, end});
                    i = end; // non-overlapping
                    continue;
                }
            }
            i++;
        }
        return out;
    }

    private static List<int[]> regexMatches(String text, String query, boolean caseSensitive, boolean wholeWord) {
        String pattern = wholeWord ? "\\b(?:" + query + ")\\b" : query;
        Pattern p;
        try {
            p = Pattern.compile(pattern, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        Matcher matcher = p.matcher(text);
        int from = 0;
        while (from <= text.length() && matcher.find(from)) {
            int start = matcher.start();
            int end = matcher.end();
            out.add(new int[] {start, end});
            from = end > start ? end : end + 1; // advance past a zero-width match
        }
        return out;
    }

    private static boolean isWordBounded(String text, int start, int end) {
        boolean leftOk = start == 0 || !isWordChar(text.charAt(start - 1));
        boolean rightOk = end == text.length() || !isWordChar(text.charAt(end));
        return leftOk && rightOk;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
