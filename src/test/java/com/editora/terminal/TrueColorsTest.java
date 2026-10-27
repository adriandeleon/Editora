package com.editora.terminal;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TrueColorsTest {

    @Test
    void theSameColourIsTheSameObject() {
        assertSame(TerminalPalette.TrueColors.of(12, 200, 7), TerminalPalette.TrueColors.of(12, 200, 7));
    }

    @Test
    void theValueIsExact() {
        assertEquals(Color.rgb(255, 0, 128), TerminalPalette.TrueColors.of(255, 0, 128));
        assertEquals(Color.rgb(0, 0, 0), TerminalPalette.TrueColors.of(0, 0, 0));
    }

    /**
     * Every colour, through a cache a fraction of that size: a slot that is reused must never hand
     * back the colour that used to be in it.
     */
    @Test
    void aSharedSlotNeverAnswersWithTheWrongColour() {
        for (int r = 0; r < 256; r += 3) {
            for (int g = 0; g < 256; g += 5) {
                for (int b = 0; b < 256; b += 7) {
                    Color c = TerminalPalette.TrueColors.of(r, g, b);
                    assertEquals(r, (int) Math.round(c.getRed() * 255));
                    assertEquals(g, (int) Math.round(c.getGreen() * 255));
                    assertEquals(b, (int) Math.round(c.getBlue() * 255));
                }
            }
        }
    }
}
