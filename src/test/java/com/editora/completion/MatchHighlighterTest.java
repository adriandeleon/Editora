package com.editora.completion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
