package com.editora.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Project Map explore round, the cards group: the pure parts of find in a card and declaration stepping. */
class ProjectMapCardsExploreTest {

    @Test
    void findMatchesCountsEveryPlaceTheTextStandsIgnoringCaseUnlessAsked() {
        assertArrayEquals(
                new int[] {0, 9, 18},
                ProjectMapPreview.findMatches("return a\nReturn b\nRETURN c", "return", false, 100));
        assertArrayEquals(
                new int[] {0}, ProjectMapPreview.findMatches("return a\nReturn b\nRETURN c", "return", true, 100));
        assertArrayEquals(
                new int[] {0, 1, 2}, ProjectMapPreview.findMatches("aaaa", "aa", false, 100), "overlaps count");
        assertArrayEquals(new int[] {0, 1}, ProjectMapPreview.findMatches("aaaa", "aa", false, 2), "capped");
        assertEquals(0, ProjectMapPreview.findMatches("abc", "", false, 10).length);
        assertEquals(0, ProjectMapPreview.findMatches("ab", "abc", false, 10).length);
    }

    @Test
    void findPickLandsOnTheNextOrPreviousMatchAndWraps() {
        int[] starts = {5, 20, 40};
        assertEquals(0, ProjectMapPreview.findPick(starts, 5, 0), "typing: the match at the start counts");
        assertEquals(1, ProjectMapPreview.findPick(starts, 6, 0));
        assertEquals(1, ProjectMapPreview.findPick(starts, 5, 1), "next: after the current one");
        assertEquals(0, ProjectMapPreview.findPick(starts, 40, 1), "past the last: the first");
        assertEquals(2, ProjectMapPreview.findPick(starts, 5, -1), "before the first: the last");
        assertEquals(1, ProjectMapPreview.findPick(starts, 40, -1));
    }

    @Test
    void altPageUpAndDownStepADeclarationCardAndNothingElseDoes() {
        assertEquals(-1, ProjectMapPreview.declarationStepOf(key(KeyCode.PAGE_UP, false, true, false)));
        assertEquals(1, ProjectMapPreview.declarationStepOf(key(KeyCode.PAGE_DOWN, false, true, false)));
        assertEquals(0, ProjectMapPreview.declarationStepOf(key(KeyCode.PAGE_DOWN, false, false, false)));
        assertEquals(0, ProjectMapPreview.declarationStepOf(key(KeyCode.PAGE_DOWN, true, true, false)));
        assertEquals(0, ProjectMapPreview.declarationStepOf(key(KeyCode.PAGE_DOWN, false, true, true)));
        assertTrue(ProjectMapPreview.DECLARATION_CARD_KEYS.containsAll(ProjectMapPreview.GEOMETRY_KEYS));
        assertTrue(ProjectMapPreview.DECLARATION_CARD_KEYS.contains("M-pageup"));
        assertTrue(ProjectMapPreview.DECLARATION_CARD_KEYS.contains("M-pagedown"));
    }

    private static KeyEvent key(KeyCode code, boolean shift, boolean alt, boolean control) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false);
    }
}
