package com.editora.ui;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.jspecify.annotations.Nullable;

/**
 * The text of each file as the debugged program was built from it, and the line mapping between that text and
 * the document as it is now (B1).
 *
 * <p>A running JVM keeps the line numbers of the code it loaded. Edit a file during a session — one line typed
 * at the top — and its breakpoint markers move with their text while the program does not: Editora sent the
 * moved lines, so the breakpoints landed on other statements (or on none, and never stopped), and a stop on the
 * program's line 7 was painted on the document's line 7, a different statement. So breakpoint lines go to the
 * adapter in the launched text's numbering, and frame and breakpoint lines come back through the same mapping.
 * A successful hot code replace makes the current text the launched one ({@link #rebaseline}).
 *
 * <p>Pure apart from the cache: callers give it the current text; nothing here reads a buffer or a file.
 */
final class LaunchedLines {

    /** The launched text of each file of the session, split into lines; keyed by {@link DebugCoordinator#key}. */
    private final Map<String, List<String>> launched = new HashMap<>();

    /**
     * The last mapping computed per file, with the text it was computed for. Keyed by the text, not by the
     * buffer's version counter: a breakpoint shift is reported while the edit is being applied, before that
     * counter moves.
     */
    private final Map<String, Cached> cache = new HashMap<>();

    private record Cached(String text, Mapping mapping) {}

    /** Forgets every file (a session ended, or a new one is starting). */
    void clear() {
        launched.clear();
        cache.clear();
    }

    boolean isEmpty() {
        return launched.isEmpty();
    }

    /** Records {@code text} as what {@code file}'s program was built from, unless a text is already recorded. */
    void snapshotIfAbsent(Path file, String text) {
        if (file != null && text != null) {
            launched.putIfAbsent(DebugCoordinator.key(file), lines(text));
        }
    }

    /** The program now runs {@code text} for {@code file} (a hot code replace succeeded): map from it from now on. */
    void rebaseline(Path file, String text) {
        if (file != null && text != null) {
            String key = DebugCoordinator.key(file);
            launched.put(key, lines(text));
            cache.remove(key);
        }
    }

    boolean has(Path file) {
        return file != null && launched.containsKey(DebugCoordinator.key(file));
    }

    /**
     * The mapping between {@code file}'s launched text and {@code currentText} (the document now), or {@link
     * Mapping#IDENTITY} when there is no launched text or nothing changed.
     */
    Mapping mapping(Path file, String currentText) {
        if (file == null) {
            return Mapping.IDENTITY;
        }
        String key = DebugCoordinator.key(file);
        List<String> before = launched.get(key);
        if (before == null) {
            return Mapping.IDENTITY;
        }
        Cached hit = cache.get(key);
        if (hit != null && hit.text().equals(currentText)) {
            return hit.mapping();
        }
        Mapping mapping = Mapping.between(before, lines(currentText));
        cache.put(key, new Cached(currentText, mapping));
        return mapping;
    }

    private static List<String> lines(String text) {
        return Arrays.asList(text.split("\n", -1));
    }

    /**
     * Line numbers between two versions of a file (0-based). {@code toLaunched} of a line added since is -1;
     * {@code toCurrent} of a line deleted since is the next line that survived (-1 past the end).
     */
    static final class Mapping {

        /** No change: every line maps to itself. */
        static final Mapping IDENTITY = new Mapping(null, null);

        private final int @Nullable [] currentToLaunched;
        private final int @Nullable [] launchedToCurrent;

        private Mapping(int @Nullable [] currentToLaunched, int @Nullable [] launchedToCurrent) {
            this.currentToLaunched = currentToLaunched;
            this.launchedToCurrent = launchedToCurrent;
        }

        /** Whether the document differs from the launched text. */
        boolean changed() {
            return currentToLaunched != null;
        }

        int toLaunched(int currentLine) {
            if (currentToLaunched == null) {
                return currentLine;
            }
            return currentLine >= 0 && currentLine < currentToLaunched.length ? currentToLaunched[currentLine] : -1;
        }

        int toCurrent(int launchedLine) {
            if (launchedToCurrent == null) {
                return launchedLine;
            }
            return launchedLine >= 0 && launchedLine < launchedToCurrent.length ? launchedToCurrent[launchedLine] : -1;
        }

        static Mapping between(List<String> launched, List<String> current) {
            if (launched.equals(current)) {
                return IDENTITY;
            }
            int[] toLaunched = new int[current.size()];
            int[] toCurrent = new int[launched.size()];
            Arrays.fill(toLaunched, -1);
            Arrays.fill(toCurrent, -1);
            Patch<String> patch = DiffUtils.diff(launched, current);
            int l = 0;
            int c = 0;
            for (AbstractDelta<String> delta : patch.getDeltas()) {
                int ls = delta.getSource().getPosition();
                int cs = delta.getTarget().getPosition();
                while (l < ls && c < cs) { // the equal stretch before the change
                    toLaunched[c] = l;
                    toCurrent[l] = c;
                    l++;
                    c++;
                }
                // A line edited in place (a typo fixed, a comment added), re-indented or wrapped in a block is still
                // the launched line: its code is in the running program (review 2, B104; review 3, B203).
                int[] pairs = pairByContent(
                        delta.getSource().getLines(), delta.getTarget().getLines());
                for (int k = 0; k < pairs.length; k++) {
                    if (pairs[k] >= 0) {
                        toLaunched[cs + pairs[k]] = ls + k;
                        toCurrent[ls + k] = cs + pairs[k];
                    }
                }
                // Two lines joined into one: the second launched line is in the joined line too.
                for (int k = 1; k < pairs.length; k++) {
                    int joined = toCurrent[ls + k - 1];
                    if (pairs[k] < 0 && joined >= 0 && contains(current.get(joined), launched.get(ls + k))) {
                        toCurrent[ls + k] = joined;
                    }
                }
                l = ls + delta.getSource().size();
                c = cs + delta.getTarget().size();
            }
            while (l < launched.size() && c < current.size()) {
                toLaunched[c] = l;
                toCurrent[l] = c;
                l++;
                c++;
            }
            // a launched line that is gone: the next one that survived, as a breakpoint on it would move
            int next = -1;
            for (int i = toCurrent.length - 1; i >= 0; i--) {
                if (toCurrent[i] >= 0) {
                    next = toCurrent[i];
                } else {
                    toCurrent[i] = next;
                }
            }
            return new Mapping(toLaunched, toCurrent);
        }

        /** Above this many line pairs in one changed region, only equal lines are paired by content. */
        private static final int FUZZY_PAIRS = 2_500;

        /** Above this many, the region is paired from the top (B104's rule), as diffing it would cost too much. */
        private static final int EXACT_PAIRS = 1_000_000;

        /**
         * Pure: which line of {@code after} each line of {@code before} became, inside one changed region (-1:
         * gone). Lines are paired by content, in order (review 3, B203): the same text once trimmed (re-indented,
         * wrapped in a block), else a similar line (a typo fixed, a value changed) — the pairing with the most
         * likeness wins. A region where nothing is alike — a line rewritten — pairs from the top, as before: the
         * running program still has that line's code.
         */
        static int[] pairByContent(List<String> before, List<String> after) {
            int m = before.size();
            int n = after.size();
            int[] out = new int[m];
            Arrays.fill(out, -1);
            if (m == 0 || n == 0) {
                return out;
            }
            long size = (long) m * n;
            if (size <= EXACT_PAIRS) {
                boolean fuzzy = size <= FUZZY_PAIRS;
                String[] a = before.stream().map(String::strip).toArray(String[]::new);
                String[] b = after.stream().map(String::strip).toArray(String[]::new);
                double[][] score = new double[m + 1][n + 1]; // best likeness pairing a[i..] with b[j..]
                for (int i = m - 1; i >= 0; i--) {
                    for (int j = n - 1; j >= 0; j--) {
                        double best = Math.max(score[i + 1][j], score[i][j + 1]);
                        double like = likeness(a[i], b[j], fuzzy);
                        if (like > 0) {
                            best = Math.max(best, like + score[i + 1][j + 1]);
                        }
                        score[i][j] = best;
                    }
                }
                boolean any = false;
                for (int i = 0, j = 0; i < m && j < n; ) {
                    double like = likeness(a[i], b[j], fuzzy);
                    if (like > 0 && score[i][j] == like + score[i + 1][j + 1]) {
                        out[i++] = j++;
                        any = true;
                    } else if (score[i][j] == score[i + 1][j]) {
                        i++;
                    } else {
                        j++;
                    }
                }
                if (any) {
                    return out;
                }
            }
            for (int k = 0; k < Math.min(m, n); k++) {
                out[k] = k;
            }
            return out;
        }

        /**
         * Pure: how alike two trimmed lines are, 0 when not enough to be the same line. Equal text counts most; a
         * blank line little (blank lines are everywhere); then one line holding the other ({@code c();} wrapped as
         * {@code if (ok) c();}), or an edit distance under half the longer line.
         */
        static double likeness(String a, String b, boolean fuzzy) {
            if (a.equals(b)) {
                return a.isEmpty() ? 0.25 : 2;
            }
            if (!fuzzy || a.isEmpty() || b.isEmpty()) {
                return 0;
            }
            if (Math.min(a.length(), b.length()) >= 3 && (a.contains(b) || b.contains(a))) {
                return 0.75;
            }
            int longer = Math.max(a.length(), b.length());
            if (Math.abs(a.length() - b.length()) * 2 >= longer) {
                return 0; // the distance is at least half the longer line
            }
            double distance = (double) distance(a, b, longer / 2) / longer;
            return distance < 0.5 ? 1 - distance : 0;
        }

        /** Levenshtein distance between {@code a} and {@code b}, or {@code limit + 1} once it is over {@code limit}. */
        private static int distance(String a, String b, int limit) {
            int[] prev = new int[b.length() + 1];
            int[] cur = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) {
                prev[j] = j;
            }
            for (int i = 1; i <= a.length(); i++) {
                cur[0] = i;
                int rowMin = cur[0];
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                    rowMin = Math.min(rowMin, cur[j]);
                }
                if (rowMin > limit) {
                    return limit + 1;
                }
                int[] t = prev;
                prev = cur;
                cur = t;
            }
            return prev[b.length()];
        }

        /** Whether the current line {@code joined} holds the launched line {@code part} (two lines joined). */
        private static boolean contains(String joined, String part) {
            String p = part.strip();
            return p.length() >= 2 && joined.contains(p);
        }
    }
}
