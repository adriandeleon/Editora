package com.editora.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.editora.config.Breakpoint;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

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
    void mergePreservingOrderKeepsPreviousOrderThenAppendsNew() {
        List<Breakpoint> prev = List.of(Breakpoint.plain(5, "five"), Breakpoint.plain(2, "two"));
        List<Breakpoint> current =
                List.of(Breakpoint.plain(2, "two"), Breakpoint.plain(5, "five"), Breakpoint.plain(9, "nine"));
        List<Breakpoint> merged = com.editora.config.BreakpointStore.mergePreservingOrder(prev, current);
        assertEquals(5, merged.get(0).line()); // prev order preserved (5 before 2)
        assertEquals(2, merged.get(1).line());
        assertEquals(9, merged.get(2).line()); // new one appended
    }
}
