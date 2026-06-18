package com.editora.ui;

/**
 * Pure visibility decisions for tool-window stripe buttons + the stripe panes (extracted from
 * {@link ToolWindowManager} for unit-testing). The two preferences are deliberately distinct:
 * {@code setVisible} is the user's persisted show/hide choice, while {@code setAvailable} is a transient,
 * context-driven hide (e.g. the Commit window outside a Git repo) that must not clobber that choice.
 */
final class ToolWindowVisibility {

    private ToolWindowVisibility() {}

    /** A stripe button shows iff the user has it visible AND it isn't transiently unavailable. */
    static boolean buttonShown(boolean visiblePref, boolean unavailable) {
        return visiblePref && !unavailable;
    }

    /** A stripe pane shows iff stripes are enabled, Zen isn't hiding them, and it has at least one button. */
    static boolean stripeShown(boolean stripesEnabled, boolean zenHidesStripes, boolean stripeEmpty) {
        return stripesEnabled && !zenHidesStripes && !stripeEmpty;
    }
}
