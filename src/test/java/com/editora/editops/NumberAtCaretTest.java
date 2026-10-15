package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class NumberAtCaretTest {

    /** The text after the change, with | at the caret. */
    private static String change(String marked, long delta) {
        int caret = marked.indexOf('|');
        String text = marked.replace("|", "");
        Indenter.TabEdit e = NumberAtCaret.change(text, caret, caret, delta);
        if (e == null) {
            return null;
        }
        String after = text.substring(0, e.from()) + e.replacement() + text.substring(e.to());
        return after.substring(0, e.selStart()) + "|" + after.substring(e.selStart());
    }

    @Test
    void changesTheNumberTheCaretIsOnOrTheNextOneOnTheLine() {
        assertEquals("x = 43| + 1", change("x = 4|2 + 1", 1));
        assertEquals("x = 43| + 1", change("x = |42 + 1", 1));
        assertEquals("x = 43| + 1", change("x = 42| + 1", 1));
        assertEquals("width: 11|px", change("|width: 10px", 1));
        assertNull(change("no number| here\n42", 1), "the next line is not searched");
    }

    @Test
    void keepsSignsAndZeroPadding() {
        assertEquals("v-0|", change("v-|1", -1), "a minus after a word is subtraction: 1 - 1");
        assertEquals("x = 0|", change("x = |-1", 1));
        assertEquals("x = -2|", change("x = -|1", -1));
        assertEquals("file008|.txt", change("file|007.txt", 1));
        assertEquals("file010|.txt", change("file009|.txt", 1));
        assertEquals("-010|", change("|-009", -1));
    }

    @Test
    void hexKeepsItsCaseAndWidth() {
        assertEquals("0x10|", change("0x0|f", 1));
        assertEquals("0x100|", change("|0xFF", 1));
        assertEquals("0x0a|", change("0x0|9", 1), "no letters to copy the case of: lower");
        assertEquals("0x0f|", change("0x10|", -1));
        assertEquals("0x0A0|", change("0x0|9F", 1), "upper case kept");
        assertEquals("0x0|", change("|0x0", -5), "a hex literal stops at zero");
        assertEquals("0xab|", change("0x|aa", 1), "the caret on the prefix");
    }

    @Test
    void handlesNumbersOfAnySize() {
        assertEquals("100000000000000000000|", change("|99999999999999999999", 1));
    }

    @Test
    void withASelectionTheFirstNumberInsideIt() {
        String text = "a 1 b 2 c 3";
        Indenter.TabEdit e = NumberAtCaret.change(text, 4, 9, 10);
        assertEquals("12", e.replacement());
        assertEquals(6, e.from());
        assertNull(NumberAtCaret.change(text, 4, 5, 1), "no digit inside the selection");
    }
}
