package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SmartSelectStackTest {

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
