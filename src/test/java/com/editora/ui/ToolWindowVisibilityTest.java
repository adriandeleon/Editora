package com.editora.ui;

import org.junit.jupiter.api.Test;

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
}
