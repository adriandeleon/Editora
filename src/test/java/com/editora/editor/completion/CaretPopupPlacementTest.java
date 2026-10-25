package com.editora.editor.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CaretPopupPlacementTest {

    // A 1000-pixel-tall screen, a caret line 20 pixels tall.

    @Test
    void belowTheCaretLineWhenItFits() {
        assertEquals(122, CaretPopupPlacement.top(100, 120, 200, 0, 1000));
    }

    @Test
    void aboveTheCaretLineNearTheScreensBottom() {
        // Below would end at 1012; above ends 2 pixels over the caret line instead of covering it.
        assertEquals(578, CaretPopupPlacement.top(780, 800, 200, 0, 1000));
    }

    @Test
    void theRoomierSideWhenItFitsNeither() {
        assertEquals(0, CaretPopupPlacement.top(600, 620, 900, 0, 1000), "more room above: pinned to the top");
        assertEquals(322, CaretPopupPlacement.top(300, 320, 900, 0, 1000), "more room below");
    }

    @Test
    void aScreenThatDoesNotStartAtZero() {
        assertEquals(1578, CaretPopupPlacement.top(1780, 1800, 200, 1000, 2000));
    }
}
