package com.editora.search;

import java.util.ArrayList;
import java.util.List;

/**
 * Ranked fuzzy matching for the pickers — the shared answer to "does this candidate match what was typed,
 * and how well?". Pure and unit-tested.
 *
 * <p>The pickers historically asked only the first half of that question ({@code CommandPalette.isSubsequence},
 * a boolean), so a scattered accidental match ranked alongside a clean prefix hit. {@link #of} returns a
 * {@link Match} carrying a relative {@code score} <em>and</em> the matched character runs, so a caller can
 * both order its results and embolden the characters responsible.
 *
 * <p><b>Scoring.</b> Every matched character earns {@link #SCORE_MATCH}; on top of that a character earns a
 * bonus for landing on a word boundary (start of string, after a separator, a camelCase hump, a
 * letter&rarr;digit transition) or for continuing a run begun by the previous one. Non-adjacent steps are
 * penalized, and the whole match is penalized for how far it is spread and for how much of the candidate
 * precedes it. The net effect is the ordering people expect: an exact prefix beats an acronym beats a
 * scattered subsequence, and {@code mc} finds {@code MainController} over {@code MyMacro} because it lands
 * on two humps rather than inside one word.
 *
 * <p><b>Best alignment, not the first one.</b> The match is computed by dynamic programming over the
 * candidate rather than greedily left-to-right. A greedy scan takes the first place each query character
 * fits, which is not usually the best: for {@code mc} against {@code MyMacController} it would take the
 * {@code c} inside "Mac" and miss the {@code C} hump that the user meant.
 *
 * <p><b>Multiple terms.</b> Whitespace splits the query into terms that must <em>all</em> match, in any
 * order — so {@code "toggle git"} finds "Git: Toggle Blame", which a single ordered subsequence cannot.
 *
 * <p><b>Cost.</b> This runs on the FX thread on every keystroke over an entire candidate list (the command
 * registry alone is ~550 entries), so a non-match must be cheap: each term first runs an O(n) subsequence
 * scan and returns immediately if the characters are not even present, and only a surviving candidate
 * allocates the matrices. The scan also narrows the region the matrices have to cover to the span actually
 * capable of holding the match. The work is therefore self-limiting as the query grows: a one-character
 * query matches nearly everything but has a one-row matrix, while a long query has a tall matrix but few
 * survivors.
 *
 * <p>Measured over the 1451 paths of this repository's {@code src/}, scoring <em>every</em> candidate on
 * every keystroke: 0.26 ms for a one-character query, peaking at 1.16 ms for a seven-character one, and
 * 0.29 ms for a query that matches almost nothing. It plateaus around a millisecond rather than growing
 * with query length, which is the self-limiting behaviour above showing up in the numbers — comfortably
 * inside a 16 ms frame, with the whole candidate list rescored from scratch each time.
 *
 * <p><b>Case is folded per character against the original string</b>, never by matching a lowercased copy —
 * {@code String.toLowerCase} is not length-preserving ({@code "İ"} U+0130 becomes two characters), so a
 * copy's indices drift past such a character and the returned ranges would overrun the string the caller
 * then substrings. The same rule is documented on {@code completion/MatchHighlighter}.
 */
public final class FuzzyMatch {

    private FuzzyMatch() {}

    /**
     * A successful match: a relative {@code score} (higher is better) and the half-open {@code [start,end)}
     * character ranges of the candidate that matched, in ascending order with adjacent runs coalesced.
     *
     * <p>Scores are only comparable between candidates scored against the <em>same</em> query. A caller
     * that wants to bias results — by recency, by frecency, by an LSP server's own ordering — adds its own
     * term to this score rather than asking for it here.
     */
    public record Match(int score, int[][] ranges) {}

    /** Base value of a single matched character. */
    static final int SCORE_MATCH = 16;
    /** Start of the string, or the character after a non-alphanumeric separator. */
    static final int BONUS_BOUNDARY = 10;
    /** A camelCase hump, or the first digit of a run. */
    static final int BONUS_CAMEL = 8;
    /**
     * The <em>first query character</em>'s bonus counts this many times, wherever in the candidate it
     * lands — how strongly that character anchors the match matters more than any later one.
     *
     * <p>Note it is emphatically not a bonus for sitting at index 0 of the candidate. That was the first
     * shape of this, and it is wrong for the command palette, whose titles are "Category: Verb": it made
     * "Undo History" outrank "Edit: Undo" for the query {@code undo}, because the first title happens to
     * begin with the word while the second merely contains it as its whole verb. There is no reason to
     * believe the user meant the tool window.
     */
    static final int BONUS_FIRST_MULTIPLIER = 2;
    /** Continuing the run begun by the previous matched character. */
    static final int BONUS_CONSECUTIVE = 12;
    /** The typed character had the same case as the candidate's — a tiebreak, deliberately small. */
    static final int BONUS_EXACT_CASE = 1;
    /** A step that is not adjacent to the previous matched character. */
    static final int PENALTY_GAP = -5;
    /** Per character of slack between the first and last matched character. */
    static final int PENALTY_SPREAD = -1;
    /** Awarded when a path's match lies wholly within its final segment. See {@link #ofPath}. */
    static final int BONUS_BASENAME = 48;

    /** Longest candidate the matrices are built over; a longer candidate is matched on its tail. */
    static final int MAX_SCAN = 400;

    /**
     * Scores {@code query} against {@code candidate}, or returns {@code null} when it does not match.
     *
     * <p>A blank query returns {@code null} rather than a neutral match: "show everything" is the caller's
     * decision, and every current caller already special-cases the empty field before filtering.
     */
    public static Match of(String candidate, String query) {
        List<int[]> spans = new ArrayList<>();
        int total = run(candidate, query, spans);
        if (total == NO_MATCH) {
            return null;
        }
        return new Match(total, coalesce(spans, tailOffset(candidate)));
    }

    /**
     * The score {@link #of} would report, or {@link #NO_SCORE} when it would not match — without building
     * the highlight ranges.
     *
     * <p>Exists because the ranges are the expensive part and the bulk callers <em>throw them away</em>:
     * {@code SymbolIndex.Hit} and the file-search hit both carry a score and nothing else, and the picker
     * re-derives the highlight at render time (see {@code MatchText}) for the ~40 rows it actually draws.
     * So a corpus-wide scan was allocating a span list, a per-character {@code int[]}, a coalesced
     * {@code int[][]} and a {@link Match} for every one of tens of thousands of candidates, to serve
     * forty (#876).
     *
     * <p>Scores are identical to {@code of(...).score()} by construction — same code path, spans simply not
     * collected — and {@code FuzzyMatchTest} pins that against drift.
     */
    public static int scoreOf(String candidate, String query) {
        return run(candidate, query, null);
    }

    /** Scoring core shared by {@link #of} and {@link #scoreOf}; fills {@code spans} when it is non-null. */
    private static int run(String candidate, String query, List<int[]> spans) {
        if (candidate == null || candidate.isEmpty() || query == null) {
            return NO_MATCH;
        }
        String q = query.strip();
        if (q.isEmpty()) {
            return NO_MATCH;
        }
        // A candidate longer than the scan cap is matched on its tail: for the two things that get long —
        // a path and a symbol's qualified name — the distinguishing part is at the end.
        int offset = tailOffset(candidate);
        String s = offset == 0 ? candidate : candidate.substring(offset);

        int total = 0;
        boolean matched = false;
        int from = 0;
        while (from < q.length()) {
            while (from < q.length() && Character.isWhitespace(q.charAt(from))) {
                from++;
            }
            if (from >= q.length()) {
                break;
            }
            int to = from;
            while (to < q.length() && !Character.isWhitespace(q.charAt(to))) {
                to++;
            }
            int termScore = matchTerm(s, q, from, to, spans);
            if (termScore == NO_MATCH) {
                return NO_MATCH; // every term must match
            }
            total += termScore;
            matched = true;
            from = to;
        }
        return matched ? total : NO_MATCH;
    }

    /** Where the matched window starts in {@code candidate} once the {@link #MAX_SCAN} cap is applied. */
    private static int tailOffset(String candidate) {
        return Math.max(0, candidate.length() - MAX_SCAN);
    }

    /**
     * As {@link #of}, but aware that {@code path} is a path: a match lying wholly inside the final segment
     * scores {@link #BONUS_BASENAME} higher, so typing a file's name ranks it above files that merely live
     * in a directory of that name.
     *
     * <p>The basename is tried first and the full path only as a fallback, which is what lets a query
     * spanning both ({@code "ui main"}) still find {@code ui/MainController.java} while a plain
     * {@code "main"} is never dragged down by the directories above it.
     */
    public static Match ofPath(String path, String query) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (cut >= 0 && cut + 1 < path.length()) {
            Match base = of(path.substring(cut + 1), query);
            if (base != null) {
                int[][] shifted = new int[base.ranges().length][];
                for (int i = 0; i < shifted.length; i++) {
                    shifted[i] = new int[] {base.ranges()[i][0] + cut + 1, base.ranges()[i][1] + cut + 1};
                }
                return new Match(base.score() + BONUS_BASENAME, shifted);
            }
        }
        return of(path, query);
    }

    /**
     * The score {@link #ofPath} would report, or {@link #NO_SCORE} — the {@link #scoreOf} counterpart, and
     * the one the project-file scan runs per keystroke.
     */
    public static int scoreOfPath(String path, String query) {
        if (path == null || path.isEmpty()) {
            return NO_MATCH;
        }
        int cut = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (cut >= 0 && cut + 1 < path.length()) {
            int base = scoreOf(path.substring(cut + 1), query);
            if (base != NO_MATCH) {
                return base + BONUS_BASENAME;
            }
        }
        return scoreOf(path, query);
    }

    /** Returned by {@link #matchTerm} when the term is not present in the candidate at all. */
    private static final int NO_MATCH = Integer.MIN_VALUE;

    /** What {@link #scoreOf} and {@link #scoreOfPath} return for a candidate that does not match. */
    public static final int NO_SCORE = NO_MATCH;

    /**
     * A matrix cell no path reaches. Never added to — every use is guarded by an equality check first —
     * so it cannot underflow into a spuriously attractive score.
     */
    private static final int UNREACHABLE = Integer.MIN_VALUE;

    /**
     * Matches one whitespace-delimited term of the query against {@code s}, appending one span per matched
     * character to {@code spans} and returning the term's score. Returns {@link #NO_MATCH} — leaving
     * {@code spans} untouched — when the term does not match.
     */
    private static int matchTerm(String s, String query, int qStart, int qEnd, List<int[]> spans) {
        // spans may be null — the scoreOf path wants the score without paying for the ranges.
        int m = qEnd - qStart;
        int n = s.length();
        if (m > n) {
            return NO_MATCH;
        }

        // Cheap rejection: a plain forward subsequence scan. This is what keeps a non-matching candidate
        // from ever allocating a matrix, which is the whole cost story for a picker filtering on every
        // keystroke.
        boolean present = false;
        for (int i = 0, j = 0; j < n; j++) {
            if (eq(s.charAt(j), query.charAt(qStart + i)) && ++i == m) {
                present = true;
                break;
            }
        }
        if (!present) {
            return NO_MATCH;
        }

        // The window the matrices have to cover. Deliberately NOT the greedy scan's own span: greedy finds
        // the *earliest* end, and the best alignment frequently ends later. For `mc` against
        // "MyMacController" greedy stops at the `c` in "Mac" and would fence out the `C` at the hump that
        // is the better answer — the exact bug this matcher exists to avoid, reintroduced by the
        // optimization. The sound bounds are the first place the first character can sit and the last
        // place the last one can.
        int lo = 0;
        while (lo < n && !eq(s.charAt(lo), query.charAt(qStart))) {
            lo++;
        }
        int hi = n;
        while (hi > lo && !eq(s.charAt(hi - 1), query.charAt(qEnd - 1))) {
            hi--;
        }

        int w = hi - lo;
        int[][] best = new int[m][w]; // best[i][j] = score of matching query[..i] with query[i] at lo+j
        int[][] from = new int[m][w]; // the column of query[i-1] that produced it, or -1
        for (int i = 0; i < m; i++) {
            java.util.Arrays.fill(best[i], UNREACHABLE);
        }

        for (int i = 0; i < m; i++) {
            char qc = query.charAt(qStart + i);
            int runningBest = UNREACHABLE; // max over previous row, strictly left of j-1
            int runningIdx = -1;
            for (int j = 0; j < w; j++) {
                if (i > 0 && j >= 2) {
                    int cand = best[i - 1][j - 2];
                    if (cand > runningBest) {
                        runningBest = cand;
                        runningIdx = j - 2;
                    }
                }
                int abs = lo + j;
                char sc = s.charAt(abs);
                if (!eq(sc, qc)) {
                    continue;
                }
                int bonus = bonusAt(s, abs);
                int exact = sc == qc ? BONUS_EXACT_CASE : 0;
                if (i == 0) {
                    best[i][j] = SCORE_MATCH + bonus * BONUS_FIRST_MULTIPLIER + exact;
                    from[i][j] = -1;
                    continue;
                }
                int gain = SCORE_MATCH + bonus + exact;
                // Either continue the run from j-1, or jump from the best earlier column (<= j-2).
                int consec = j >= 1 && best[i - 1][j - 1] != UNREACHABLE
                        ? best[i - 1][j - 1] + BONUS_CONSECUTIVE
                        : UNREACHABLE;
                int jump = runningBest != UNREACHABLE ? runningBest + PENALTY_GAP : UNREACHABLE;
                if (consec == UNREACHABLE && jump == UNREACHABLE) {
                    continue; // unreachable at this column
                }
                if (consec >= jump) {
                    best[i][j] = consec + gain;
                    from[i][j] = j - 1;
                } else {
                    best[i][j] = jump + gain;
                    from[i][j] = runningIdx;
                }
            }
        }

        int endCol = -1;
        int endScore = UNREACHABLE;
        for (int j = 0; j < w; j++) {
            if (best[m - 1][j] > endScore) {
                endScore = best[m - 1][j];
                endCol = j;
            }
        }
        if (endCol < 0 || endScore == UNREACHABLE) {
            return NO_MATCH; // the narrowing scan proved a match exists, so this is unreachable in practice
        }

        int[] cols = new int[m];
        for (int i = m - 1, j = endCol; i >= 0; i--) {
            cols[i] = j;
            j = from[i][j];
        }
        if (spans != null) {
            for (int i = 0; i < m; i++) {
                spans.add(new int[] {lo + cols[i], lo + cols[i] + 1});
            }
        }
        int spread = cols[m - 1] - cols[0] + 1 - m;
        return endScore + spread * PENALTY_SPREAD;
    }

    /**
     * Bonus for matching at {@code j} — the signal that separates a match a human would call meaningful
     * from one that merely lands mid-word. Read from the original string so the character before the
     * matrix window is still visible.
     */
    private static int bonusAt(String s, int j) {
        char c = s.charAt(j);
        if (j == 0) {
            return BONUS_BOUNDARY;
        }
        char prev = s.charAt(j - 1);
        if (!Character.isLetterOrDigit(prev)) {
            return BONUS_BOUNDARY;
        }
        if (Character.isLowerCase(prev) && Character.isUpperCase(c)) {
            return BONUS_CAMEL;
        }
        if (Character.isDigit(c) && !Character.isDigit(prev)) {
            return BONUS_CAMEL;
        }
        return 0;
    }

    /** Sorts the per-character spans and merges touching/overlapping ones into runs, shifted by {@code offset}. */
    private static int[][] coalesce(List<int[]> spans, int offset) {
        spans.sort((a, b) -> Integer.compare(a[0], b[0]));
        List<int[]> out = new ArrayList<>();
        for (int[] span : spans) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && span[0] <= last[1]) {
                last[1] = Math.max(last[1], span[1]);
            } else {
                out.add(new int[] {span[0], span[1]});
            }
        }
        if (offset != 0) {
            for (int[] r : out) {
                r[0] += offset;
                r[1] += offset;
            }
        }
        return out.toArray(new int[0][]);
    }

    private static boolean eq(char a, char b) {
        return a == b || Character.toLowerCase(a) == Character.toLowerCase(b);
    }
}
