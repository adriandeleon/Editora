package com.editora.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The start line of an incremental highlight pass, across passes that never apply. */
class HighlightStartTest {

    @Test
    void aFreshBufferOwesTheWholeDocument() {
        assertEquals(0, new HighlightStart().dispatch());
    }

    @Test
    void aSupersededPassKeepsItsStartLineForTheNextOne() {
        HighlightStart start = new HighlightStart();
        start.dispatch();
        start.applied(); // the initial full pass landed

        start.edited(10);
        assertEquals(10, start.dispatch(), "pass A starts at the edit");
        // Before A applies, a second edit lands further down and pass B is dispatched; A is discarded.
        start.edited(50);
        assertEquals(10, start.dispatch(), "B must still cover line 10: A never re-tokenized it");
        // B is superseded too (another keystroke) — the debt carries on.
        start.edited(80);
        assertEquals(10, start.dispatch());

        start.applied();
        start.edited(70);
        assertEquals(70, start.dispatch(), "once a pass applied, only new edits are owed");
    }

    @Test
    void aPassDiscardedForALengthMismatchIsOwedToo() {
        HighlightStart start = new HighlightStart();
        start.dispatch();
        start.applied();

        start.edited(4);
        start.dispatch(); // never applied: the document changed length before it came back
        start.edited(9);
        assertEquals(4, start.dispatch());
    }

    @Test
    void theEarliestEditWinsAndEditsAboveAnInFlightPassLowerTheStart() {
        HighlightStart start = new HighlightStart();
        start.dispatch();
        start.applied();

        start.edited(30);
        start.edited(12);
        start.edited(40);
        assertEquals(12, start.dispatch());
        start.edited(3);
        assertEquals(3, start.dispatch());
    }

    @Test
    void invalidateForcesAFullPassEvenAfterOneApplied() {
        HighlightStart start = new HighlightStart();
        start.dispatch();
        start.applied();
        start.edited(25);
        start.invalidate(); // language changed
        assertEquals(0, start.dispatch());
    }

    @Test
    void withNothingOwedTheStartIsTheNoLineSentinel() {
        HighlightStart start = new HighlightStart();
        start.dispatch();
        start.applied();
        assertEquals(Integer.MAX_VALUE, start.dispatch(), "the caller has no end-state for it → a full pass");
    }
}
