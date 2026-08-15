package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolWindowVisibilityTest {

    @Test
    void buttonShownNeedsVisibleAndAvailable() {
        assertTrue(ToolWindowVisibility.buttonShown(true, false));
        assertFalse(ToolWindowVisibility.buttonShown(false, false)); // user hid it
        assertFalse(ToolWindowVisibility.buttonShown(true, true)); // transiently unavailable (e.g. not a repo)
        assertFalse(ToolWindowVisibility.buttonShown(false, true));
    }

    @Test
    void stripeShownNeedsEnabledNotZenAndNonEmpty() {
        assertTrue(ToolWindowVisibility.stripeShown(true, false, false));
        assertFalse(ToolWindowVisibility.stripeShown(false, false, false)); // stripes disabled in Settings
        assertFalse(ToolWindowVisibility.stripeShown(true, true, false)); // Zen hides stripes
        assertFalse(ToolWindowVisibility.stripeShown(true, false, true)); // no buttons on the stripe
    }

    /** An empty stripe is revealed during a drag so a window can be re-docked to a side that has nothing. */
    @Test
    void anEmptyStripeIsShownWhileDragging() {
        assertTrue(ToolWindowVisibility.stripeShown(true, false, true, true));
        assertFalse(ToolWindowVisibility.stripeShown(true, false, true, false));
    }

    /** Dragging does not override the two deliberate hides — there is no stripe to drag from in either. */
    @Test
    void draggingDoesNotOverrideTheDeliberateHides() {
        assertFalse(ToolWindowVisibility.stripeShown(false, false, true, true)); // stripes off in Settings
        assertFalse(ToolWindowVisibility.stripeShown(true, true, true, true)); // Zen
    }

    /** The 3-arg form is the 4-arg one with no drag in progress, so existing callers are unchanged. */
    @Test
    void theShortFormAssumesNoDrag() {
        for (boolean enabled : new boolean[] {true, false}) {
            for (boolean zen : new boolean[] {true, false}) {
                for (boolean empty : new boolean[] {true, false}) {
                    assertEquals(
                            ToolWindowVisibility.stripeShown(enabled, zen, empty, false),
                            ToolWindowVisibility.stripeShown(enabled, zen, empty));
                }
            }
        }
    }
}
