package com.editora.ui;

import java.util.List;

/**
 * Pure decisions behind "maximize a tool window" (IntelliJ's Maximize Tool Window neighbourhood).
 *
 * <p>Maximizing is deliberately expressed as <em>divider positions</em> rather than as reparenting: the
 * panel already sits in the right {@code SplitPane}, so pushing every divider to one end gives it the whole
 * split without touching the scene graph — which keeps focus, the stripe button state, and the remembered
 * per-window sizes all intact, and makes restoring a single array assignment.
 */
final class ToolWindowMaximize {

    private ToolWindowMaximize() {}

    /** No divider change is possible (a split of one item, or an index that isn't in it). */
    static final double[] NO_CHANGE = new double[0];

    /**
     * The divider positions that give the item at {@code index} the whole split.
     *
     * <p>Divider <em>i</em> sits between items <em>i</em> and <em>i+1</em>, so every divider before the
     * target collapses to 0 (pushing the items above/left of it shut) and every divider from the target
     * onwards goes to 1. With the editor and both side panels open — {@code [left, editor, right]} —
     * maximizing the left window is therefore {@code [1, 1]} and the right one {@code [0, 0]}.
     *
     * @return one position per divider, or {@link #NO_CHANGE} when the split can't be maximized
     */
    static double[] positions(int itemCount, int index) {
        if (itemCount < 2 || index < 0 || index >= itemCount) {
            return NO_CHANGE;
        }
        double[] pos = new double[itemCount - 1];
        for (int i = 0; i < pos.length; i++) {
            pos[i] = i < index ? 0.0 : 1.0;
        }
        return pos;
    }

    /**
     * Which tool window a maximize request acts on: the one holding keyboard focus, else the only open one.
     *
     * <p>The fallback is what makes the command useful from the palette, where invoking it necessarily
     * moves focus out of the panel first. It stops at <em>one</em> open window on purpose — with two open
     * there is no non-arbitrary answer, and silently maximizing the wrong one is worse than saying so.
     *
     * @return the target, or {@code null} when the caller should report that there is nothing to maximize
     */
    static <T> T pick(T active, List<T> open) {
        if (active != null) {
            return active;
        }
        return open.size() == 1 ? open.get(0) : null;
    }
}
