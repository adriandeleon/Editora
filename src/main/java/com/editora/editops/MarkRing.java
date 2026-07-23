package com.editora.editops;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.OptionalInt;

/**
 * The Emacs mark ring: a bounded, newest-first ring of caret positions that {@code C-SPC} pushes and
 * {@code pop-mark} walks back through. Pure and toolkit-free (mirroring {@link KillRing}); the buffer that
 * owns it shifts the stored offsets across edits so a mark still points at its text after you type.
 *
 * <p>Positions are document offsets. Popping cycles: {@link #pop} returns the most recent mark and rotates
 * the current point to the back of the ring, so repeated pops visit every mark and come back to where you
 * started — the observable behaviour of repeatedly pressing {@code C-u C-SPC} in Emacs. (Editora has no
 * {@code C-u} prefix argument, so pop is its own command rather than a prefixed {@code C-SPC}.)
 *
 * <p>Scoped to explicit marks only — automatic jump-back on a large motion is the separate
 * {@code NavigationHistory} ({@code nav.back}/{@code nav.forward}), so the two do not fight over one ring.
 */
public final class MarkRing {

    /** Emacs {@code mark-ring-max}. */
    public static final int DEFAULT_MAX = 16;

    private final int max;
    private final Deque<Integer> ring = new ArrayDeque<>(); // front = most recent

    public MarkRing() {
        this(DEFAULT_MAX);
    }

    public MarkRing(int max) {
        if (max < 1) {
            throw new IllegalArgumentException("max must be >= 1");
        }
        this.max = max;
    }

    /** Pushes a mark position, newest first; a consecutive duplicate is collapsed. Negative is ignored. */
    public void push(int pos) {
        if (pos < 0) {
            return;
        }
        if (!ring.isEmpty() && ring.peekFirst() == pos) {
            return;
        }
        ring.addFirst(pos);
        while (ring.size() > max) {
            ring.removeLast();
        }
    }

    /**
     * Emacs {@code pop-to-mark}: returns the most recent mark to move point to, and rotates
     * {@code currentPoint} to the back of the ring so repeated pops cycle through every mark and return to
     * the start. Empty when the ring is empty.
     */
    public OptionalInt pop(int currentPoint) {
        if (ring.isEmpty()) {
            return OptionalInt.empty();
        }
        int target = ring.removeFirst();
        if (currentPoint >= 0 && currentPoint != target) {
            ring.addLast(currentPoint);
        }
        return OptionalInt.of(target);
    }

    /**
     * Shifts every stored offset across an edit that, at offset {@code pos}, removed {@code removed}
     * characters and inserted {@code inserted}. A mark at or before the edit stays put; one after it moves
     * by the length delta; one inside the removed span collapses to the edit site — the same marker
     * arithmetic {@code BookmarkManager}/{@code SnippetSession} use to track positions through edits.
     */
    public void shift(int pos, int removed, int inserted) {
        if ((removed == 0 && inserted == 0) || ring.isEmpty()) {
            return;
        }
        int[] shifted = new int[ring.size()];
        int i = 0;
        for (int mark : ring) {
            shifted[i++] = shiftOne(mark, pos, removed, inserted);
        }
        ring.clear();
        for (int m : shifted) {
            ring.addLast(m);
        }
    }

    /** The pure per-mark shift; see {@link #shift}. */
    static int shiftOne(int mark, int pos, int removed, int inserted) {
        if (mark <= pos) {
            return mark; // at or before the edit (insertion at the mark keeps it before the new text)
        }
        if (mark >= pos + removed) {
            return mark + (inserted - removed); // wholly after the edit
        }
        return pos; // the text it pointed at was removed — collapse to the edit start (before any insert)
    }

    public int size() {
        return ring.size();
    }

    public boolean isEmpty() {
        return ring.isEmpty();
    }

    /** The ring contents, most recent first — for tests / display. */
    public List<Integer> positions() {
        return List.copyOf(ring);
    }

    public void clear() {
        ring.clear();
    }
}
