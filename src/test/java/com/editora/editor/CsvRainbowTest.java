package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

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
}
