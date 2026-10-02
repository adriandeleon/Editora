package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineEndingsTest {

    @Test
    void dominantPicksTheMostFrequentTerminator() {
        assertEquals("LF", LineEndings.dominant("a\nb\n"));
        assertEquals("CRLF", LineEndings.dominant("a\r\nb\r\n"));
        assertEquals("CR", LineEndings.dominant("a\rb\r"));
        assertEquals("CRLF", LineEndings.dominant("a\r\nb\r\nc\n"), "one stray LF does not flip a CRLF file");
        assertEquals("LF", LineEndings.dominant("a\nb\nc\r\n"), "one stray CRLF does not flip an LF file");
    }

    @Test
    void dominantDefaultsToLfWhenThereIsNothingToGoOn() {
        assertEquals("LF", LineEndings.dominant(null));
        assertEquals("LF", LineEndings.dominant(""));
        assertEquals("LF", LineEndings.dominant("no terminator at all"));
        assertEquals("LF", LineEndings.dominant("a\r\nb\n"), "a tie goes to LF");
    }

    @Test
    void toLfRemovesEveryCarriageReturn() {
        assertEquals("a\nb\nc\nd", LineEndings.toLf("a\r\nb\rc\nd"));
        assertEquals("", LineEndings.toLf(null));
        String untouched = "already\nnormalised\n";
        assertSame(untouched, LineEndings.toLf(untouched), "an LF-only text is returned as is, not copied");
    }

    @Test
    void applyIsTheInverseOfToLfForAUniformFile() {
        for (String original : new String[] {"a\r\nb\r\n", "a\rb\r", "a\nb\n", "", "one line"}) {
            String label = LineEndings.dominant(original);
            assertEquals(original, LineEndings.apply(LineEndings.toLf(original), label), label);
        }
    }

    @Test
    void applyLeavesTextAloneForLfAndUnknownLabels() {
        String text = "a\nb\n";
        assertSame(text, LineEndings.apply(text, "LF"));
        assertSame(text, LineEndings.apply(text, null));
        assertSame(text, LineEndings.apply(text, "nonsense"));
        assertNull(LineEndings.apply(null, "CRLF"));
    }

    @Test
    void labelsMapFromEditorConfigValues() {
        assertEquals("LF", LineEndings.labelOf("lf"));
        assertEquals("CRLF", LineEndings.labelOf("crlf"));
        assertEquals("CR", LineEndings.labelOf("cr"));
        assertNull(LineEndings.labelOf(null));
        assertNull(LineEndings.labelOf("unset"));
        assertTrue(LineEndings.isLabel("CRLF"));
        assertFalse(LineEndings.isLabel("crlf"));
        assertFalse(LineEndings.isLabel(null));
        assertEquals("\r\n", LineEndings.separator("CRLF"));
        assertEquals("\r", LineEndings.separator("CR"));
        assertEquals("\n", LineEndings.separator("LF"));
    }
}
