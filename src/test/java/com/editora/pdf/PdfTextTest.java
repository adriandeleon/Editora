package com.editora.pdf;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfTextTest {

    @Test
    void splitsLinesAndExpandsTabs() {
        List<List<PdfText.Run>> lines = PdfText.splitIntoLineRuns("a\tb\nc", null, 4);
        assertEquals(2, lines.size());
        // tab after 1 column -> 3 spaces to reach column 4
        assertEquals("a   b", lines.get(0).get(0).text());
        assertEquals("c", lines.get(1).get(0).text());
        // null spans => default color, no bold/italic
        assertEquals(PdfTheme.DEFAULT_FG, lines.get(0).get(0).color());
        assertTrue(!lines.get(0).get(0).bold() && !lines.get(0).get(0).italic());
    }

    @Test
    void trailingNewlineYieldsTrailingEmptyLine() {
        List<List<PdfText.Run>> lines = PdfText.splitIntoLineRuns("x\n", null, 4);
        assertEquals(2, lines.size());
        assertEquals("x", lines.get(0).get(0).text());
        assertTrue(lines.get(1).isEmpty());
    }

    @Test
    void carriageReturnsAreDropped() {
        List<List<PdfText.Run>> lines = PdfText.splitIntoLineRuns("a\r\nb", null, 4);
        assertEquals(2, lines.size());
        assertEquals("a", lines.get(0).get(0).text());
        assertEquals("b", lines.get(1).get(0).text());
    }

    @Test
    void wrapBreaksAtColumnLimit() {
        var line = List.of(new PdfText.Run("abcdefghij", PdfTheme.DEFAULT_FG, false, false));
        List<List<PdfText.Run>> wrapped = PdfText.wrap(line, 4);
        assertEquals(3, wrapped.size());
        assertEquals("abcd", wrapped.get(0).get(0).text());
        assertEquals("efgh", wrapped.get(1).get(0).text());
        assertEquals("ij", wrapped.get(2).get(0).text());
    }

    @Test
    void wrapNoOpWhenWithinWidth() {
        var line = List.of(new PdfText.Run("abc", PdfTheme.DEFAULT_FG, false, false));
        assertEquals(1, PdfText.wrap(line, 10).size());
        assertEquals(1, PdfText.wrap(line, 0).size()); // 0 => unbounded
    }
}
