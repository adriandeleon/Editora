package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the Settings search matcher (pure, no toolkit). */
class SettingsSearchTest {

    @Test
    void blankQueryMatchesEverything() {
        assertTrue(SettingsWindow.matches("", "font size"));
        assertTrue(SettingsWindow.matches(null, "font size"));
        assertTrue(SettingsWindow.matches("   ", "anything"));
    }

    @Test
    void caseInsensitiveSubstring() {
        assertTrue(SettingsWindow.matches("MINI", "show minimap overview"));
        assertTrue(SettingsWindow.matches("spell", "spell check spelling enable"));
        assertTrue(SettingsWindow.matches("Tab", "tab size indent width"));
    }

    @Test
    void nonMatchingQuery() {
        assertFalse(SettingsWindow.matches("python", "font size text"));
        assertFalse(SettingsWindow.matches("xyz", "theme appearance dark light"));
    }

    @Test
    void nullKeywordsNeverMatchNonBlankQuery() {
        assertFalse(SettingsWindow.matches("font", null));
    }

    @Test
    void queryIsTrimmed() {
        assertTrue(SettingsWindow.matches("  minimap  ", "show minimap"));
    }

    @Test
    void everyWordMustMatchInAnyOrder() {
        assertTrue(SettingsWindow.matches("size font", "editor font size"));
        assertTrue(SettingsWindow.matches("font   size", "Font Size"));
        assertFalse(SettingsWindow.matches("font colour", "editor font size"), "one missing word fails the row");
    }

    @Test
    void localizedTextIsMatchedCaseInsensitively() {
        assertTrue(SettingsWindow.matches("schrift", "font size Schriftgröße Größe der Editorschrift"));
        assertTrue(SettingsWindow.matches("GRÖSSE schrift", "font size Schriftgrösse"));
    }
}
