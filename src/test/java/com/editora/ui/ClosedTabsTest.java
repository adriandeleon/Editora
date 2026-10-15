package com.editora.ui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClosedTabsTest {

    private static final Path A = Path.of("/w/a.txt");
    private static final Path B = Path.of("/w/b.txt");
    private static final Path C = Path.of("/w/c.txt");

    @Test
    void popsTheMostRecentlyClosedFirst() {
        ClosedTabs closed = new ClosedTabs();
        closed.closed(A, 3);
        closed.closed(B, 7);
        assertEquals(new ClosedTabs.Entry(B, 7), closed.pop());
        assertEquals(new ClosedTabs.Entry(A, 3), closed.pop());
        assertNull(closed.pop());
    }

    @Test
    void closingAFileAgainMovesItToTheTopOnce() {
        ClosedTabs closed = new ClosedTabs();
        closed.closed(A, 1);
        closed.closed(B, 2);
        closed.closed(A, 9);
        assertEquals(2, closed.size());
        assertEquals(new ClosedTabs.Entry(A, 9), closed.pop());
        assertEquals(B, closed.pop().path());
    }

    @Test
    void keepsOnlyTheNewestUpToItsCapacity() {
        ClosedTabs closed = new ClosedTabs(2);
        closed.closed(A, 0);
        closed.closed(B, 0);
        closed.closed(C, 0);
        assertEquals(2, closed.size());
        assertEquals(C, closed.pop().path());
        assertEquals(B, closed.pop().path());
        assertNull(closed.pop());
    }

    @Test
    void forgetsAFileAndIgnoresUntitledAndNegativeCarets() {
        ClosedTabs closed = new ClosedTabs();
        closed.closed(null, 4);
        assertEquals(0, closed.size());
        closed.closed(A, -5);
        closed.closed(B, 0);
        closed.forget(B);
        assertEquals(new ClosedTabs.Entry(A, 0), closed.pop());

        ClosedTabs one = new ClosedTabs(0); // a capacity below one is one
        one.closed(A, 0);
        one.closed(B, 0);
        assertEquals(1, one.size());
        assertEquals(B, one.pop().path());
    }
}
