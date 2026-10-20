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
    }
}
