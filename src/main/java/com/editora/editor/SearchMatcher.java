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
        // regionMatches folds per character, so it cannot match a case pair of different lengths (ß↔SS,
        // ﬁ↔FI). Take the full-folding path only when one side actually contains such a character — the
        // check is one comparison per char and rejects all ASCII, so ordinary code pays nothing (#444).
        if (!caseSensitive && (CaseFold.mayExpand(query) || CaseFold.mayExpand(text))) {
            return foldedMatches(text, query, wholeWord);
        }
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

    /**
     * Case-insensitive literal matching under <b>full</b> case folding, so a length-changing fold matches in
     * both directions. Offsets are the original text's throughout — {@link CaseFold#matchAt} folds on the fly
     * rather than searching a folded copy, so there is no index map to translate back through.
     */
    private static List<int[]> foldedMatches(String text, String query, boolean wholeWord) {
        String folded = CaseFold.fold(query);
        if (folded.isEmpty()) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; ) {
            int end = CaseFold.matchAt(text, i, folded);
            if (end > i && (!wholeWord || isWordBounded(text, i, end))) {
                out.add(new int[] {i, end});
                i = end; // non-overlapping
                continue;
            }
            i += Character.charCount(text.codePointAt(i));
        }
        return out;
    }

    /**
     * Compiles the regex query exactly as {@link #matches} does (whole-word wrapped in a non-capturing
     * group so user capture groups keep their numbers), or {@code null} on a bad pattern. Shared with
     * {@code MultiFileSearch.replaceAll}'s capture-group replace so the two can't diverge.
     */
    public static Pattern compileRegex(String query, boolean caseSensitive, boolean wholeWord) {
        String pattern = wholeWord ? "\\b(?:" + (query == null ? "" : query) + ")\\b" : (query == null ? "" : query);
        try {
            // UNICODE_CASE so case-insensitive folds non-ASCII too (é↔É) — matching the literal path's
            // String.regionMatches folding and ripgrep's -i; without it the regex path silently misses
            // accented/Cyrillic/Greek case variants that the other two backends find.
            int flags = caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            return Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Wall-clock budget for a single regex search before it's abandoned (see {@link Deadline}). */
    static final long DEFAULT_MATCH_BUDGET_NANOS = 1_000_000_000L; // 1 s

    private static List<int[]> regexMatches(String text, String query, boolean caseSensitive, boolean wholeWord) {
        return regexMatches(text, query, caseSensitive, wholeWord, DEFAULT_MATCH_BUDGET_NANOS);
    }

    /**
     * Regex matches, but bounded in time. {@code java.util.regex} is a backtracking engine with no timeout, so
     * a <b>valid</b> but pathological pattern — the classic {@code (a+)+$} against a long run of {@code a}s
     * ending in a non-match — backtracks for effectively forever. The in-editor find bar runs this
     * <em>synchronously on the JavaFX thread</em> (a debounce only delays it), so such a pattern froze the whole
     * editor with no way out ({@code regexError} only catches <em>syntax</em> errors, and a pathological pattern
     * compiles fine). The input is wrapped in a {@link Deadline} sequence whose {@code charAt} aborts the match
     * once the budget passes — the engine touches {@code charAt} on every backtrack step, so it unwinds
     * promptly — and we return whatever was found so far rather than hang. Package-visible budget overload for
     * tests.
     */
    static List<int[]> regexMatches(
            String text, String query, boolean caseSensitive, boolean wholeWord, long budgetNanos) {
        Pattern p = compileRegex(query, caseSensitive, wholeWord);
        if (p == null) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        Matcher matcher = p.matcher(new Deadline(text, budgetNanos));
        int from = 0;
        try {
            while (from <= text.length() && matcher.find(from)) {
                int start = matcher.start();
                int end = matcher.end();
                out.add(new int[] {start, end});
                from = end > start ? end : end + 1; // advance past a zero-width match
            }
        } catch (MatchAbortedException aborted) {
            return out; // budget exceeded — partial results beat freezing the UI
        }
        return out;
    }

    /** Thrown by {@link Deadline} to unwind a runaway backtracking match; caught in {@link #regexMatches}. */
    private static final class MatchAbortedException extends RuntimeException {
        MatchAbortedException() {
            super(null, null, false, false); // no message/stacktrace — it's control flow, not an error
        }
    }

    /**
     * A read-only {@link CharSequence} view of the text that throws {@link MatchAbortedException} from
     * {@code charAt} once {@code deadlineNanos} passes. The clock is only sampled every 1024th access (a bit
     * mask) so the common fast path stays a plain array read.
     */
    private static final class Deadline implements CharSequence {
        private final CharSequence text;
        private final long deadlineNanos;
        private int ticks;

        Deadline(CharSequence text, long budgetNanos) {
            this.text = text;
            this.deadlineNanos = System.nanoTime() + budgetNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++ticks & 0x3FF) == 0 && System.nanoTime() > deadlineNanos) {
                throw new MatchAbortedException();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return text.subSequence(start, end);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }

    /**
     * True word-boundary ({@code \b}) test for a literal match — a boundary exists where the char inside the
     * match and the char just outside it differ in word-ness. Using {@code \b} semantics (rather than
     * "the outside char must be a non-word char") keeps literal whole-word in agreement with the regex path's
     * {@code \b(?:…)\b} and with ripgrep {@code -w} when the query's own edge char is a non-word char (e.g.
     * {@code +foo} does match in {@code a+foo} — there is a boundary between {@code a} and {@code +}). For a
     * query with word-char edges (the common case) this is identical to the old test.
     */
    private static boolean isWordBounded(String text, int start, int end) {
        boolean beforeWord = start > 0 && isWordChar(text.charAt(start - 1));
        boolean firstWord = isWordChar(text.charAt(start));
        boolean lastWord = isWordChar(text.charAt(end - 1));
        boolean afterWord = end < text.length() && isWordChar(text.charAt(end));
        return (beforeWord != firstWord) && (lastWord != afterWord);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
