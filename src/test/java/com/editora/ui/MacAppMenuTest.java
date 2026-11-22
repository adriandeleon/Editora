package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** WIN-15: the title of the Settings item in macOS's application menu, which carries the keymap's chord. */
class MacAppMenuTest {

    @Test
    void settingsDropsItsCategoryTakesAnEllipsisAndShowsTheChord() {
        assertEquals("Settings…   ⌘,", MacAppMenu.settingsTitle("View: Settings", "⌘,"));
        assertEquals("Einstellungen…", MacAppMenu.settingsTitle("Ansicht: Einstellungen", null));
        assertEquals("Settings…", MacAppMenu.settingsTitle("Settings", " "));
    }
}
