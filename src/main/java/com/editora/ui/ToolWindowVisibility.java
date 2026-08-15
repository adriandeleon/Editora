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
        return stripeShown(stripesEnabled, zenHidesStripes, stripeEmpty, false);
    }

    /**
     * As above, but an <em>empty</em> stripe is also shown while a stripe button is being dragged, so it
     * can be dropped there.
     *
     * <p>Without this the empty side is unreachable and re-docking silently only works between sides that
     * already have a button on them: an empty stripe is {@code setManaged(false)}, and an unmanaged,
     * invisible node receives no drag events at all — so the drop target would be there, correct, and
     * never once consulted.
     *
     * <p>Deliberately does not override the two <em>deliberate</em> hides: with stripes switched off or Zen
     * on there is no stripe UI to drag from in the first place, so a drag can't be in progress.
     */
    static boolean stripeShown(boolean stripesEnabled, boolean zenHidesStripes, boolean stripeEmpty, boolean dragging) {
        return stripesEnabled && !zenHidesStripes && (!stripeEmpty || dragging);
    }
}
