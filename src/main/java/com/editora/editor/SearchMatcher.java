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

    // --- bounded results for the find bar ---------------------------------------------------------

    /**
     * What the find bar searches for. {@code scopeStart}/{@code scopeEnd} restrict the result to matches
     * lying wholly inside {@code [scopeStart, scopeEnd)} (find in selection); -1/-1 is the whole document.
     * The scope filters matches found by searching the whole text — it is not a search of the substring —
     * so anchors and lookarounds resolve against the real document and the non-overlapping matches are the
     * same ones a whole-document search highlights.
     */
    public record Query(
            String text, boolean caseSensitive, boolean regex, boolean wholeWord, int scopeStart, int scopeEnd) {

        public Query(String text, boolean caseSensitive, boolean regex, boolean wholeWord) {
            this(text, caseSensitive, regex, wholeWord, -1, -1);
        }

        boolean scoped() {
            return scopeStart >= 0 && scopeEnd > scopeStart;
        }

        boolean inScope(int start, int end) {
            return !scoped() || (start >= scopeStart && end <= scopeEnd);
        }
    }

    /** Receives each match in document order; returns false to stop the search. */
    @FunctionalInterface
    interface Sink {
        boolean match(int start, int end);
    }

    /** The most matches the find bar holds at once (see {@link #around}). */
    public static final int DEFAULT_PAGE = 100_000;

    /**
     * Feeds every in-scope match of {@code q} to {@code sink} until it declines one. Returns false when the
     * search was abandoned part-way (a regex out of time or stack) rather than finished or stopped.
     */
    static boolean scan(String text, Query q, Sink sink) {
        if (text == null || q.text() == null || q.text().isEmpty()) {
            return true;
        }
        Sink scoped = !q.scoped()
                ? sink
                // A match starting past the scope ends the search: nothing after it can be inside.
                : (start, end) -> q.inScope(start, end) ? sink.match(start, end) : start <= q.scopeEnd();
        if (q.regex()) {
            return scanRegex(text, q.text(), q.caseSensitive(), q.wholeWord(), DEFAULT_MATCH_BUDGET_NANOS, scoped);
        }
        scanLiteral(text, q.text(), q.caseSensitive(), q.wholeWord(), scoped);
        return true;
    }

    /** Every in-scope match of {@code q}, held compactly. For Replace All, which has to rewrite them all. */
    public static SearchMatches all(String text, Query q) {
        IntPairs found = new IntPairs(Integer.MAX_VALUE);
        boolean complete = scan(text, q, (start, end) -> {
            found.add(start, end);
            return true;
        });
        return new SearchMatches(found.toArray(found.size()), found.size(), 0, false, complete);
    }

    /**
     * At most {@code cap} matches of {@code q} around {@code from}: the nearest ones before it — up to half
     * the page, more when few follow — and the ones starting at or after it that fill the rest. A result
     * under the cap is simply every match; a longer one is the page next to {@code from}, which knows how
     * many matches precede it and whether more follow. The search stops as soon as the page is full, so a
     * dense query costs the text up to {@code from} plus a bounded stretch after it, and memory proportional
     * to {@code cap} — never to the number of matches in the document.
     */
    public static SearchMatches around(String text, Query q, int from, int cap) {
        int limit = Math.max(2, cap);
        IntPairs before = new IntPairs(limit); // a ring: only the nearest `limit` before `from` survive
        IntPairs after = new IntPairs(limit);
        long[] seenBefore = new long[1];
        boolean[] more = new boolean[1];
        boolean complete = scan(text, q, (start, end) -> {
            if (start < from) {
                before.add(start, end);
                seenBefore[0]++;
                return true;
            }
            // Matches arrive in order, so every one before `from` has been seen by now: they keep at most
            // half the page, and what they do not use goes to the ones ahead.
            if (after.size() == limit - Math.min(before.size(), limit / 2)) {
                more[0] = true;
                return false;
            }
            after.add(start, end);
            return true;
        });
        int keep = Math.min(before.size(), limit - after.size());
        int[] offsets = new int[2 * (keep + after.size())];
        before.copyLast(keep, offsets, 0);
        after.copyLast(after.size(), offsets, 2 * keep);
        return new SearchMatches(offsets, keep + after.size(), seenBefore[0] - keep, more[0], complete);
    }

    /** A page of matches and the index in it of the match that was asked for (-1 when there are none). */
    public record Located(SearchMatches matches, int index) {}

    /**
     * The match to jump to from {@code from} — forward: the first one starting at or after it, else the
     * document's first; backward: the last one starting before it, else the document's last — together with
     * the page of matches around it. This is {@link #nextIndex} for a result too long to hold: wrapping
     * around searches again from the other end instead of indexing a list of everything.
     */
    public static Located locate(String text, Query q, int from, boolean forward, int cap) {
        SearchMatches page = around(text, q, from, cap);
        if (page.isEmpty()) {
            return new Located(page, -1);
        }
        int at = page.firstStartingAtOrAfter(from);
        if (forward) {
            if (at < page.size()) {
                return new Located(page, at);
            }
            if (page.before() > 0) {
                page = around(text, q, 0, cap); // wrap: the page that starts the document
            }
            return new Located(page, page.isEmpty() ? -1 : 0);
        }
        if (at > 0) {
            return new Located(page, at - 1);
        }
        if (page.moreAfter()) {
            page = around(text, q, Integer.MAX_VALUE, cap); // wrap: the page that ends the document
        }
        return new Located(page, page.size() - 1);
    }

    /**
     * Whether {@code [start, end)} is one of {@code q}'s matches. Reads the text only up to {@code start}
     * and builds no result.
     */
    public static boolean isMatch(String text, Query q, int start, int end) {
        boolean[] found = new boolean[1];
        scan(text, q, (s, e) -> {
            found[0] = s == start && e == end;
            return s < start;
        });
        return found[0];
    }

    /** Growable start/end pairs; past {@code capacity} pairs it keeps only the newest (a ring). */
    private static final class IntPairs {
        private final int capacity;
        private int[] data = new int[32];
        private int size;
        private int head; // index of the oldest pair once the ring is full

        IntPairs(int capacity) {
            this.capacity = capacity;
        }

        int size() {
            return size;
        }

        void add(int start, int end) {
            if (size == capacity) { // full: overwrite the oldest
                data[2 * head] = start;
                data[2 * head + 1] = end;
                head = (head + 1) % capacity;
                return;
            }
            if (2 * size == data.length) {
                long grown = Math.min(2L * capacity, 2L * data.length);
                data = java.util.Arrays.copyOf(data, (int) Math.min(grown, Integer.MAX_VALUE - 8));
            }
            data[2 * size] = start;
            data[2 * size + 1] = end;
            size++;
        }

        /** Copies the newest {@code count} pairs, oldest first, into {@code target} at {@code at}. */
        void copyLast(int count, int[] target, int at) {
            for (int i = size - count; i < size; i++) {
                int from = 2 * ((head + i) % Math.max(1, size));
                target[at++] = data[from];
                target[at++] = data[from + 1];
            }
        }

        int[] toArray(int count) {
            int[] out = new int[2 * count];
            copyLast(count, out, 0);
            return out;
        }
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
        if (limit <= 0) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        scanLiteral(text, query, caseSensitive, wholeWord, (start, end) -> {
            out.add(new int[] {start, end});
            return out.size() < limit;
        });
        return out;
    }

    private static void scanLiteral(String text, String query, boolean caseSensitive, boolean wholeWord, Sink sink) {
        if (Thread.currentThread().isInterrupted()) {
            return;
        }
        // regionMatches folds per character, so it cannot match a case pair of different lengths (ß↔SS,
        // ﬁ↔FI). Take the full-folding path only when one side actually contains such a character — the
        // check is one comparison per char and rejects all ASCII, so ordinary code pays nothing (#444).
        if (!caseSensitive) {
            boolean expandingQuery = CaseFold.mayExpand(query);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            boolean expandingText = !expandingQuery && CaseFold.mayExpand(text);
            if (Thread.currentThread().isInterrupted()) {
                return;
            }
            if (expandingQuery || expandingText) {
                scanFolded(text, query, wholeWord, sink);
                return;
            }
        }
        int n = text.length();
        int m = query.length();
        for (int i = 0; i + m <= n; ) {
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                return;
            }
            if (text.regionMatches(!caseSensitive, i, query, 0, m)) {
                int end = i + m;
                if (!wholeWord || isWordBounded(text, i, end)) {
                    if (!sink.match(i, end)) {
                        return;
                    }
                    i = end; // non-overlapping
                    continue;
                }
            }
            i++;
        }
    }

    /**
     * Case-insensitive literal matching under <b>full</b> case folding, so a length-changing fold matches in
     * both directions. Offsets are the original text's throughout — {@link CaseFold#matchAt} folds on the fly
     * rather than searching a folded copy, so there is no index map to translate back through.
     */
    private static void scanFolded(String text, String query, boolean wholeWord, Sink sink) {
        String folded = CaseFold.fold(query);
        if (folded.isEmpty()) {
            return;
        }
        int n = text.length();
        for (int i = 0; i < n; ) {
            if ((i & 0x3FF) == 0 && Thread.currentThread().isInterrupted()) {
                return;
            }
            int end = CaseFold.matchAt(text, i, folded);
            if (end > i && (!wholeWord || isWordBounded(text, i, end))) {
                if (!sink.match(i, end)) {
                    return;
                }
                i = end; // non-overlapping
                continue;
            }
            i += Character.charCount(text.codePointAt(i));
        }
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
        List<int[]> out = new ArrayList<>();
        boolean complete = scanRegex(text, query, caseSensitive, wholeWord, budgetNanos, (start, end) -> {
            out.add(new int[] {start, end});
            return out.size() < limit;
        });
        return new Result(out, complete);
    }

    /** Feeds each regex match to {@code sink}; false when the search was abandoned rather than finished. */
    private static boolean scanRegex(
            String text, String query, boolean caseSensitive, boolean wholeWord, long budgetNanos, Sink sink) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        // An empty text is one empty line, which a MULTILINE ^ cannot match (see compileRegex).
        Pattern p = compile(query, caseSensitive, wholeWord, !text.isEmpty());
        if (p == null) {
            return true;
        }
        Matcher matcher = p.matcher(new Deadline(text, budgetNanos));
        int from = 0;
        try {
            while (from <= text.length() && matcher.find(from)) {
                if (Thread.currentThread().isInterrupted()) {
                    return false;
                }
                int start = matcher.start();
                int end = matcher.end();
                if (!sink.match(start, end)) {
                    return true;
                }
                from = end > start ? end : end + 1; // advance past a zero-width match
            }
        } catch (MatchBudgetExceededException | StackOverflowError aborted) {
            // Budget exceeded, or java.util.regex recursed once per repetition of a group (`(.|\n)*?` over
            // a couple of thousand characters) and ran out of stack. Partial results beat freezing the UI
            // or an Error escaping onto the FX / search thread.
            return false;
        }
        return true;
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
