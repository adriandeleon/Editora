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
        return matches(text, query, caseSensitive, regex, wholeWord, Integer.MAX_VALUE);
    }

    /** Bounded variant used by multi-file search so dense lines cannot allocate past its result budget. */
    public static List<int[]> matches(
            String text, String query, boolean caseSensitive, boolean regex, boolean wholeWord, int limit) {
        return search(text, query, caseSensitive, regex, wholeWord, limit).matches();
    }

    /**
     * A search's matches plus whether the search ran to the end of the text. {@code complete} is false when a
     * regex search was abandoned part-way — it ran out of its time budget or overflowed the stack — so the
     * caller can say so instead of presenting a partial (possibly empty) list as the whole answer.
     */
    public record Result(List<int[]> matches, boolean complete) {}

    /** As {@link #matches(String, String, boolean, boolean, boolean)}, also reporting an abandoned search. */
    public static Result search(String text, String query, boolean caseSensitive, boolean regex, boolean wholeWord) {
        return search(text, query, caseSensitive, regex, wholeWord, Integer.MAX_VALUE);
    }

    private static Result search(
            String text, String query, boolean caseSensitive, boolean regex, boolean wholeWord, int limit) {
        if (text == null || query == null || query.isEmpty()) {
            return new Result(List.of(), true);
        }
        return regex
                ? regexSearch(text, query, caseSensitive, wholeWord, DEFAULT_MATCH_BUDGET_NANOS, limit)
                : new Result(literalMatches(text, query, caseSensitive, wholeWord, limit), true);
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

    private static List<int[]> literalMatches(
            String text, String query, boolean caseSensitive, boolean wholeWord, int limit) {
        if (limit <= 0 || Thread.currentThread().isInterrupted()) {
            return List.of();
        }
        // regionMatches folds per character, so it cannot match a case pair of different lengths (ß↔SS,
        // ﬁ↔FI). Take the full-folding path only when one side actually contains such a character — the
        // check is one comparison per char and rejects all ASCII, so ordinary code pays nothing (#444).
        if (!caseSensitive) {
            boolean expandingQuery = CaseFold.mayExpand(query);
            if (Thread.currentThread().isInterrupted()) {
                return List.of();
            }
            boolean expandingText = !expandingQuery && CaseFold.mayExpand(text);
            if (Thread.currentThread().isInterrupted()) {
                return List.of();
            }
            if (expandingQuery || expandingText) {
                return foldedMatches(text, query, wholeWord, limit);
            }
        }
        List<int[]> out = new ArrayList<>();
        int n = text.length();
        int m = query.length();
        for (int i = 0; i + m <= n; ) {
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                return out;
            }
            if (text.regionMatches(!caseSensitive, i, query, 0, m)) {
                int end = i + m;
                if (!wholeWord || isWordBounded(text, i, end)) {
                    out.add(new int[] {i, end});
                    if (out.size() >= limit) {
                        return out;
                    }
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
    private static List<int[]> foldedMatches(String text, String query, boolean wholeWord, int limit) {
        String folded = CaseFold.fold(query);
        if (folded.isEmpty()) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        int n = text.length();
        for (int i = 0; i < n; ) {
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                return out;
            }
            int end = CaseFold.matchAt(text, i, folded);
            if (end > i && (!wholeWord || isWordBounded(text, i, end))) {
                out.add(new int[] {i, end});
                if (out.size() >= limit) {
                    return out;
                }
                i = end; // non-overlapping
                continue;
            }
            i += Character.charCount(text.codePointAt(i));
        }
        return out;
    }

    /** A character that counts as part of a word for whole-word matching — the regex twin of isWordChar. */
    private static final String WORD_CHAR = "[\\p{L}\\p{Nd}_]";

    /**
     * Compiles the regex query for a <b>line-oriented</b> caller ({@code MultiFileSearch}, which hands in one
     * line at a time), or {@code null} on a bad pattern: the same whole-word wrapping and case flags as
     * {@link #matches}, but without {@link Pattern#MULTILINE}. On a single line the flag adds nothing, and it
     * takes something away — a MULTILINE {@code ^} refuses to match at the end of the input, so {@code ^},
     * {@code ^$} and {@code ^\s*$} would all miss an empty line.
     */
    public static Pattern compileRegex(String query, boolean caseSensitive, boolean wholeWord) {
        return compile(query, caseSensitive, wholeWord, false);
    }

    /**
     * Compiles the regex query for a <b>whole-document</b> search (the find bar, Replace All, Query Replace),
     * or {@code null} on a bad pattern: {@code ^} and {@code $} anchor at every line rather than only at the
     * document's two ends.
     */
    public static Pattern compileDocumentRegex(String query, boolean caseSensitive, boolean wholeWord) {
        return compile(query, caseSensitive, wholeWord, true);
    }

    private static Pattern compile(String query, boolean caseSensitive, boolean wholeWord, boolean multiline) {
        String q = query == null ? "" : query;
        // Whole-word means "no word character immediately before or after the match" — the same test the
        // literal path makes (isWordBounded) and the half boundaries ripgrep -w uses. The query sits in a
        // non-capturing group so user capture groups keep their numbers.
        String pattern = wholeWord ? "(?<!" + WORD_CHAR + ")(?:" + q + ")(?!" + WORD_CHAR + ")" : q;
        try {
            // UNICODE_CASE so case-insensitive folds non-ASCII too (é↔É) — matching the literal path's
            // String.regionMatches folding and ripgrep's -i; without it the regex path silently misses
            // accented/Cyrillic/Greek case variants that the other two backends find.
            int flags = (multiline ? Pattern.MULTILINE : 0)
                    | (caseSensitive ? 0 : (Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            return Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            return null;
        }
    }

    /** Wall-clock budget for a single regex search before it's abandoned (see {@link Deadline}). */
    static final long DEFAULT_MATCH_BUDGET_NANOS = 1_000_000_000L; // 1 s

    /**
     * Wraps {@code text} in a read-only {@link CharSequence} that aborts a backtracking regex match once the
     * shared {@link #DEFAULT_MATCH_BUDGET_NANOS} wall-clock budget passes, throwing a
     * {@link MatchBudgetExceededException}. Lets another synchronous FX-thread regex walk — the find bar's
     * Replace All — share the exact guard the incremental search already uses, instead of running an unbounded
     * {@link Matcher} that a pathological-but-valid pattern (the classic {@code (a+)+b}) can freeze the UI on.
     */
    public static CharSequence budgetedSequence(CharSequence text) {
        return new Deadline(text, DEFAULT_MATCH_BUDGET_NANOS);
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
        return regexSearch(text, query, caseSensitive, wholeWord, budgetNanos, Integer.MAX_VALUE)
                .matches();
    }

    private static Result regexSearch(
            String text, String query, boolean caseSensitive, boolean wholeWord, long budgetNanos, int limit) {
        if (limit <= 0 || Thread.currentThread().isInterrupted()) {
            return new Result(List.of(), true);
        }
        // An empty text is one empty line, which a MULTILINE ^ cannot match (see compileRegex).
        Pattern p = compile(query, caseSensitive, wholeWord, !text.isEmpty());
        if (p == null) {
            return new Result(List.of(), true);
        }
        List<int[]> out = new ArrayList<>();
        Matcher matcher = p.matcher(new Deadline(text, budgetNanos));
        int from = 0;
        try {
            while (from <= text.length() && matcher.find(from)) {
                if (Thread.currentThread().isInterrupted()) {
                    return new Result(out, false);
                }
                int start = matcher.start();
                int end = matcher.end();
                out.add(new int[] {start, end});
                if (out.size() >= limit) {
                    return new Result(out, true);
                }
                from = end > start ? end : end + 1; // advance past a zero-width match
            }
        } catch (MatchBudgetExceededException | StackOverflowError aborted) {
            // Budget exceeded, or java.util.regex recursed once per repetition of a group (`(.|\n)*?` over
            // a couple of thousand characters) and ran out of stack. Partial results beat freezing the UI
            // or an Error escaping onto the FX / search thread.
            return new Result(out, false);
        }
        return new Result(out, true);
    }

    /**
     * Thrown by {@link Deadline} to unwind a runaway backtracking match once its wall-clock budget passes.
     * The incremental search catches it and keeps its partial results; the find bar's Replace All catches it
     * and abandons the whole replace (a half-applied replacement would corrupt the buffer).
     */
    public static final class MatchBudgetExceededException extends RuntimeException {
        MatchBudgetExceededException() {
            super(null, null, false, false); // no message/stacktrace — it's control flow, not an error
        }
    }

    /**
     * A read-only {@link CharSequence} view of the text that throws {@link MatchBudgetExceededException} from
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
            if ((++ticks & 0x3FF) == 0
                    && (Thread.currentThread().isInterrupted() || System.nanoTime() > deadlineNanos)) {
                throw new MatchBudgetExceededException();
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
     * Whole-word test for a literal match: the character immediately before the match and the one
     * immediately after it must not be word characters. These are ripgrep {@code -w}'s half boundaries, and
     * the regex path wraps its pattern in the same two lookarounds, so the find bar, replace and project
     * search agree in both modes. Unlike {@code \b…\b} this does not look at the query's own edge
     * characters, so a query that starts or ends with punctuation ({@code @Override}, {@code $var},
     * {@code run()}, {@code --flag}) matches wherever it is not glued to a word.
     */
    private static boolean isWordBounded(String text, int start, int end) {
        boolean beforeWord = start > 0 && isWordChar(text.charAt(start - 1));
        boolean afterWord = end < text.length() && isWordChar(text.charAt(end));
        return !beforeWord && !afterWord;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
