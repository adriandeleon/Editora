package com.editora.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchHighlighterTest {

    @Test
    void prefixIsOneContiguousRun() {
        assertArrayEquals(new int[][] {{0, 3}}, MatchHighlighter.matchRanges("getValue", "get"));
    }

    @Test
    void caseInsensitiveSubstring() {
        assertArrayEquals(new int[][] {{3, 6}}, MatchHighlighter.matchRanges("getValue", "val"));
    }

    @Test
    void camelCaseSubsequenceCoalescesRuns() {
        // "gv" matches g(0) and V(3) — two single-char runs.
        assertArrayEquals(new int[][] {{0, 1}, {3, 4}}, MatchHighlighter.matchRanges("getValue", "gv"));
    }

    @Test
    void nonSubsequenceYieldsNoRanges() {
        assertEquals(0, MatchHighlighter.matchRanges("getValue", "xyz").length);
    }

    @Test
    void blankOrNullQueryYieldsNoRanges() {
        assertEquals(0, MatchHighlighter.matchRanges("getValue", "").length);
        assertEquals(0, MatchHighlighter.matchRanges("getValue", "   ").length);
        assertEquals(0, MatchHighlighter.matchRanges("getValue", null).length);
        assertEquals(0, MatchHighlighter.matchRanges(null, "g").length);
    }

    /**
     * Ranges must index the label itself. They used to be computed over {@code label.toLowerCase()}, which
     * is not length-preserving — "İ" (U+0130) lowercases to two chars — so every index past such a char was
     * shifted: the popup cell (which substrings the label with these ranges) bolded the wrong characters,
     * and read past the label's end when the lowercased copy was longer.
     */
    @Test
    void rangesIndexTheLabelNotItsLowercasedCopy() {
        assertArrayEquals(new int[][] {{1, 5}}, MatchHighlighter.matchRanges("İstanbul", "stan"));
        for (int[] r : MatchHighlighter.matchRanges("İd", "d")) {
            assertTrue(r[1] <= "İd".length(), "a range must stay inside the label");
        }
        assertArrayEquals(new int[][] {{2, 3}}, MatchHighlighter.matchRanges("İşd", "d"));
    }

    /** Every range the popup cell will substring must be within the label, for any label/query pair. */
    @Test
    void rangesAreAlwaysWithinTheLabel() {
        for (String label : new String[] {"İstanbul", "İd", "ǅungla", "STRASSE", "İİİx", "getValue"}) {
            for (String q : new String[] {"d", "x", "i", "s", "stan", "ss", "gv"}) {
                for (int[] r : MatchHighlighter.matchRanges(label, q)) {
                    assertTrue(
                            r[0] >= 0 && r[0] <= r[1] && r[1] <= label.length(),
                            "range " + r[0] + ".." + r[1] + " outside " + label + " for query " + q);
                }
            }
        }
    }
}
