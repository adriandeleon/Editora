package com.editora.editops;

import com.editora.editops.AutoClose.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the pure auto-close decision logic. */
class AutoCloseTest {

    private static Action act(char typed, char prev, char next, boolean sel) {
        return AutoClose.decide(typed, prev, next, sel).action();
    }

    @Test
    void closerForMapping() {
        assertEquals(')', AutoClose.closerFor('('));
        assertEquals(']', AutoClose.closerFor('['));
        assertEquals('}', AutoClose.closerFor('{'));
        assertEquals('"', AutoClose.closerFor('"'));
        assertEquals('`', AutoClose.closerFor('`'));
        assertEquals(0, AutoClose.closerFor('a'));
    }

    @Test
    void openerInsertsPair() {
        AutoClose.Decision d = AutoClose.decide('(', ' ', ' ', false);
        assertEquals(Action.INSERT_PAIR, d.action());
        assertEquals(')', d.closer());
        assertEquals(Action.INSERT_PAIR, act('{', (char) 0, (char) 0, false));
    }

    @Test
    void openerOrQuoteWrapsSelection() {
        assertEquals(Action.WRAP_SELECTION, act('(', 'x', 'y', true));
        assertEquals(Action.WRAP_SELECTION, act('"', 'x', 'y', true));
    }

    @Test
    void typeOverExistingCloserOrQuote() {
        assertEquals(Action.SKIP_OVER, act(')', 'x', ')', false));
        assertEquals(Action.SKIP_OVER, act('"', 'x', '"', false));
        assertEquals(Action.NONE, act(')', 'x', 'y', false)); // plain closer, normal insert
    }

    @Test
    void closerDoesNotSkipOverWithSelection() {
        // With a selection, a typed closer must NOT skip-over (that silently dropped the selection);
        // it falls through to NONE so RichTextFX replaces the selection normally.
        assertEquals(Action.NONE, act('}', 'b', '}', true));
        assertEquals(Action.NONE, act(')', 'x', ')', true));
        // A quote with a selection still wraps it (the wrap branch runs before skip-over).
        assertEquals(Action.WRAP_SELECTION, act('"', 'b', '"', true));
    }

    @Test
    void quotePairingHeuristics() {
        assertEquals(Action.INSERT_PAIR, act('"', ' ', ' ', false)); // open space → pair
        assertEquals(Action.INSERT_PAIR, act('"', '(', (char) 0, false));
        assertEquals(Action.NONE, act('\'', 'n', ' ', false)); // don't  → no pair
        assertEquals(Action.NONE, act('"', 'x', ' ', false)); // after a word char
        assertEquals(Action.NONE, act('"', ' ', 'x', false)); // before a word char
        assertEquals(Action.NONE, act('"', '"', ' ', false)); // after another quote
    }

    @Test
    void emptyPairDetection() {
        assertTrue(AutoClose.isEmptyPair('(', ')'));
        assertTrue(AutoClose.isEmptyPair('"', '"'));
        assertFalse(AutoClose.isEmptyPair('(', ']'));
        assertFalse(AutoClose.isEmptyPair('a', 'b'));
    }
}
