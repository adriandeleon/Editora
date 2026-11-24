package com.editora.ui;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.util.Duration;

import com.editora.diff.BoundedDiff;
import com.editora.editor.DocumentText;
import com.editora.editor.EditorBuffer;
import com.editora.perf.OffFx;
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
 * <p>The mapping is a diff of the whole file, so it is worked out off the JavaFX thread (PERF-01, Pauta 6.2.0) on
 * a snapshot of the document, keyed by its {@linkplain EditorBuffer#docVersion() version}: {@link #known} answers
 * only for the version the document has now, {@link #latest} answers the last one worked out meanwhile, and a
 * waiter given to either runs on the FX thread once the mapping for the then-current text has landed (worked out
 * again while the text moved on). The snapshot is taken after the edit in progress, since a breakpoint shift is
 * reported while the edit is being applied, before the version moves. FX-confined apart from the work.
 */
final class LaunchedLines {

    /** Where the mapping is worked out: heard off the FX thread (PERF-01). */
    static final String SITE = "LaunchedLines.mapping";

    /**
     * A file's launched text: a handle on the document's version taken in O(1) at launch, its String built and its
     * lines split off the FX thread, the first time a mapping needs them. A tab never edited during the session is
     * never copied; one that is shares the unchanged parts with the live document (perf review 2026-11-23, D7).
     */
    private static final class Launched {
        final DocumentText text;
        private volatile @Nullable List<String> lines;

        Launched(DocumentText text) {
            this.text = text;
        }

        List<String> lines() {
            List<String> l = lines;
            if (l == null) {
                l = LaunchedLines.lines(text.text());
                lines = l;
            }
            return l;
        }
    }

    /** A mapping worked out for one version of the document. */
    private record Known(long version, Mapping mapping) {}

    /**
     * How long the text must stay unchanged before a mapping is worked out for an edit: holding Enter above a
     * breakpoint diffs the file once typing pauses, not once per newline (perf review 2026-11-23, D8). Asks that
     * are not typing (a stop, a toggle) do not wait.
     */
    static final Duration EDIT_PAUSE = Duration.millis(150);

    /** A mapping being worked out for a file, and who waits for it (keyed so a repeated ask waits once). */
    private static final class Pending {
        final Map<Object, Runnable> waiters = new LinkedHashMap<>();

        /** Waiting for the typing to pause ({@link #EDIT_PAUSE}) before the work starts; null when not waiting. */
        @Nullable
        PauseTransition quiet;
    }

    /** The launched text of each file of the session; keyed by {@link DebugCoordinator#key}. */
    private final Map<String, Launched> launched = new HashMap<>();

    private final Map<String, Known> known = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();

    /** Forgets every file (a session ended, or a new one is starting); mappings in flight are dropped. */
    void clear() {
        pending.values().forEach(p -> {
            if (p.quiet != null) {
                p.quiet.stop();
            }
        });
        launched.clear();
        known.clear();
        pending.clear();
    }

    boolean isEmpty() {
        return launched.isEmpty();
    }

    /** Records {@code text} as what {@code file}'s program was built from, unless a text is already recorded. */
    void snapshotIfAbsent(Path file, String text) {
        if (text != null) {
            snapshotIfAbsent(file, DocumentText.of(text));
        }
    }

    /** As {@link #snapshotIfAbsent(Path, String)}, from a handle whose text is built only when a mapping needs it. */
    void snapshotIfAbsent(Path file, DocumentText text) {
        if (file != null && text != null) {
            launched.putIfAbsent(DebugCoordinator.key(file), new Launched(text));
        }
    }

    /** The program now runs {@code text} for {@code file} (a hot code replace succeeded): map from it from now on. */
    void rebaseline(Path file, String text) {
        if (text != null) {
            rebaseline(file, DocumentText.of(text));
        }
    }

    /** As {@link #rebaseline(Path, String)}, from a handle on the document's version. */
    void rebaseline(Path file, DocumentText text) {
        if (file != null && text != null) {
            String key = DebugCoordinator.key(file);
            launched.put(key, new Launched(text));
            known.remove(key); // a mapping in flight from the old text is worked out again when it lands
        }
    }

    boolean has(Path file) {
        return file != null && launched.containsKey(DebugCoordinator.key(file));
    }

    /**
     * The mapping between {@code b}'s launched text and its document now, or null while it is being worked out
     * (then asked for). {@link Mapping#IDENTITY} when the file has no launched text.
     */
    @Nullable
    Mapping known(EditorBuffer b) {
        return knownOr(b, null, null);
    }

    /**
     * As {@link #known}, but while the mapping is worked out, {@code onKnown} runs once it has landed (one waiter
     * per {@code tag}: a later ask with the same tag replaces it).
     */
    @Nullable
    Mapping knownOr(EditorBuffer b, @Nullable Object tag, @Nullable Runnable onKnown) {
        Path file = b.getPath();
        String key = file == null ? null : DebugCoordinator.key(file);
        if (key == null || !launched.containsKey(key)) {
            return Mapping.IDENTITY;
        }
        Known k = known.get(key);
        if (k != null
                && k.version() == b.docVersion()
                && !b.getBreakpointManager().isEditDriven()) {
            return k.mapping();
        }
        ask(b, key, tag, onKnown);
        return null;
    }

    /**
     * The mapping for {@code b}'s text now when known; else the last one worked out for the file (identity when
     * none yet), and {@code onKnown} runs once the current one lands.
     */
    Mapping latest(EditorBuffer b, Object tag, Runnable onKnown) {
        Mapping now = knownOr(b, tag, onKnown);
        if (now != null) {
            return now;
        }
        Path file = b.getPath();
        Known last = file == null ? null : known.get(DebugCoordinator.key(file));
        return last == null ? Mapping.IDENTITY : last.mapping();
    }

    private void ask(EditorBuffer b, String key, @Nullable Object tag, @Nullable Runnable onKnown) {
        Pending p = pending.get(key);
        boolean started = p != null;
        if (p == null) {
            p = new Pending();
            pending.put(key, p);
        }
        if (onKnown != null) {
            p.waiters.put(tag != null ? tag : onKnown, onKnown);
        }
        Pending mine = p;
        boolean typing = b.getBreakpointManager().isEditDriven();
        if (!started) {
            // after the edit in progress: its version and text; an edit's once the typing pauses
            Platform.runLater(() -> {
                if (typing) {
                    startWhenQuiet(b, key, mine);
                } else {
                    start(b, key, mine);
                }
            });
        } else if (!typing && p.quiet != null) {
            p.quiet.stop(); // someone not typing waits (a stop, a toggle): worked out now
            p.quiet = null;
            Platform.runLater(() -> start(b, key, mine));
        }
    }

    /** On the FX thread: {@link #start} once {@code b}'s text has not changed for {@link #EDIT_PAUSE}. */
    private void startWhenQuiet(EditorBuffer b, String key, Pending p) {
        if (pending.get(key) != p) {
            return; // the session was cleared meanwhile
        }
        long version = b.docVersion();
        PauseTransition quiet = new PauseTransition(EDIT_PAUSE);
        p.quiet = quiet;
        quiet.setOnFinished(e -> {
            if (p.quiet != quiet) {
                return; // started meanwhile, or waiting again
            }
            p.quiet = null;
            if (b.docVersion() != version || b.getBreakpointManager().isEditDriven()) {
                startWhenQuiet(b, key, p); // still typing
            } else {
                start(b, key, p);
            }
        });
        quiet.play();
    }

    /** On the FX thread: snapshots the document and works the mapping out off it. */
    private void start(EditorBuffer b, String key, Pending p) {
        Launched from = launched.get(key);
        if (pending.get(key) != p || from == null) {
            return; // the session was cleared meanwhile
        }
        long version = b.docVersion();
        DocumentText text = textOf(b); // O(1): the String is built off the FX thread (D8)
        OffFx.then(
                SITE, () -> Mapping.between(from.lines(), lines(text.text())), a -> land(b, key, p, from, version, a));
    }

    private void land(EditorBuffer b, String key, Pending p, Launched from, long version, OffFx.Answer<Mapping> a) {
        if (pending.get(key) != p) {
            return; // cleared
        }
        if (launched.get(key) != from) {
            Platform.runLater(() -> start(b, key, p)); // rebaselined meanwhile: from the new launched text
            return;
        }
        known.put(key, new Known(version, a.or(Mapping.IDENTITY)));
        if (b.docVersion() != version || b.getBreakpointManager().isEditDriven()) {
            // the text moved on meanwhile: worked out again, once the typing pauses
            Platform.runLater(() -> startWhenQuiet(b, key, p));
            return;
        }
        pending.remove(key);
        p.waiters.values().forEach(Runnable::run);
    }

    /**
     * {@code b}'s whole text as a handle taken in O(1) on the FX thread; a narrowed or log-filtered tab, whose
     * document is a part of the file, is copied whole ({@link EditorBuffer#getContent()}).
     */
    static DocumentText textOf(EditorBuffer b) {
        return b.isNarrowed() || b.isLogFiltered() ? DocumentText.of(b.getContent()) : b.documentText();
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
            // bounded: past the budget the changed middle is one region, paired by content below (P7)
            Patch<String> patch = BoundedDiff.orChangedMiddle(launched, current);
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
