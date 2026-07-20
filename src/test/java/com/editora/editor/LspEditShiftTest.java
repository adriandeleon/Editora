package com.editora.editor;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LspEditShiftTest {

    /** Accepting a single-line identifier: `f` at line 10 cols 4–5 becomes `foobar`, cols 4–10. */
    private static LspEditShift.Change singleLineAccept() {
        return new LspEditShift.Change(10, 4, 10, 5, 10, 10);
    }

    /** Accepting a 3-line snippet at line 10 col 4, ending at line 12 col 1. */
    private static LspEditShift.Change multiLineAccept() {
        return new LspEditShift.Change(10, 4, 10, 5, 12, 1);
    }

    private static LspTextEdit importEdit(int line) {
        return new LspTextEdit(line, 0, line, 0, "import { Foo } from './foo';\n");
    }

    @Test
    void leavesAnImportAboveTheCaretAlone() {
        List<LspTextEdit> out = LspEditShift.shift(List.of(importEdit(2)), multiLineAccept());
        assertEquals(1, out.size());
        assertEquals(2, out.get(0).startLine());
        assertEquals(0, out.get(0).startCol());
    }

    /** The bug: a multi-line accept moves everything below the caret, so an edit below it must move too. */
    @Test
    void shiftsAnEditBelowTheCaretByTheLineDelta() {
        List<LspTextEdit> out = LspEditShift.shift(List.of(importEdit(40)), multiLineAccept());
        assertEquals(42, out.get(0).startLine());
        assertEquals(42, out.get(0).endLine());
        assertEquals(0, out.get(0).startCol());
    }

    /** An edit sharing the accept's last line also moves by the column delta. */
    @Test
    void shiftsColumnsOnTheAcceptsOwnLine() {
        LspTextEdit sameLine = new LspTextEdit(10, 20, 10, 23, "Bar");
        List<LspTextEdit> out = LspEditShift.shift(List.of(sameLine), singleLineAccept());
        assertEquals(10, out.get(0).startLine());
        assertEquals(25, out.get(0).startCol()); // +5: `f` (1 char) became `foobar` (6)
        assertEquals(28, out.get(0).endCol());
    }

    /** A multi-line accept moves a following same-line edit onto the new last line, columns rebased. */
    @Test
    void movesAFollowingSameLineEditOntoTheNewLastLine() {
        LspTextEdit sameLine = new LspTextEdit(10, 20, 10, 23, "Bar");
        List<LspTextEdit> out = LspEditShift.shift(List.of(sameLine), multiLineAccept());
        assertEquals(12, out.get(0).startLine());
        assertEquals(16, out.get(0).startCol()); // 20 + (1 - 5)
    }

    /** A position inside the replaced range addresses text that no longer exists — drop it, never guess. */
    @Test
    void dropsAnEditInsideTheReplacedRange() {
        LspTextEdit inside = new LspTextEdit(10, 4, 10, 5, "x");
        // A wider accept: cols 4..12 replaced, so an edit at cols 6..7 is strictly inside.
        LspEditShift.Change wide = new LspEditShift.Change(10, 4, 10, 12, 10, 20);
        LspTextEdit within = new LspTextEdit(10, 6, 10, 7, "y");
        assertTrue(LspEditShift.shift(List.of(within), wide).isEmpty());
        // The range's own boundaries are not "inside": start is untouched, end shifts.
        assertEquals(1, LspEditShift.shift(List.of(inside), singleLineAccept()).size());
    }

    /** Boundaries: at the replaced range's start is untouched; at its end moves with the end. */
    @Test
    void treatsRangeBoundariesAsOutsideTheReplacedText() {
        int[] atStart = LspEditShift.shiftPosition(10, 4, singleLineAccept());
        assertEquals(10, atStart[0]);
        assertEquals(4, atStart[1]);
        int[] atEnd = LspEditShift.shiftPosition(10, 5, singleLineAccept());
        assertEquals(10, atEnd[0]);
        assertEquals(10, atEnd[1]); // rides the end of the accepted text
    }

    /** A pure insertion (nothing replaced) never drops anything — there is no inside. */
    @Test
    void pureInsertionDropsNothing() {
        LspEditShift.Change insertion = new LspEditShift.Change(10, 4, 10, 4, 12, 0);
        List<LspTextEdit> out = LspEditShift.shift(List.of(importEdit(2), importEdit(40)), insertion);
        assertEquals(2, out.size());
        assertEquals(2, out.get(0).startLine());
        assertEquals(42, out.get(1).startLine());
    }

    /** An accept that moved nothing after it (identical text) returns the list untouched. */
    @Test
    void identityChangeIsAPassThrough() {
        List<LspTextEdit> edits = List.of(importEdit(2));
        LspEditShift.Change nothingMoved = new LspEditShift.Change(10, 4, 10, 9, 10, 9);
        assertSame(edits, LspEditShift.shift(edits, nothingMoved));
        assertTrue(nothingMoved.identity());
    }

    /** No measured change (nothing accepted yet, or a non-completion caller) is a pass-through, not a crash. */
    @Test
    void nullChangeIsAPassThrough() {
        List<LspTextEdit> edits = List.of(importEdit(2));
        assertSame(edits, LspEditShift.shift(edits, null));
        assertEquals(List.of(), LspEditShift.shift(List.of(), multiLineAccept()));
    }

    /** Deleting lines (an accept shorter than what it replaced) shifts following edits upward. */
    @Test
    void shiftsUpwardWhenTheAcceptRemovesLines() {
        LspEditShift.Change shrink = new LspEditShift.Change(10, 0, 13, 0, 10, 3);
        List<LspTextEdit> out = LspEditShift.shift(List.of(importEdit(40)), shrink);
        assertEquals(37, out.get(0).startLine());
    }
}
