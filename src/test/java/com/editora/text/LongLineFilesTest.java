package com.editora.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LongLineFilesTest {

    @Test
    void aBundleOfLongLinesLooksMinified() {
        assertTrue(LongLineFiles.looksMinified(2_400_300, 301, "javascript"), "300 lines of 8,000 characters");
        assertTrue(LongLineFiles.looksMinified(198_018, 19, "javascript"), "18 lines of 11,000 characters");
        assertTrue(LongLineFiles.looksMinified(760_040, 41, "css"));
    }

    @Test
    void ordinaryCodeAndSmallFilesDoNot() {
        assertFalse(LongLineFiles.looksMinified(521_000, 20_000, "javascript"), "20,000 lines of hand-written code");
        assertFalse(LongLineFiles.looksMinified(12_000, 3, "javascript"), "small: a few long lines type fine");
    }

    @Test
    void proseWithLongParagraphsIsNotMinified() {
        assertFalse(LongLineFiles.looksMinified(300_000, 400, "markdown"));
        assertFalse(LongLineFiles.looksMinified(300_000, 400, "plaintext"));
        assertFalse(LongLineFiles.looksMinified(300_000, 400, "log"));
    }

    @Test
    void viewFirstNamesTheStrongestReason() {
        assertEquals(LongLineFiles.Reason.LARGE, LongLineFiles.viewFirst(true, true, false, false, 9, 1, "json"));
        assertEquals(
                LongLineFiles.Reason.LONG_LINE, LongLineFiles.viewFirst(false, true, false, false, 70_000, 1, "json"));
        assertEquals(
                LongLineFiles.Reason.MINIFIED,
                LongLineFiles.viewFirst(false, false, false, false, 2_400_300, 301, "javascript"));
        assertNull(LongLineFiles.viewFirst(false, false, false, false, 5_000, 200, "javascript"));
    }

    @Test
    void aTruncatedLoadOrALogIsLeftAlone() {
        assertNull(LongLineFiles.viewFirst(true, false, true, false, 9, 1, "json"), "read-only for good already");
        assertNull(LongLineFiles.viewFirst(true, true, false, true, 9, 1, "log"), "the log viewer has its own mode");
    }
}
