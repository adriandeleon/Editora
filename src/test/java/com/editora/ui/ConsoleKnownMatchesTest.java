package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The matches an open console find bar keeps current as output is appended, trimmed and cleared. */
class ConsoleKnownMatchesTest {

    private static final Object SOURCE = new Object();

    private static List<int[]> pairs(int... offsets) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < offsets.length; i += 2) {
            out.add(new int[] {offsets[i], offsets[i + 1]});
        }
        return out;
    }

    private static String text(List<int[]> matches) {
        StringBuilder out = new StringBuilder();
        for (int[] m : matches) {
            out.append(m[0]).append('-').append(m[1]).append(' ');
        }
        return out.toString().strip();
    }

    @Test
    void knowsOnlyItsOwnQueryAndSource() {
        ConsoleKnownMatches known = new ConsoleKnownMatches();
        assertFalse(known.knows(SOURCE, "ab", false));
        known.set(SOURCE, "ab", false, pairs(0, 2), 10, false);
        assertTrue(known.knows(SOURCE, "ab", false));
        assertFalse(known.knows(SOURCE, "ab", true));
        assertFalse(known.knows(SOURCE, "abc", false));
        assertFalse(known.knows(new Object(), "ab", false));
        known.set(SOURCE, "ab", false, pairs(0, 2), 10, true);
        assertFalse(known.knows(SOURCE, "ab", false), "a capped list is not complete");
    }

    @Test
    void anAppendLeavesTheMatchesAndTheSearchedEnd() {
        ConsoleKnownMatches known = new ConsoleKnownMatches();
        known.set(SOURCE, "ab", false, pairs(0, 2, 5, 7), 10, false);
        known.changed(10, 0, 4);
        assertEquals(10, known.end());
        assertEquals("0-2 5-7", text(known.before(10)));
        assertEquals("0-2", text(known.before(6)));
    }

    @Test
    void cuttingTheTopShiftsTheMatchesAndDropsTheCutOnes() {
        ConsoleKnownMatches known = new ConsoleKnownMatches();
        known.set(SOURCE, "ab", false, pairs(0, 2, 5, 7, 8, 10), 12, false);
        known.changed(0, 3, 0);
        assertEquals(9, known.end());
        assertEquals("2-4 5-7", text(known.before(9)));
        known.changed(0, 4, 0); // cuts into the first remaining match
        assertEquals("1-3", text(known.before(9)));
        known.changed(0, 100, 0); // cleared
        assertEquals(0, known.end());
        assertTrue(known.before(100).isEmpty());
    }

    @Test
    void anEditInsideMovesTheSearchedEndBack() {
        ConsoleKnownMatches known = new ConsoleKnownMatches();
        known.set(SOURCE, "ab", false, pairs(0, 2, 5, 7), 10, false);
        known.changed(6, 4, 0); // the last line overwritten after a carriage return
        assertEquals(6, known.end());
        known.changed(8, 0, 3); // later than what is searched: nothing to change
        assertEquals(6, known.end());
    }

    @Test
    void changesSinceASnapshotAreReplayed() {
        ConsoleKnownMatches known = new ConsoleKnownMatches();
        known.set(SOURCE, "ab", false, pairs(4, 6), 8, false);
        known.replay(List.of(new int[] {8, 0, 5}, new int[] {0, 2, 0}));
        assertEquals("2-4", text(known.before(known.end())));
        assertEquals(6, known.end());
    }
}
