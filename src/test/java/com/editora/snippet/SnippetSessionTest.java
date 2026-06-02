package com.editora.snippet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Unit tests for the pure parts of the snippet session: offset shifting and re-indentation. */
class SnippetSessionTest {

    private static List<int[]> ranges(int[]... rs) {
        return new ArrayList<>(List.of(rs));
    }

    @Test
    void typingAtActiveFieldEndGrowsItAndShiftsLater() {
        List<int[]> rs = ranges(new int[]{2, 5}, new int[]{8, 10});
        SnippetSession.shift(rs, 0, 5, 3); // typed 3 chars at the active field's end (pos 5)
        assertArrayEquals(new int[]{2, 8}, rs.get(0)); // active field grew
        assertArrayEquals(new int[]{11, 13}, rs.get(1)); // later range shifted by +3
    }

    @Test
    void typingInsideActiveFieldShiftsEndAndLater() {
        List<int[]> rs = ranges(new int[]{2, 5}, new int[]{8, 10});
        SnippetSession.shift(rs, 0, 3, 1);
        assertArrayEquals(new int[]{2, 6}, rs.get(0));
        assertArrayEquals(new int[]{9, 11}, rs.get(1));
    }

    @Test
    void deletingShrinksActiveFieldAndShiftsLater() {
        List<int[]> rs = ranges(new int[]{2, 5}, new int[]{8, 10});
        SnippetSession.shift(rs, 0, 4, -1);
        assertArrayEquals(new int[]{2, 4}, rs.get(0));
        assertArrayEquals(new int[]{7, 9}, rs.get(1));
    }

    @Test
    void earlierFieldEditDoesNotMoveActiveStartButRangeBeforeStays() {
        // Active is the second field [8,10]; edit happens inside it at pos 9.
        List<int[]> rs = ranges(new int[]{2, 5}, new int[]{8, 10});
        SnippetSession.shift(rs, 1, 9, 2);
        assertArrayEquals(new int[]{2, 5}, rs.get(0)); // earlier field untouched
        assertArrayEquals(new int[]{8, 12}, rs.get(1)); // active field grew
    }

    @Test
    void reindentShiftsContinuationLinesAndRanges() {
        // text "x\ny" with a stop on 'y' (offset 2); indent two spaces.
        ParsedSnippet p = new ParsedSnippet("x\ny",
                List.of(new TabStop(0, List.of(new int[]{2, 2}), "")));
        ParsedSnippet out = SnippetSession.reindent(p, "  ");
        assertEquals("x\n  y", out.text());
        assertArrayEquals(new int[]{4, 4}, out.stops().get(0).ranges().get(0));
    }

    @Test
    void reindentNoNewlineIsUnchanged() {
        ParsedSnippet p = new ParsedSnippet("abc", List.of(new TabStop(0, List.of(new int[]{3, 3}), "")));
        assertEquals(p, SnippetSession.reindent(p, "    "));
    }
}
