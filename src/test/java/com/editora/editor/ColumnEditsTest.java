package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.editora.editor.ColumnEdits.Range;

/** Unit tests for the pure rectangular-selection helpers (no toolkit). */
class ColumnEditsTest {

    private static final String TEXT = "alpha\nbeta\ngamma\n"; // lines: alpha, beta, gamma, ""

    @Test
    void lineStartsAndLengths() {
        int[] s = ColumnEdits.lineStarts(TEXT);
        assertArrayEquals(new int[]{0, 6, 11, 17}, s);
        assertEquals(5, ColumnEdits.lineLength(TEXT, s, 0)); // alpha
        assertEquals(4, ColumnEdits.lineLength(TEXT, s, 1)); // beta
        assertEquals(5, ColumnEdits.lineLength(TEXT, s, 2)); // gamma
        assertEquals(0, ColumnEdits.lineLength(TEXT, s, 3)); // trailing empty line
    }

    @Test
    void rectTextColumns1to3AcrossThreeLines() {
        // cols [1,3): "lp" / "et" / "am"
        assertEquals("lp\net\nam", ColumnEdits.rectText(TEXT, 0, 2, 1, 3));
    }

    @Test
    void rectTextClampsShortLines() {
        // cols [3,5) over alpha("ha"), beta(len 4 -> "a"), gamma("ma")
        assertEquals("ha\na\nma", ColumnEdits.rectText(TEXT, 0, 2, 3, 5));
    }

    @Test
    void rectRangesAbsoluteOffsets() {
        List<Range> r = ColumnEdits.rectRanges(TEXT, 0, 2, 1, 3);
        assertEquals(new Range(1, 3), r.get(0));   // alpha[1,3)
        assertEquals(new Range(7, 9), r.get(1));   // beta start 6, +1..+3
        assertEquals(new Range(12, 14), r.get(2)); // gamma start 11, +1..+3
    }

    @Test
    void rectRangesClampPastEol() {
        // cols [3,5) on beta (len 4): start 6, [9,10) only (one char)
        List<Range> r = ColumnEdits.rectRanges(TEXT, 1, 1, 3, 5);
        assertEquals(new Range(9, 10), r.get(0));
    }

    @Test
    void insertOffsetsAtColumn() {
        // insert at col 2 on lines 0..2: alpha->2, beta->8, gamma->13
        assertEquals(List.of(2, 8, 13), ColumnEdits.insertOffsets(TEXT, 0, 2, 2));
    }

    @Test
    void insertOffsetsClampToShortLine() {
        // insert at col 10 (past every line) clamps to each line end
        assertEquals(List.of(5, 10, 16), ColumnEdits.insertOffsets(TEXT, 0, 2, 10));
    }

    @Test
    void backspaceRangesSkipShortLines() {
        // delete char before col 5 on lines 0..2: alpha(len5)->[4,5), beta(len4) skipped, gamma(len5)->[15,16)
        List<Range> r = ColumnEdits.backspaceRanges(TEXT, 0, 2, 5);
        assertEquals(2, r.size());
        assertEquals(new Range(4, 5), r.get(0));
        assertEquals(new Range(15, 16), r.get(1));
    }

    @Test
    void backspaceAtColumnZeroIsEmpty() {
        assertEquals(List.of(), ColumnEdits.backspaceRanges(TEXT, 0, 2, 0));
    }
}
