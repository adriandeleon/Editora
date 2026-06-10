package com.editora.diff;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class InlineDiffTest {

    @Test
    void tokenizeSplitsWordsFromPunctuationAndSpace() {
        assertEquals(java.util.List.of("foo", "(", "bar", ")"), InlineDiff.tokenize("foo(bar)"));
        assertEquals(java.util.List.of("a", " ", "b"), InlineDiff.tokenize("a b"));
    }

    @Test
    void singleChangedWordYieldsOneRangePerSide() {
        InlineDiff.Spans s = InlineDiff.compute("the quick fox", "the slow fox");
        assertEquals(1, s.left().length);
        assertEquals(1, s.right().length);
        assertEquals("quick", "the quick fox".substring(s.left()[0][0], s.left()[0][1]));
        assertEquals("slow", "the slow fox".substring(s.right()[0][0], s.right()[0][1]));
    }

    @Test
    void identicalLinesYieldNoRanges() {
        InlineDiff.Spans s = InlineDiff.compute("same text", "same text");
        assertEquals(0, s.left().length);
        assertEquals(0, s.right().length);
    }

    @Test
    void appendingToAWordHighlightsTheWholeChangedWord() {
        // "value" and "value2" are different word tokens, so both sides highlight the whole word
        // (word-level granularity, like GitHub's within-line emphasis).
        InlineDiff.Spans s = InlineDiff.compute("value", "value2");
        assertEquals(1, s.left().length);
        assertEquals("value", "value".substring(s.left()[0][0], s.left()[0][1]));
        assertEquals(1, s.right().length);
        assertEquals("value2", "value2".substring(s.right()[0][0], s.right()[0][1]));
    }

    @Test
    void appendingAfterAWordBoundaryHighlightsOnlyTheNewToken() {
        // A new trailing word after a space is a pure insert — nothing highlighted on the left.
        InlineDiff.Spans s = InlineDiff.compute("a b", "a b c");
        assertEquals(0, s.left().length);
        assertEquals(1, s.right().length);
        // The inserted tokens are the separating space and "c", so the highlighted range is " c".
        assertEquals(" c", "a b c".substring(s.right()[0][0], s.right()[0][1]));
    }

    @Test
    void mergeCoalescesAdjacentRanges() {
        int[][] merged = InlineDiff.merge(java.util.List.of(
                new int[]{0, 3}, new int[]{3, 5}, new int[]{7, 9}));
        assertEquals(2, merged.length);
        assertEquals(0, merged[0][0]);
        assertEquals(5, merged[0][1]);
        assertEquals(7, merged[1][0]);
        assertEquals(9, merged[1][1]);
    }
}
