package com.editora.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

class LspPositionsTest {

    @Test
    void lineCharForOffsets() {
        String t = "abc\ndef\nghi";
        assertEquals(0, LspPositions.lineChar(t, 0)[0]);
        assertEquals(0, LspPositions.lineChar(t, 0)[1]);
        assertEquals(0, LspPositions.lineChar(t, 2)[0]);
        assertEquals(2, LspPositions.lineChar(t, 2)[1]);
        // offset 4 is the 'd' on line 1, column 0
        assertEquals(1, LspPositions.lineChar(t, 4)[0]);
        assertEquals(0, LspPositions.lineChar(t, 4)[1]);
        // last char 'i' is line 2, column 2
        assertEquals(2, LspPositions.lineChar(t, 10)[0]);
        assertEquals(2, LspPositions.lineChar(t, 10)[1]);
    }

    @Test
    void offsetIsInverseOfLineChar() {
        String t = "alpha\nbeta\n\ngamma";
        for (int o = 0; o <= t.length(); o++) {
            int[] lc = LspPositions.lineChar(t, o);
            assertEquals(o, LspPositions.offset(t, lc[0], lc[1]), "roundtrip at offset " + o);
        }
    }

    @Test
    void offsetsClampOutOfRange() {
        String t = "abc\ndef";
        assertEquals(0, LspPositions.lineChar(t, -5)[1]);
        assertEquals(t.length(), LspPositions.offset(t, 99, 99)); // past end → text length
        assertEquals(3, LspPositions.offset(t, 0, 99)); // char past line end → line end
    }

    @Test
    void positionRoundTrip() {
        String t = "one\ntwo three\nfour";
        Position p = LspPositions.toPosition(t, 8); // 't' of "three"? offset 8 = line1 col4
        assertEquals(1, p.getLine());
        assertEquals(4, p.getCharacter());
        assertEquals(8, LspPositions.offset(t, p));
    }

    @Test
    void nullTextIsSafe() {
        assertEquals(0, LspPositions.lineChar(null, 5)[0]);
        assertEquals(0, LspPositions.offset(null, 3, 3));
    }
}
