package com.editora.terminal;

import com.jediterm.core.compatibility.Point;
import com.jediterm.terminal.model.CharBuffer;
import com.jediterm.terminal.model.SelectionUtil;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalSelection;
import com.jediterm.terminal.model.TerminalTextBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The highlight covers exactly what copy produces. */
class SelectionGeometryTest {

    private static TerminalTextBuffer bufferWith(String row0, String row1) {
        TerminalTextBuffer buffer = new TerminalTextBuffer(40, 4, new StyleState());
        buffer.writeString(0, 1, new CharBuffer(row0));
        buffer.writeString(0, 2, new CharBuffer(row1));
        return buffer;
    }

    @Test
    void theHighlightMatchesTheCopyOnOneRow() {
        // The bug this pins: a drag over "hello" to its last cell highlighted five and copied four.
        TerminalTextBuffer buffer = bufferWith("hello world", "");
        TerminalSelection selection = new TerminalSelection(new Point(0, 0), new Point(4, 0));
        assertEquals("hell", SelectionUtil.getSelectionText(selection, buffer));
        assertArrayEquals(new int[] {0, 4}, SelectionGeometry.onRow(selection, 0, 40));
    }

    @Test
    void aMultiRowSelectionCoversTheFirstRowToItsEndAndTheLastFromItsStart() {
        TerminalSelection selection = new TerminalSelection(new Point(3, 0), new Point(2, 2));
        assertArrayEquals(new int[] {3, 40}, SelectionGeometry.onRow(selection, 0, 40));
        assertArrayEquals(new int[] {0, 40}, SelectionGeometry.onRow(selection, 1, 40));
        assertArrayEquals(new int[] {0, 2}, SelectionGeometry.onRow(selection, 2, 40));
        assertNull(SelectionGeometry.onRow(selection, 3, 40));
        assertNull(SelectionGeometry.onRow(selection, -1, 40));
    }

    @Test
    void theEndpointsMayArriveInEitherOrder() {
        TerminalSelection backwards = new TerminalSelection(new Point(2, 2), new Point(3, 0));
        assertArrayEquals(new int[] {3, 40}, SelectionGeometry.onRow(backwards, 0, 40));
        assertArrayEquals(new int[] {0, 2}, SelectionGeometry.onRow(backwards, 2, 40));
    }

    @Test
    void anEndAtColumnZeroCoversNoneOfItsRow() {
        // Copy adds the newline for the row and none of its text, so there is nothing to wash.
        TerminalSelection selection = new TerminalSelection(new Point(0, 0), new Point(0, 1));
        assertNull(SelectionGeometry.onRow(selection, 1, 40));
    }

    // --- drag ---------------------------------------------------------------------------------

    @Test
    void aForwardDragSelectsFromTheAnchorToTheBoundary() {
        TerminalSelection drag = SelectionGeometry.drag(new Point(2, 0), 0, 7);
        assertEquals(2, drag.getStart().x);
        assertEquals(7, drag.getEnd().x);
    }

    @Test
    void aBackwardDragStillIncludesTheCellThatWasPressed() {
        // Press on cell 5, drag left to the boundary before cell 2: cells 2..5 inclusive.
        TerminalTextBuffer buffer = bufferWith("abcdefghij", "");
        TerminalSelection drag = SelectionGeometry.drag(new Point(5, 0), 0, 2);
        assertEquals("cdef", SelectionUtil.getSelectionText(drag, buffer));
    }

    @Test
    void draggingAcrossRowsGoesBothWays() {
        TerminalSelection down = SelectionGeometry.drag(new Point(5, 0), 2, 1);
        assertEquals(new Point(5, 0).toString(), down.getStart().toString());
        assertEquals(new Point(1, 2).toString(), down.getEnd().toString());

        TerminalSelection up = SelectionGeometry.drag(new Point(5, 2), 0, 8);
        assertEquals(new Point(8, 0).toString(), up.getStart().toString());
        assertEquals(new Point(6, 2).toString(), up.getEnd().toString());
    }

    @Test
    void thePressCellAloneIsOneCellWhicheverSideThePointerIsOn() {
        TerminalTextBuffer buffer = bufferWith("abcdefghij", "");
        assertEquals("d", SelectionUtil.getSelectionText(SelectionGeometry.drag(new Point(3, 0), 0, 3), buffer));
        assertEquals("d", SelectionUtil.getSelectionText(SelectionGeometry.drag(new Point(3, 0), 0, 4), buffer));
    }

    @Test
    void thereIsNoSelectionUntilThePointerLeavesThePressCell() {
        Point anchor = new Point(3, 0);
        assertFalse(SelectionGeometry.leftPressCell(anchor, 0, 3));
        assertFalse(SelectionGeometry.leftPressCell(anchor, 0, 4));
        assertTrue(SelectionGeometry.leftPressCell(anchor, 0, 2));
        assertTrue(SelectionGeometry.leftPressCell(anchor, 0, 5));
        assertTrue(SelectionGeometry.leftPressCell(anchor, 1, 3));
    }
}
