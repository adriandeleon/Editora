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

    private static List<String> wrapText(String text, int cols) {
        return PdfText.wrap(List.of(new PdfText.Run(text, PdfTheme.DEFAULT_FG, false, false)), cols).stream()
                .map(runs -> runs.stream().map(PdfText.Run::text).reduce("", String::concat))
                .toList();
    }

    @Test
    void wrapPrefersTheLastSpaceNearTheLimit() {
        // 20 columns: "the quick brown fox " is exactly 20, then "jumps" would be cut mid-word.
        assertEquals(
                List.of("the quick brown ", "foxes jump over the ", "lazy dog"),
                wrapText("the quick brown foxes jump over the lazy dog", 20));
        // Nothing is lost or reordered, whatever the width.
        String line = "    return compute(alpha, beta) + compute(gamma, delta); // trailing comment here";
        for (int cols = 1; cols < 90; cols++) {
            List<String> wrapped = wrapText(line, cols);
            assertEquals(line, String.join("", wrapped), "at " + cols + " columns");
            for (String visual : wrapped) {
                assertTrue(PdfText.columns(visual) <= cols, "\"" + visual + "\" is wider than " + cols);
            }
        }
    }

    @Test
    void wrapCutsHardWhenThereIsNoSpaceInTheLastQuarter() {
        // The only space is in the first half: breaking there would waste most of the line.
        assertEquals(List.of("ab cdefghijklmnopqrs", "tuvwxyz"), wrapText("ab cdefghijklmnopqrstuvwxyz", 20));
        // A space exactly at the limit is already a clean break.
        assertEquals(List.of("abcdefghij", " klm"), wrapText("abcdefghij klm", 10));
        assertEquals(List.of("abcdefghi ", "klm"), wrapText("abcdefghi klm", 10));
    }

    @Test
    void wrapNeverBreaksInsideIndentation() {
        String line = " ".repeat(18) + "x".repeat(10);
        assertEquals(List.of(" ".repeat(18) + "xx", "xxxxxxxx"), wrapText(line, 20), "the indent is not a break");
    }

    @Test
    void wrapKeepsStylesAcrossTheBreak() {
        var line = List.of(
                new PdfText.Run("int ", java.awt.Color.RED, true, false),
                new PdfText.Run("value = other;", PdfTheme.DEFAULT_FG, false, false));
        List<List<PdfText.Run>> wrapped = PdfText.wrap(line, 12);
        assertEquals(2, wrapped.size());
        assertEquals(
                new PdfText.Run("int ", java.awt.Color.RED, true, false),
                wrapped.get(0).get(0));
        assertEquals("value = ", wrapped.get(0).get(1).text());
        assertEquals("other;", wrapped.get(1).get(0).text());
    }

    @Test
    void combiningMarksTakeNoColumnAndStayWithTheirBase() {
        assertEquals(0, PdfText.columns(0x0301), "combining acute");
        assertEquals(0, PdfText.columns(0x200D), "zero-width joiner");
        assertEquals(0, PdfText.columns(0xFE0F), "variation selector");
        assertEquals(4, PdfText.columns("cafe\u0301"), "e + combining accent is one cell");
        // Ten cells; the accent on the tenth must not start the next line.
        List<String> wrapped = wrapText("abcdefghie\u0301klm", 10);
        assertEquals(List.of("abcdefghie\u0301", "klm"), wrapped);
    }

    @Test
    void wideCharactersAndEmojiTakeTwoColumns() {
        assertEquals(2, PdfText.columns(0x4E2D), "CJK ideograph");
        assertEquals(2, PdfText.columns(0x1F680), "rocket");
        assertEquals(2, PdfText.columns(0x1F600), "grinning face");
        assertEquals(1, PdfText.columns('a'));
        assertEquals(1, PdfText.columns(0x00E9), "precomposed é");
        assertEquals(List.of("\uD83D\uDE80\uD83D\uDE80", "\uD83D\uDE80"), wrapText("\uD83D\uDE80".repeat(3), 5));
        // A tab stop after a combining mark counts the cell once.
        assertEquals(
                "e\u0301  x",
                PdfText.splitIntoLineRuns("e\u0301\tx", null, 3).get(0).get(0).text());
    }
}
