package com.editora.snippet;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Unit tests for the pure parts of the snippet session: offset shifting and re-indentation. */
class SnippetSessionTest {

    private static List<int[]> ranges(int[]... rs) {
        return new ArrayList<>(List.of(rs));
    }

    @Test
    void typingAtActiveFieldEndGrowsItAndShiftsLater() {
        List<int[]> rs = ranges(new int[] {2, 5}, new int[] {8, 10});
        SnippetSession.shift(rs, 0, 5, 0, 3); // typed 3 chars at the active field's end (pos 5)
        assertArrayEquals(new int[] {2, 8}, rs.get(0)); // active field grew
        assertArrayEquals(new int[] {11, 13}, rs.get(1)); // later range shifted by +3
    }

    @Test
    void typingInsideActiveFieldShiftsEndAndLater() {
        List<int[]> rs = ranges(new int[] {2, 5}, new int[] {8, 10});
        SnippetSession.shift(rs, 0, 3, 0, 1);
        assertArrayEquals(new int[] {2, 6}, rs.get(0));
        assertArrayEquals(new int[] {9, 11}, rs.get(1));
    }

    @Test
    void deletingShrinksActiveFieldAndShiftsLater() {
        List<int[]> rs = ranges(new int[] {2, 5}, new int[] {8, 10});
        SnippetSession.shift(rs, 0, 4, 1, 0);
        assertArrayEquals(new int[] {2, 4}, rs.get(0));
        assertArrayEquals(new int[] {7, 9}, rs.get(1));
    }

    @Test
    void earlierFieldEditDoesNotMoveActiveStartButRangeBeforeStays() {
        // Active is the second field [8,10]; edit happens inside it at pos 9.
        List<int[]> rs = ranges(new int[] {2, 5}, new int[] {8, 10});
        SnippetSession.shift(rs, 1, 9, 0, 2);
        assertArrayEquals(new int[] {2, 5}, rs.get(0)); // earlier field untouched
        assertArrayEquals(new int[] {8, 12}, rs.get(1)); // active field grew
    }

    @Test
    void aFieldStartingWhereTheActiveFieldEndsIsPushedByTypingAtThatEnd() {
        // `for ${1:_, }${2:v}` after "_, " was replaced by "i": $1 = [4,5], $2 = [5,6], and "," is typed at 5.
        // $2 used to keep its start and grow over the typed text.
        List<int[]> rs = ranges(new int[] {4, 5}, new int[] {5, 6});
        SnippetSession.shift(rs, 0, 5, 0, 1);
        assertArrayEquals(new int[] {4, 6}, rs.get(0), "the active field grew");
        assertArrayEquals(new int[] {6, 7}, rs.get(1), "the next field moved, same length");
    }

    @Test
    void theFinalCaretDirectlyAfterTheActiveFieldStaysAfterWhatIsTypedThere() {
        // `val ${1:name} = ${2:value}` with $0 at the very end: [16,16] sits exactly at $2's end.
        List<int[]> rs = ranges(new int[] {11, 16}, new int[] {16, 16});
        SnippetSession.shift(rs, 0, 11, 5, 1); // "value" replaced by "x"
        assertArrayEquals(new int[] {11, 12}, rs.get(0));
        assertArrayEquals(new int[] {12, 12}, rs.get(1));
        SnippetSession.shift(rs, 0, 12, 0, 1); // then "y" typed at the field's end
        assertArrayEquals(new int[] {11, 13}, rs.get(0));
        assertArrayEquals(new int[] {13, 13}, rs.get(1), "$0 is after the typed text, not inside it");
    }

    @Test
    void offsetsInsideARemovedSpanCollapseToTheEditInsteadOfMovingInFrontOfIt() {
        // An active field [20,35] with fields nested at [21,30] and [34,35], replaced whole by one character.
        // Shifted by the delta (-14) the first nested range landed at [7,16] — in text before the snippet.
        List<int[]> rs = ranges(new int[] {20, 35}, new int[] {21, 30}, new int[] {34, 35}, new int[] {40, 44});
        SnippetSession.shift(rs, 0, 20, 15, 1);
        assertArrayEquals(new int[] {20, 21}, rs.get(0));
        assertArrayEquals(new int[] {20, 20}, rs.get(1), "nested in the removed text: collapsed to the edit");
        assertArrayEquals(new int[] {20, 20}, rs.get(2), "even though it ended exactly where the removal did");
        assertArrayEquals(new int[] {26, 30}, rs.get(3), "after the edit: shifted by the net change");
    }

    @Test
    void aRangeOnlyPartlyInsideTheRemovalKeepsItsSurvivingPart() {
        // Removal [10,14) inside the active field [8,20]; another range [12,18] straddles its end.
        List<int[]> rs = ranges(new int[] {8, 20}, new int[] {12, 18});
        SnippetSession.shift(rs, 0, 10, 4, 0);
        assertArrayEquals(new int[] {8, 16}, rs.get(0));
        assertArrayEquals(new int[] {10, 14}, rs.get(1));
    }

    @Test
    void aRangeStartingAtAnInsertionInsideTheActiveFieldDoesNotMove() {
        // Insertion at the active field's START (not its end): an empty stop there may be a neighbour in front.
        List<int[]> rs = ranges(new int[] {5, 9}, new int[] {5, 5});
        SnippetSession.shift(rs, 0, 5, 0, 2);
        assertArrayEquals(new int[] {5, 11}, rs.get(0));
        assertArrayEquals(new int[] {5, 5}, rs.get(1));
    }

    @Test
    void reindentShiftsContinuationLinesAndRanges() {
        // text "x\ny" with a stop on 'y' (offset 2); indent two spaces.
        ParsedSnippet p = new ParsedSnippet("x\ny", List.of(new TabStop(0, List.of(new int[] {2, 2}), "")));
        ParsedSnippet out = SnippetSession.reindent(p, "  ");
        assertEquals("x\n  y", out.text());
        assertArrayEquals(new int[] {4, 4}, out.stops().get(0).ranges().get(0));
    }

    @Test
    void reindentNoNewlineIsUnchanged() {
        ParsedSnippet p = new ParsedSnippet("abc", List.of(new TabStop(0, List.of(new int[] {3, 3}), "")));
        assertEquals(p, SnippetSession.reindent(p, "    "));
    }

    @Test
    void reindentPreservesChoicesSoTheDropdownSurvives() {
        // A multi-line choice snippet expanded at an indent runs through reindent, which used to rebuild the
        // TabStop with the 3-arg ctor and drop the choices → no dropdown.
        TabStop choice = new TabStop(1, List.of(new int[] {2, 3}), "a", List.of("a", "b", "c"));
        ParsedSnippet p = new ParsedSnippet("{\na\n}", List.of(choice));
        ParsedSnippet out = SnippetSession.reindent(p, "  ");
        TabStop s = out.stops().get(0);
        assertEquals(List.of("a", "b", "c"), s.choices());
        assertEquals(true, s.hasChoices());
    }

    // --- normalize: line endings and the buffer's indent unit (A4-3) ---

    @Test
    void normalizeConvertsLeadingTabsToTheIndentUnitAndShiftsStops() {
        // "key:\n\t${2:subkey}: v" — the stop sits after the tab.
        ParsedSnippet p =
                new ParsedSnippet("key:\n\tsubkey: v", List.of(new TabStop(2, ranges(new int[] {6, 12}), "")));
        ParsedSnippet out = SnippetSession.normalize(p, "  ");
        assertEquals("key:\n  subkey: v", out.text());
        assertArrayEquals(new int[] {7, 13}, out.stops().get(0).ranges().get(0));
    }

    @Test
    void normalizeKeepsTabsForATabIndentedBufferAndTabsInsideALine() {
        ParsedSnippet p = new ParsedSnippet("a:\n\tb\tc", List.of());
        assertEquals("a:\n\tb\tc", SnippetSession.normalize(p, "\t").text());
        assertEquals("a:\n\tb\tc", SnippetSession.normalize(p, null).text());
        assertEquals("a:\n    b\tc", SnippetSession.normalize(p, "    ").text(), "only indentation is converted");
    }

    @Test
    void normalizeCollapsesCrLfSoStopsMatchWhatTheAreaStores() {
        // if (${1:cond}) {\r\n\t${2:body}\r\n}${3:tail}$0
        String t = "if (cond) {\r\n\tbody\r\n}tail";
        ParsedSnippet p = new ParsedSnippet(
                t,
                List.of(
                        new TabStop(1, ranges(new int[] {4, 8}), ""),
                        new TabStop(2, ranges(new int[] {14, 18}), ""),
                        new TabStop(3, ranges(new int[] {21, 25}), ""),
                        new TabStop(0, ranges(new int[] {25, 25}), "")));
        ParsedSnippet out = SnippetSession.normalize(p, null);
        assertEquals("if (cond) {\n\tbody\n}tail", out.text());
        assertEquals(
                "body",
                out.text()
                        .substring(
                                out.stops().get(1).ranges().get(0)[0],
                                out.stops().get(1).ranges().get(0)[1]));
        assertEquals(
                "tail",
                out.text()
                        .substring(
                                out.stops().get(2).ranges().get(0)[0],
                                out.stops().get(2).ranges().get(0)[1]));
        assertArrayEquals(new int[] {23, 23}, out.stops().get(3).ranges().get(0));
        assertEquals(
                "a\nb\nc",
                SnippetSession.normalize(new ParsedSnippet("a\rb\r\nc", List.of()), null)
                        .text());
    }

    // --- shift with the parser's nesting order in slots 2 and 3 ---

    @Test
    void identicalOffsetsAreToldApartByTheNestingOrder() {
        // `${2:${1}foo}`: $1 = [10,10] nested at the start of $2 = [10,13]. Typing in $1 grows $2.
        List<int[]> nested = ranges(new int[] {10, 10, 1, 2}, new int[] {10, 13, 0, 3});
        SnippetSession.shift(nested, 0, 10, 0, 1);
        assertArrayEquals(new int[] {10, 11, 1, 2}, nested.get(0));
        assertArrayEquals(new int[] {10, 14, 0, 3}, nested.get(1), "the parent keeps its start");
        // `${1}${2:foo}`: the same offsets, but $2 follows $1 and is pushed.
        List<int[]> adjacent = ranges(new int[] {10, 10, 0, 1}, new int[] {10, 13, 2, 3});
        SnippetSession.shift(adjacent, 0, 10, 0, 1);
        assertArrayEquals(new int[] {11, 14, 2, 3}, adjacent.get(1), "the neighbour moves, same length");
        // `$1$2` with $2 active: the empty stop in front of it stays in front.
        List<int[]> before = ranges(new int[] {5, 5, 0, 1}, new int[] {5, 5, 2, 3});
        SnippetSession.shift(before, 1, 5, 0, 1);
        assertArrayEquals(new int[] {5, 5, 0, 1}, before.get(0));
        assertArrayEquals(new int[] {5, 6, 2, 3}, before.get(1));
    }

    @Test
    void aStopAroundARewrittenMirrorFollowsItInsteadOfCollapsing() {
        // `${4:$1}`: the mirror [73,74] is rewritten to 4 characters; $4 has the same extent, around it.
        List<int[]> rs = ranges(new int[] {73, 74, 5, 6}, new int[] {73, 74, 4, 7});
        SnippetSession.shift(rs, 0, 73, 1, 4);
        assertArrayEquals(new int[] {73, 77, 5, 6}, rs.get(0));
        assertArrayEquals(new int[] {73, 77, 4, 7}, rs.get(1));
    }
}
