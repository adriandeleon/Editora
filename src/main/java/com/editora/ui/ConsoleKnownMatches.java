package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The matches an open console find bar already knows, kept current as output arrives so a repaint searches only
 * what changed (perf review P6): the text {@code [0, end())} has been searched for one query, and its matches are
 * known. A console only appends at its end, cuts its oldest output from the top and clears; an edit anywhere
 * else just moves {@link #end()} back to where it happened.
 *
 * <p>The matches are stored as they were found and shifted lazily, so cutting the top of a console with
 * thousands of matches costs no copy.
 */
final class ConsoleKnownMatches {

    private @Nullable Object source;
    private String needle = "";
    private boolean caseSensitive;
    private boolean known;

    /** As found: {@code [start, end)} pairs, ascending, in the coordinates the text had then. */
    private List<int[]> stored = List.of();
    /** The first of {@link #stored} not cut away. */
    private int first;
    /** How many characters were cut from the top since: stored offset minus this is the current offset. */
    private int shift;

    private int end;

    /** Whether these matches are for {@code needle} in {@code source}, and so can be built on. */
    boolean knows(Object source, String needle, boolean caseSensitive) {
        return known && this.source == source && this.needle.equals(needle) && this.caseSensitive == caseSensitive;
    }

    /**
     * {@code matches} (ascending, in current coordinates) are every match in the text's first {@code end}
     * characters. A capped list ({@code capped}) is not complete, so nothing is known.
     */
    void set(Object source, String needle, boolean caseSensitive, List<int[]> matches, int end, boolean capped) {
        if (capped) {
            forget();
            return;
        }
        this.source = source;
        this.needle = needle;
        this.caseSensitive = caseSensitive;
        this.known = true;
        this.stored = matches;
        this.first = 0;
        this.shift = 0;
        this.end = end;
    }

    void forget() {
        known = false;
        source = null;
        stored = List.of();
        first = 0;
        shift = 0;
        end = 0;
    }

    /** The text changed: {@code removed} characters at {@code position} were replaced by {@code inserted}. */
    void changed(int position, int removed, int inserted) {
        if (!known) {
            return;
        }
        if (position == 0 && inserted == 0) { // the oldest output cut away, or the console cleared
            shift += removed;
            while (first < stored.size() && stored.get(first)[0] < shift) {
                first++;
            }
            end = Math.max(0, end - removed);
        } else if (position < end) {
            end = position;
        }
    }

    /** Up to where the text is searched. */
    int end() {
        return end;
    }

    /** The known matches that end at or before {@code offset}, in current coordinates. */
    List<int[]> before(int offset) {
        List<int[]> out = new ArrayList<>();
        for (int i = first; i < stored.size(); i++) {
            int[] m = stored.get(i);
            if (m[1] - shift > offset) {
                break;
            }
            out.add(new int[] {m[0] - shift, m[1] - shift});
        }
        return out;
    }

    /** Replays {@code changes} ({@code {position, removed, inserted}} each) made after a search's snapshot. */
    void replay(List<int[]> changes) {
        for (int[] c : changes) {
            changed(c[0], c[1], c[2]);
        }
    }
}
