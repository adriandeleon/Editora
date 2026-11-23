package com.editora.editor.highlight;

import java.util.Collection;
import java.util.List;

import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvRainbowTest {

    /** Flattens segments to a readable "len:code,len:code,…" string for assertions. */
    private static String flat(List<int[]> segs) {
        StringBuilder b = new StringBuilder();
        for (int[] s : segs) {
            if (b.length() > 0) {
                b.append(',');
            }
            b.append(s[0]).append(':').append(s[1]);
        }
        return b.toString();
    }

    @Test
    void colorsColumnsAndDelimiters() {
        // "ab,cd,ef" → col0(ab) DELIM col1(cd) DELIM col2(ef)
        assertEquals("2:0,1:-1,2:1,1:-1,2:2", flat(CsvRainbow.segments("ab,cd,ef", ',', 8)));
    }

    @Test
    void columnsCycleModuloColorCount() {
        // 3 colors: columns 0,1,2,0 → the fourth field wraps to color 0.
        assertEquals("1:0,1:-1,1:1,1:-1,1:2,1:-1,1:0", flat(CsvRainbow.segments("a,b,c,d", ',', 3)));
    }

    @Test
    void resetsPerLineAndMarksNewline() {
        // Two rows; column numbering restarts after the newline (PLAIN = -2).
        assertEquals("1:0,1:-1,1:1,1:-2,1:0,1:-1,1:1", flat(CsvRainbow.segments("a,b\nc,d", ',', 8)));
    }

    @Test
    void quotedDelimiterIsPartOfTheField() {
        // "x,y" is one quoted field (color 0), so the inner comma is NOT a delimiter; then a real delimiter.
        // Chars: " x , y "  ,  z  → 5 chars of field (color 0), 1 delim, 1 field (color 1).
        assertEquals("5:0,1:-1,1:1", flat(CsvRainbow.segments("\"x,y\",z", ',', 8)));
    }

    @Test
    void escapedQuotePairStaysInField() {
        // "a""b" is one field: " a "" b " = 6 chars, all color 0.
        assertEquals("6:0", flat(CsvRainbow.segments("\"a\"\"b\"", ',', 8)));
    }

    @Test
    void emptyAndNull() {
        assertEquals("", flat(CsvRainbow.segments("", ',', 8)));
        assertEquals("", flat(CsvRainbow.segments(null, ',', 8)));
    }

    private static StyleSpans<Collection<String>> spans(String text) {
        return CsvRainbow.buildSpans(text, ',', 8);
    }

    @Test
    void anEditInsideAFieldRestylesOnlyTheEditedRange() {
        String before = "a,b\nc,d\ne,f\n";
        String after = "a,b\ncx,d\ne,f\n"; // 'x' typed at offset 5
        int[] range = CsvRainbow.changedRange(spans(before), spans(after), 5, 6);
        assertArrayEquals(new int[] {5, 6}, range, "the rest of the document keeps its colours");
    }

    @Test
    void aDelimiterTypedRecoloursTheRestOfItsRecordOnly() {
        String before = "a,b\nc,d\ne,f\n";
        String after = "a,b\nc,,d\ne,f\n"; // ',' typed at offset 6: 'd' moves to the third column
        int[] range = CsvRainbow.changedRange(spans(before), spans(after), 6, 7);
        assertEquals(6, range[0]);
        assertEquals(after.indexOf("\ne,f"), range[1], "the records below are untouched");
    }

    @Test
    void anOpeningQuoteRecoloursTheDelimitersItSwallowsInItsRecord() {
        String before = "a,b,c\nd,e\n";
        String after = "a,\"b,c\nd,e\n"; // the quote takes ",c" into the second field (quotes end at the line)
        int[] range = CsvRainbow.changedRange(spans(before), spans(after), 2, 3);
        assertEquals(2, range[0]);
        assertEquals(after.indexOf("c\n") + 1, range[1], "the record below keeps its colours");
    }

    @Test
    void nothingChangedWhenNothingWasEditedAndTheColoursAgree() {
        String text = "a,b\nc,d\n";
        int[] range = CsvRainbow.changedRange(spans(text), spans(text), text.length(), text.length());
        assertEquals(range[0], range[1]);
    }

    @Test
    void aDirtyRangeThatCannotExplainTheLengthRestylesAll() {
        int[] range = CsvRainbow.changedRange(spans("a,b\n"), spans("a,b\nc,d\ne,f\n"), 0, 1);
        assertArrayEquals(new int[] {0, "a,b\nc,d\ne,f\n".length()}, range);
    }
}
