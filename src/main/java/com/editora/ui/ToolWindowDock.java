package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure order arithmetic behind dragging a stripe button — within a stripe or across to another one.
 *
 * <p>Display order is one flat list of ids spanning all three stripes ({@code WorkspaceState
 * .toolWindowOrder}); a window's rank is only ever compared against its own side's peers, so moving an id
 * within that single list is all a re-dock needs. Both operations return a new list rather than mutating
 * the session's own, so a caller can compute first and commit once.
 */
final class ToolWindowDock {

    private ToolWindowDock() {}

    /**
     * Places {@code srcId} immediately before (or after) {@code targetId}.
     *
     * <p>Removing the source first is what makes the insertion index meaningful: taken from the original
     * list it would be off by one whenever the source sat ahead of the target.
     */
    static List<String> dropOnto(List<String> order, String srcId, String targetId, boolean after) {
        List<String> next = new ArrayList<>(order);
        if (srcId == null || targetId == null || srcId.equals(targetId)) {
            return next;
        }
        next.remove(srcId);
        int t = next.indexOf(targetId);
        if (t < 0) {
            next.add(srcId); // target isn't ordered (shouldn't happen) — last is the honest answer
        } else {
            next.add(after ? t + 1 : t, srcId);
        }
        return next;
    }

    /**
     * Sends {@code srcId} to the end of the list, which puts it last on whichever side it now sits — the
     * only non-arbitrary landing spot for a drop on empty stripe space, where there is no neighbour to
     * measure against.
     */
    static List<String> dropAtEnd(List<String> order, String srcId) {
        List<String> next = new ArrayList<>(order);
        if (srcId == null) {
            return next;
        }
        next.remove(srcId);
        next.add(srcId);
        return next;
    }
}
