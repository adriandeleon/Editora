package com.editora.editops;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmartSelectStackTest {

    // --- the server-supplied chain (#739) -----------------------------------------------------------

    /** With a chain available, expand follows the server's grammar-accurate ranges instead of the ladder. */
    @Test
    void aServerChainSuppliesTheNextRange() {
        SmartSelectStack st = new SmartSelectStack();
        List<int[]> chain = List.of(new int[] {5, 9}, new int[] {2, 14}, new int[] {0, 30});

        assertArrayEquals(new int[] {5, 9}, st.expand("ignored", 6, 7, chain));
        assertArrayEquals(new int[] {2, 14}, st.expand("ignored", 5, 9, chain));
        assertArrayEquals(new int[] {0, 30}, st.expand("ignored", 2, 14, chain));
    }

    /**
     * The smallest containing range wins regardless of the chain's order — a server that answers
     * outermost-first would otherwise jump straight to the whole file on the first press.
     */
    @Test
    void theSmallestContainingRangeWinsEvenIfTheChainIsReversed() {
        SmartSelectStack st = new SmartSelectStack();
        List<int[]> outermostFirst = List.of(new int[] {0, 30}, new int[] {2, 14}, new int[] {5, 9});

        assertArrayEquals(new int[] {5, 9}, st.expand("ignored", 6, 7, outermostFirst));
    }

    /** A range equal to the live selection would be a press that visibly does nothing. */
    @Test
    void aChainRangeEqualToTheSelectionIsNotOffered() {
        SmartSelectStack st = new SmartSelectStack();
        List<int[]> chain = List.of(new int[] {5, 9}, new int[] {1, 20});

        assertArrayEquals(new int[] {1, 20}, st.expand("ignored", 5, 9, chain), "skips the identical range");
    }

    /** No chain (LSP off, or the request still in flight on the first press) falls back to the local ladder. */
    @Test
    void anAbsentChainFallsBackToTheLocalLadder() {
        String text = "call(a, b, c)";
        SmartSelectStack local = new SmartSelectStack();
        SmartSelectStack withNull = new SmartSelectStack();
        int caret = text.indexOf('b');

        assertArrayEquals(local.expand(text, caret, caret), withNull.expand(text, caret, caret, null), "null chain");
        assertArrayEquals(
                new SmartSelectStack().expand(text, caret, caret),
                new SmartSelectStack().expand(text, caret, caret, List.of()),
                "empty chain");
    }

    /** Shrink retraces what was applied, so a ladder that starts local and continues server-side still pops back. */
    @Test
    void shrinkRetracesALadderThatSwitchedSources() {
        String text = "call(a, b, c)";
        SmartSelectStack st = new SmartSelectStack();
        int caret = text.indexOf('b');

        int[] first = st.expand(text, caret, caret); // local: the word
        int[] second = st.expand(text, first[0], first[1], List.of(new int[] {0, text.length()}));

        assertArrayEquals(new int[] {0, text.length()}, second);
        assertArrayEquals(first, st.shrink(second[0], second[1]));
        assertArrayEquals(new int[] {caret, caret}, st.shrink(first[0], first[1]));
    }

    @Test
    void continuesTracksWhetherTheLadderIsStillLive() {
        String text = "call(a, b, c)";
        SmartSelectStack st = new SmartSelectStack();
        int caret = text.indexOf('b');

        assertFalse(st.continues(caret, caret), "nothing handed out yet");
        int[] first = st.expand(text, caret, caret);
        assertTrue(st.continues(first[0], first[1]));
        assertFalse(st.continues(0, 1), "the user moved the caret");
    }

    /** A malformed chain entry must not throw on the keystroke path. */
    @Test
    void malformedChainEntriesAreIgnored() {
        SmartSelectStack st = new SmartSelectStack();
        List<int[]> chain = new java.util.ArrayList<>();
        chain.add(null);
        chain.add(new int[] {1});
        chain.add(new int[] {0, 10});

        assertArrayEquals(new int[] {0, 10}, st.expand("ignored", 3, 4, chain));
    }

    @Test
    void expandThenShrinkRetracesExactly() {
        String text = "call(a, b, c)";
        SmartSelectStack st = new SmartSelectStack();
        int caret = text.indexOf('b');

        int[] w = st.expand(text, caret, caret); // "b"
        int[] inner = st.expand(text, w[0], w[1]); // "a, b, c"
        int[] parens = st.expand(text, inner[0], inner[1]); // "(a, b, c)"

        // shrink walks back through the exact same ranges
        assertArrayEquals(inner, st.shrink(parens[0], parens[1]));
        assertArrayEquals(w, st.shrink(inner[0], inner[1]));
        assertArrayEquals(new int[] {caret, caret}, st.shrink(w[0], w[1]));
        assertNull(st.shrink(caret, caret), "nothing left to shrink to");
    }

    @Test
    void shrinkDoesNothingWithoutAPriorExpand() {
        SmartSelectStack st = new SmartSelectStack();
        assertNull(st.shrink(0, 1));
    }

    @Test
    void aSelectionChangedBetweenPressesStartsAFreshLadder() {
        String text = "foo(bar) baz(qux)";
        SmartSelectStack st = new SmartSelectStack();

        int[] first = st.expand(text, text.indexOf("bar"), text.indexOf("bar")); // "bar"
        // user clicks elsewhere: the live selection is no longer what we produced
        int otherCaret = text.indexOf("qux");
        int[] fresh = st.expand(text, otherCaret, otherCaret); // starts over → "qux"
        assertArrayEquals(new int[] {otherCaret, otherCaret + 3}, fresh);

        // the old ladder is gone: shrinking from the fresh range pops nothing beyond its own one push
        assertArrayEquals(new int[] {otherCaret, otherCaret}, st.shrink(fresh[0], fresh[1]));
        assertNull(st.shrink(otherCaret, otherCaret));
        // and 'first' is unrelated to the reset
        assertArrayEquals(new int[] {text.indexOf("bar"), text.indexOf("bar") + 3}, first);
    }

    @Test
    void shrinkAfterAnExternalChangeDoesNothing() {
        String text = "call(a, b, c)";
        SmartSelectStack st = new SmartSelectStack();
        int caret = text.indexOf('b');
        int[] inner = st.expand(text, caret, caret); // grew once
        // the user changes the selection by hand, then presses shrink → we must not pop
        assertNull(st.shrink(0, 2));
        // the stack is still intact for a correctly-continued shrink
        assertArrayEquals(new int[] {caret, caret}, st.shrink(inner[0], inner[1]));
    }

    @Test
    void expandStopsAtTheWholeDocument() {
        String text = "abc";
        SmartSelectStack st = new SmartSelectStack();
        int[] doc = st.expand(text, 1, 1); // caret → ... → whole doc
        // keep expanding until null
        int[] cur = doc;
        for (int i = 0; i < 10 && cur != null; i++) {
            int[] nxt = st.expand(text, cur[0], cur[1]);
            if (nxt == null) {
                break;
            }
            cur = nxt;
        }
        assertArrayEquals(new int[] {0, 3}, cur);
        assertNull(st.expand(text, 0, 3), "no expand past the whole document");
    }
}
