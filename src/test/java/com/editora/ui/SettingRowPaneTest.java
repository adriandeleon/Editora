package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When a Settings row puts its control under the text instead of beside it (pure). */
class SettingRowPaneTest {

    @Test
    void aNarrowControlSitsBesideALongDescription() {
        assertFalse(SettingRowPane.stacks(700, 900, 48)); // a switch
        assertFalse(SettingRowPane.stacks(700, 900, 220)); // an ordinary combo
    }

    @Test
    void aWideControlDropsBelowRatherThanSqueezingTheDescription() {
        // Build Tools › Default JDK: a ~460px combo in a ~640px card left the description ~90px.
        assertTrue(SettingRowPane.stacks(640, 900, 460));
        assertFalse(SettingRowPane.stacks(900, 900, 460), "a wide window still has room for both");
    }

    @Test
    void aShortTitleNeverForcesAStack() {
        // "Detected" + a long status pill: the text needs only its own width, not the full minimum.
        assertFalse(SettingRowPane.stacks(520, 70, 420));
        assertTrue(SettingRowPane.stacks(480, 70, 420));
    }
}
