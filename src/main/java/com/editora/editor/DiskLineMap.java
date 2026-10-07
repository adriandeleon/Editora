package com.editora.editor;

import java.util.Arrays;

/**
 * Where each line of an edited buffer sits in the file <em>as it is on disk</em>.
 *
 * <p>Git describes a file by the lines of its saved copy: change bars and blame are both keyed by on-disk
 * line. A buffer with unsaved edits has other line numbers below every inserted or deleted line, so looking
 * the gutter data up by buffer line painted each annotation on the wrong line — and a click on a blame entry
 * opened the commit of a different line. This map is the translation: it starts as the identity (buffer and
 * disk agree), is spliced by every edit that adds or removes a line break, and is reset when the two agree
 * again (saved, reloaded, or edited back to the saved text).
 *
 * <p>A line the user typed, pasted or rewrote has no line on disk and maps to {@link #UNKNOWN}: it gets no
 * bar and no annotation rather than a neighbour's.
 *
 * <p>Pure — no toolkit. Only the lines up to the furthest edit are stored; everything below is the disk line
 * plus one constant, so an edit near the top of a long file costs a few entries, not one per line.
 */
final class DiskLineMap {

    /** A buffer line with no counterpart on disk. */
    static final int UNKNOWN = -1;

    private static final int[] NONE = new int[0];

    /** An insertion of more lines than this is not followed line by line; the map gives up instead. */
    static final int MAX_INSERTED_LINES = 10_000;

    /** Disk line of each buffer line in {@code [0, size)}. */
    private int[] origin = NONE;

    private int size;
    /** A buffer line at or past {@link #size} is disk line {@code line + tailDelta}. */
    private int tailDelta;
    /** The relation is no longer known at all: every line answers {@link #UNKNOWN} until {@link #reset()}. */
    private boolean lost;

    /** The on-disk line shown at buffer line {@code line}, or {@link #UNKNOWN}. */
    int diskLine(int line) {
        if (lost || line < 0) {
            return UNKNOWN;
        }
        return line < size ? origin[line] : line + tailDelta;
    }

    /** Whether buffer lines and disk lines currently coincide (nothing has shifted). */
    boolean identity() {
        return !lost && size == 0 && tailDelta == 0;
    }

    boolean lost() {
        return lost;
    }

    /** The buffer matches the disk again: back to the identity. */
    void reset() {
        origin = NONE;
        size = 0;
        tailDelta = 0;
        lost = false;
    }

    /** The relation can no longer be followed; nothing is annotated until the next {@link #reset()}. */
    void lose() {
        reset();
        lost = true;
    }

    /**
     * Follows one text replacement.
     *
     * <p>The old lines {@code line … line+k} ({@code k} line breaks removed) become the new lines
     * {@code line … line+m} ({@code m} line breaks inserted). A line keeps its disk line only when its own
     * text is still there: the first one when the edit began after its first character, the last one when
     * the removed text ended exactly at its start. Lines in between are new.
     *
     * @param line the buffer line the replacement started on, as lines were numbered when it ran
     * @param atLineStart whether it started at column 0 of that line
     * @return whether any line moved (false for an edit inside one line, which costs nothing here)
     */
    boolean edit(int line, boolean atLineStart, String removed, String inserted) {
        int k = LineMarks.countNewlines(removed);
        int m = LineMarks.countNewlines(inserted);
        if (lost || (k == 0 && m == 0) || line < 0) {
            return false;
        }
        if (m > MAX_INSERTED_LINES) {
            lose(); // a whole-document rewrite (formatter, reload): nothing below is the line it was
            return true;
        }
        int first = diskLine(line);
        int last = diskLine(line + k);
        int[] replacement = new int[m + 1];
        Arrays.fill(replacement, UNKNOWN);
        if (m == 0) {
            replacement[0] = atLineStart ? last : first; // lines joined: the surviving text names the line
        } else {
            boolean tailIntact = k > 0 ? removed.endsWith("\n") : (atLineStart && removed.isEmpty());
            replacement[0] = atLineStart ? UNKNOWN : first;
            replacement[m] = tailIntact ? last : UNKNOWN;
        }
        splice(line, k + 1, replacement);
        return true;
    }

    /** Replaces {@code oldCount} buffer lines at {@code line} with the disk lines in {@code replacement}. */
    private void splice(int line, int oldCount, int[] replacement) {
        int end = line + oldCount;
        int delta = replacement.length - oldCount;
        int covered = Math.max(size, end);
        int[] next = origin;
        if (covered + Math.max(0, delta) > origin.length) {
            next = Arrays.copyOf(origin, Math.max(16, (covered + Math.max(0, delta)) * 2));
        }
        for (int i = size; i < covered; i++) {
            next[i] = i + tailDelta; // the stored part grows down to the edit, still unshifted
        }
        System.arraycopy(next, end, next, end + delta, covered - end);
        System.arraycopy(replacement, 0, next, line, replacement.length);
        origin = next;
        size = covered + delta;
        tailDelta -= delta;
    }
}
