package com.editora.editor;

import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;

import com.editora.config.Breakpoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BreakpointManagerTest {

    private static NavigableMap<Integer, Breakpoint> map(int... lines) {
        NavigableMap<Integer, Breakpoint> m = new TreeMap<>();
        for (int l : lines) {
            m.put(l, Breakpoint.plain(l, "line" + l));
        }
        return m;
    }

    @Test
    void shiftMovesBreakpointsBelowAnInsertion() {
        NavigableMap<Integer, Breakpoint> in = map(2, 5);
        // Insert one newline at the start of line 3 → breakpoints strictly below move down by 1.
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.shift(in, 3, true, 0, 1, 11);
        assertTrue(out.containsKey(2)); // above the edit — unchanged
        assertTrue(out.containsKey(6)); // 5 → 6
        assertFalse(out.containsKey(5));
    }

    @Test
    void shiftDropsBreakpointInsideDeletedSpan() {
        NavigableMap<Integer, Breakpoint> in = map(2, 4, 7);
        // Delete 2 newlines starting at line 3 (line start): removes lines 3..5; bp at 4 is dropped, 7→5.
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.shift(in, 3, true, 2, 0, 8);
        assertTrue(out.containsKey(2));
        assertFalse(out.containsKey(4)); // inside the deleted span
        assertTrue(out.containsKey(5)); // 7 → 5
    }

    @Test
    void shiftLeavesBreakpointPutForIntraLineEditAtPivot() {
        NavigableMap<Integer, Breakpoint> in = map(3);
        // Edit not at line start on line 3 (pivot = 3) adding a line → bp on 3 stays, below would move.
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.shift(in, 3, false, 0, 1, 11);
        assertTrue(out.containsKey(3));
    }

    @Test
    void reanchorKeepsExactMatchInPlace() {
        List<Breakpoint> saved = List.of(Breakpoint.plain(1, "bbb"));
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.reanchor(
                saved, 3, line -> List.of("aaa", "bbb", "ccc").get(line), 100);
        assertTrue(out.containsKey(1));
    }

    @Test
    void reanchorMovesToNearestMatchingLine() {
        // Saved at line 1 with text "target", but the file now has "target" at line 3 (drifted down).
        List<Breakpoint> saved = List.of(Breakpoint.plain(1, "target"));
        List<String> doc = List.of("a", "b", "c", "target", "e");
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.reanchor(saved, doc.size(), doc::get, 100);
        assertTrue(out.containsKey(3));
        assertFalse(out.containsKey(1));
    }

    @Test
    void reanchorNeverCollapsesTwoBreakpointsOntoOneLine() {
        // Two breakpoints on ADJACENT IDENTICAL lines (two `});` in a row — routine in real code), then a
        // line is added above outside the editor (git pull / a formatter). Both lines still exist, so both
        // breakpoints must survive: the map is keyed by line, so re-anchoring them both onto the same line
        // silently drops one — and restore() then persists the loss, so it never comes back.
        List<String> doc =
                new java.util.ArrayList<>(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "});", "});", "z"));
        doc.add(0, "// added upstream"); // everything below shifts down one: 10,11 -> 11,12
        List<Breakpoint> saved = List.of(Breakpoint.plain(10, "});"), Breakpoint.plain(11, "});"));

        NavigableMap<Integer, Breakpoint> out = BreakpointManager.reanchor(saved, doc.size(), doc::get, 2000);

        assertEquals(2, out.size(), "both breakpoints must survive — neither line was deleted");
        assertTrue(out.containsKey(11));
        assertTrue(out.containsKey(12));
    }

    @Test
    void reanchorStepsAsideRatherThanOverwriteWhenContentIsGone() {
        // Both breakpoints' text has vanished, so both fall back to their stored line — and a shrunken
        // file clamps them onto the same last line. Neither may swallow the other.
        List<String> doc = List.of("only", "two");
        List<Breakpoint> saved = List.of(Breakpoint.plain(40, "gone();"), Breakpoint.plain(50, "gone();"));

        NavigableMap<Integer, Breakpoint> out = BreakpointManager.reanchor(saved, doc.size(), doc::get, 2000);

        assertEquals(2, out.size(), "a clamp collision must not drop a breakpoint");
    }

    @Test
    void mergePreservingOrderKeepsPreviousOrderThenAppendsNew() {
        List<Breakpoint> prev = List.of(Breakpoint.plain(5, "five"), Breakpoint.plain(2, "two"));
        List<Breakpoint> current =
                List.of(Breakpoint.plain(2, "two"), Breakpoint.plain(5, "five"), Breakpoint.plain(9, "nine"));
        List<Breakpoint> merged = com.editora.config.BreakpointStore.mergePreservingOrder(prev, current);
        assertEquals(5, merged.get(0).line()); // prev order preserved (5 before 2)
        assertEquals(2, merged.get(1).line());
        assertEquals(9, merged.get(2).line()); // new one appended
    }

    @Test
    void joiningALineUpwardKeepsTheBreakpointOnTheJoinLine() {
        // foo() on line 18, bar() on line 19 with a breakpoint. Backspace at column 0 of line 19 removes only
        // the newline (mid-line delete: startLine=18, atLineStart=false, removedNL=1), joining bar() onto
        // line 18. bar()'s code survives, so its breakpoint must follow to line 18 — not silently vanish.
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.shift(map(19), 18, false, 1, 0, 30);
        assertTrue(out.containsKey(18), "the breakpoint on the join line follows its code to line 18");
        assertFalse(out.containsKey(19), "line 19 no longer exists — the breakpoint wasn't just dropped");
    }

    @Test
    void aMidLineDeleteSpanningLinesKeepsTheTrailingSurvivorButDropsTheMiddle() {
        // Delete from mid-line 18 through the start of line 20 (removedNL=2, atLineStart=false): line 19 is
        // consumed, line 20's content merges onto 18. A bp on 19 is dropped; a bp on 20 follows to 18.
        NavigableMap<Integer, Breakpoint> out = BreakpointManager.shift(map(19, 20), 18, false, 2, 0, 30);
        assertFalse(out.containsKey(19), "the fully-deleted middle line's breakpoint is dropped");
        assertTrue(out.containsKey(18), "the trailing survivor line's breakpoint follows to the join line");
    }
}
