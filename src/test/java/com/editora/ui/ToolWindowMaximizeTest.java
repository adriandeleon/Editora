package com.editora.ui;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ToolWindowMaximizeTest {

    // --- positions -------------------------------------------------------------------------------

    @Test
    void aLeftPanelTakesTheWholeSplit() {
        // [left, editor] — the single divider goes right.
        assertArrayEquals(new double[] {1.0}, ToolWindowMaximize.positions(2, 0));
    }

    @Test
    void aRightPanelTakesTheWholeSplit() {
        // [editor, right] — the single divider goes left.
        assertArrayEquals(new double[] {0.0}, ToolWindowMaximize.positions(2, 1));
    }

    @Test
    void withBothSidesOpenTheDividersCollapseAwayFromTheTarget() {
        // [left, editor, right]: everything before the target shuts, everything from it on opens.
        assertArrayEquals(new double[] {1.0, 1.0}, ToolWindowMaximize.positions(3, 0));
        assertArrayEquals(new double[] {0.0, 1.0}, ToolWindowMaximize.positions(3, 1));
        assertArrayEquals(new double[] {0.0, 0.0}, ToolWindowMaximize.positions(3, 2));
    }

    @Test
    void aSplitWithNothingToCollapseYieldsNoChange() {
        assertEquals(0, ToolWindowMaximize.positions(1, 0).length);
        assertEquals(0, ToolWindowMaximize.positions(0, 0).length);
    }

    @Test
    void anIndexOutsideTheSplitYieldsNoChange() {
        // Guards the caller's indexOf() returning -1 for a panel that isn't in this split.
        assertEquals(0, ToolWindowMaximize.positions(3, -1).length);
        assertEquals(0, ToolWindowMaximize.positions(3, 3).length);
    }

    // --- pick ------------------------------------------------------------------------------------

    @Test
    void theFocusedWindowWins() {
        assertSame("focused", ToolWindowMaximize.pick("focused", List.of("other", "another")));
    }

    @Test
    void withNothingFocusedTheOnlyOpenWindowIsTheTarget() {
        // The palette case: invoking the command necessarily moved focus out of the panel.
        assertSame("only", ToolWindowMaximize.pick(null, List.of("only")));
    }

    @Test
    void withNothingFocusedAndSeveralOpenThereIsNoTarget() {
        // Two open windows have no non-arbitrary answer — the caller reports rather than guesses.
        assertNull(ToolWindowMaximize.pick(null, List.of("a", "b")));
    }

    @Test
    void withNothingOpenThereIsNoTarget() {
        assertNull(ToolWindowMaximize.pick(null, List.of()));
    }
}
