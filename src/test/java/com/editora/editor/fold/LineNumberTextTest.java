package com.editora.editor.fold;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/** The gutter's padded line numbers: the same text {@code String.format} gave, without a Formatter per row. */
class LineNumberTextTest {

    @Test
    void formatMatchesTheFormatterItReplaced() {
        int[] numbers = {0, 1, 7, 9, 10, 99, 100, 4096, 99_999, 100_000, 1_234_567, Integer.MAX_VALUE};
        for (int width = 0; width <= 8; width++) {
            for (int n : numbers) {
                assertEquals(
                        String.format("%1$" + Math.max(1, width) + "s", n),
                        LineNumberText.format(n, Math.max(1, width)),
                        "number " + n + " in width " + width);
            }
        }
        assertEquals("12345", LineNumberText.format(12345, 3), "never truncated");
    }

    @Test
    void aRowRebuiltAtTheSameWidthReusesItsString() {
        LineNumberText cache = new LineNumberText();
        String first = cache.padded(42, 4);
        assertEquals("  42", first);
        assertSame(first, cache.padded(42, 4), "a cell rebuilt by the virtual flow formats nothing");
        assertSame(cache.blank(4), cache.blank(4));
        assertEquals("    ", cache.blank(4));
    }

    @Test
    void aWidthChangeRepadsEveryRow() {
        LineNumberText cache = new LineNumberText();
        String narrow = cache.padded(5, 2);
        assertEquals(" 5", narrow);
        String wide = cache.padded(5, 3);
        assertEquals("  5", wide);
        assertNotSame(narrow, wide);
        assertEquals("   ", cache.blank(3));
    }

    @Test
    void slotCollisionsAreStillCorrect() {
        LineNumberText cache = new LineNumberText();
        String low = cache.padded(3, 6);
        String high = cache.padded(3 + LineNumberText.SLOTS, 6);
        assertEquals("     3", low);
        assertEquals("  1027", high);
        assertEquals("     3", cache.padded(3, 6), "the evicted row is formatted again, correctly");
    }
}
