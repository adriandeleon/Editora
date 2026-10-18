package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the Settings search matcher (pure, no toolkit). */
class SettingsSearchTest {

    @Test
    void blankQueryMatchesEverything() {
        assertTrue(SettingsSearch.matches("", "font size"));
        assertTrue(SettingsSearch.matches(null, "font size"));
        assertTrue(SettingsSearch.matches("   ", "anything"));
    }

    @Test
    void caseInsensitiveSubstring() {
        assertTrue(SettingsSearch.matches("MINI", "show minimap overview"));
        assertTrue(SettingsSearch.matches("spell", "spell check spelling enable"));
        assertTrue(SettingsSearch.matches("Tab", "tab size indent width"));
    }

    @Test
    void nonMatchingQuery() {
        assertFalse(SettingsSearch.matches("python", "font size text"));
        assertFalse(SettingsSearch.matches("xyz", "theme appearance dark light"));
    }

    @Test
    void nullKeywordsNeverMatchNonBlankQuery() {
        assertFalse(SettingsSearch.matches("font", null));
    }

    @Test
    void queryIsTrimmed() {
        assertTrue(SettingsSearch.matches("  minimap  ", "show minimap"));
    }

    @Test
    void everyWordMustMatchInAnyOrder() {
        assertTrue(SettingsSearch.matches("size font", "editor font size"));
        assertTrue(SettingsSearch.matches("font   size", "Font Size"));
        assertFalse(SettingsSearch.matches("font colour", "editor font size"), "one missing word fails the row");
    }

    @Test
    void localizedTextIsMatchedCaseInsensitively() {
        assertTrue(SettingsSearch.matches("schrift", "font size Schriftgröße Größe der Editorschrift"));
        assertTrue(SettingsSearch.matches("GRÖSSE schrift", "font size Schriftgrösse"));
    }

    private static SettingsSearch.Texts title(String t) {
        return new SettingsSearch.Texts(t, "", "", "", "");
    }

    private static SettingsSearch.Texts keywords(String k) {
        return new SettingsSearch.Texts("", "", "", "", k);
    }

    @Test
    void aTitleHitOutranksAKeywordHitAndAWholeWordOutranksPartOfOne() {
        assertTrue(SettingsSearch.score("wrap", title("Wrap long lines in code"))
                > SettingsSearch.score("wrap", keywords("workspace trust build wrapper mvnw")));
        assertTrue(SettingsSearch.score("line numbers", title("Show line numbers"))
                > SettingsSearch.score(
                        "line numbers",
                        new SettingsSearch.Texts("", "Hides the line-number gutter", "", "", "line numbers")));
        assertTrue(SettingsSearch.weigh("wrap long lines", "wrap") > SettingsSearch.weigh("wrapper", "wrap"));
        assertTrue(SettingsSearch.weigh("wrapper", "wrap") > SettingsSearch.weigh("unwrap", "wrap"));
        assertEquals(0, SettingsSearch.score("font colour", title("Font size")), "every word must be found");
    }

    @Test
    void synonymsFindASettingByAnotherName() {
        assertTrue(SettingsSearch.matches("zoom", "Font size"));
        assertTrue(SettingsSearch.matches("line endings", "editorconfig charset end of line"));
        assertTrue(SettingsSearch.matches("encoding", "editorconfig charset end of line"));
        assertTrue(SettingsSearch.matches("soft wrap", "Wrap long lines"));
        assertFalse(SettingsSearch.matches("zoom", "Theme"));
    }

    @Test
    void atModifiedAsksForModifiedRowsAndLeavesTheOtherWords() {
        assertTrue(SettingsSearch.wantsModified("@modified"));
        assertTrue(SettingsSearch.wantsModified("wrap @Modified"));
        assertFalse(SettingsSearch.wantsModified("modified"));
        assertFalse(SettingsSearch.wantsModified("@modifiedx"));
        assertEquals("wrap", SettingsSearch.withoutModifiedToken("@modified wrap"));
        assertEquals("", SettingsSearch.withoutModifiedToken(" @modified "));
        assertEquals("@modifiedx", SettingsSearch.withoutModifiedToken("@modifiedx"));
    }
}
