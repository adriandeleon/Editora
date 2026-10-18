package com.editora.terminal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The scrollback-to-scrollbar mapping. */
class ScrollBarModelTest {

    @Test
    void theLiveScreenIsTheBottomOfTheTrack() {
        // The direction that matters: an inverted bar still scrolls, so nothing fails loudly.
        assertEquals(300, ScrollBarModel.value(300, 0));
        assertEquals(0, ScrollBarModel.value(300, -300));
    }

    @Test
    void draggingBackToTheBottomReturnsToTheLiveScreen() {
        assertEquals(0, ScrollBarModel.origin(300, 300));
        assertEquals(-300, ScrollBarModel.origin(300, 0));
        assertEquals(-120, ScrollBarModel.origin(300, 180));
    }

    @Test
    void aValueOutsideTheTrackIsClampedRatherThanScrollingPastTheBuffer() {
        assertEquals(0, ScrollBarModel.origin(300, 999));
        assertEquals(-300, ScrollBarModel.origin(300, -50));
    }

    @Test
    void theMappingsAreInverses() {
        for (int origin = 0; origin >= -300; origin -= 7) {
            assertEquals(origin, ScrollBarModel.origin(300, ScrollBarModel.value(300, origin)));
        }
    }

    @Test
    void thereIsNoBarWithoutHistory() {
        assertFalse(ScrollBarModel.useful(true, 0, false));
        assertTrue(ScrollBarModel.useful(true, 1, false));
    }

    @Test
    void thereIsNoBarOnTheAlternateScreen() {
        // vim and less own the viewport and keep no scrollback; a bar there would scroll away the
        // program's own display.
        assertFalse(ScrollBarModel.useful(true, 500, true));
    }

    @Test
    void theSettingWins() {
        assertFalse(ScrollBarModel.useful(false, 500, false));
    }
}
