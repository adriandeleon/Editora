package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a dropped terminal tab lands: the off-by-one of inserting into a list the tab has just left. */
class TerminalTabReorderTest {

    @Test
    void droppingOnTheLeftOrRightHalfOfATab() {
        assertEquals(1, TerminalTabReorder.insertIndex(0, 1, true, 3), "tab 0 dropped after tab 1");
        assertEquals(0, TerminalTabReorder.insertIndex(0, 1, false, 3), "before tab 1: where it was");
        assertEquals(0, TerminalTabReorder.insertIndex(2, 0, false, 3), "tab 2 before tab 0");
        assertEquals(1, TerminalTabReorder.insertIndex(2, 0, true, 3), "tab 2 after tab 0");
        assertEquals(2, TerminalTabReorder.insertIndex(0, 2, true, 3), "to the end");
        assertEquals(0, TerminalTabReorder.insertIndex(0, 0, true, 1), "a single tab stays");
        assertEquals(1, TerminalTabReorder.insertIndex(1, 5, true, 3), "a bad index is no move");
    }
}
