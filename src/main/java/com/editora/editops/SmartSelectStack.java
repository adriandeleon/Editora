package com.editora.editops;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The expand/shrink selection <em>history</em> that sits on top of {@link SmartSelect}. Pure (no toolkit),
 * so it is unit-tested; the UI just applies the ranges it returns.
 *
 * <p>{@link #expand} grows the selection and pushes the previous one; {@link #shrink} pops back. The stack
 * is invalidated whenever the live selection is not the one this stack last produced — meaning the user
 * moved the caret or edited between presses — so a fresh {@code expand} starts a new ladder rather than
 * growing from a stale anchor.
 */
public final class SmartSelectStack {

    private final Deque<int[]> stack = new ArrayDeque<>();
    private int[] last; // the selection this stack last handed out, or null

    /**
     * The next range out from the live selection {@code [s, e)}, or {@code null} when there is nothing
     * larger (the whole document). The returned range is recorded as the new "last".
     */
    public int[] expand(String text, int s, int e) {
        if (!isLast(s, e)) {
            stack.clear(); // the selection changed under us → begin a new ladder
        }
        int[] next = SmartSelect.expand(text, s, e);
        if (next == null) {
            return null;
        }
        stack.push(new int[] {s, e});
        last = next;
        return next;
    }

    /**
     * The previous range in the current ladder, or {@code null} when the live selection is not the one we
     * produced or there is nothing to shrink back to.
     */
    public int[] shrink(int s, int e) {
        if (!isLast(s, e) || stack.isEmpty()) {
            return null;
        }
        int[] prev = stack.pop();
        last = prev;
        return prev;
    }

    private boolean isLast(int s, int e) {
        return last != null && last[0] == s && last[1] == e;
    }
}
