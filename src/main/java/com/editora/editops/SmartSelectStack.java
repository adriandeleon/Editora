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
        return expand(text, s, e, null);
    }

    /**
     * As {@link #expand(String, int, int)}, but preferring a language server's selection-range chain when one
     * is available (#739) — it is grammar-accurate, where {@link SmartSelect}'s bracket/quote scan is
     * best-effort and knows nothing about strings or comments.
     *
     * <p>{@code serverChain} is the innermost-first list of {@code [start, end]} ranges around the ladder's
     * origin; null or empty falls back to the local ladder, which is also what happens on the first press of
     * a new ladder while the request is still in flight. Whichever source produced the range, the stack
     * records what was <em>applied</em>, so {@link #shrink} retraces a mixed ladder correctly.
     */
    public int[] expand(String text, int s, int e, java.util.List<int[]> serverChain) {
        if (!isLast(s, e)) {
            stack.clear(); // the selection changed under us → begin a new ladder
        }
        int[] next = fromChain(serverChain, s, e);
        if (next == null) {
            next = SmartSelect.expand(text, s, e);
        }
        if (next == null) {
            return null;
        }
        stack.push(new int[] {s, e});
        last = next;
        return next;
    }

    /**
     * The smallest range in {@code chain} that <em>strictly</em> contains {@code [s, e]}, or null.
     *
     * <p>Strictly: a range equal to the live selection would be an expand press that visibly does nothing.
     * This scans the whole chain and takes the smallest match rather than trusting it to be innermost-first —
     * a server answering outermost-first would otherwise jump straight to the whole file on one press.
     */
    private static int[] fromChain(java.util.List<int[]> chain, int s, int e) {
        if (chain == null || chain.isEmpty()) {
            return null;
        }
        int[] best = null;
        for (int[] r : chain) {
            if (r == null || r.length != 2) {
                continue;
            }
            boolean contains = r[0] <= s && r[1] >= e;
            boolean strictly = r[0] < s || r[1] > e;
            if (contains && strictly && (best == null || r[1] - r[0] < best[1] - best[0])) {
                best = r;
            }
        }
        return best == null ? null : new int[] {best[0], best[1]};
    }

    /**
     * Whether {@code [s, e]} is still the range this stack last handed out — i.e. the next {@link #expand}
     * continues the current ladder rather than starting a new one. The caller uses it to decide when to
     * re-anchor the asynchronous selection-range request (#739).
     */
    public boolean continues(int s, int e) {
        return isLast(s, e);
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
