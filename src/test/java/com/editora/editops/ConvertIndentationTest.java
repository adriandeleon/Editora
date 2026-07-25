package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConvertIndentationTest {

    private static String toSpaces(String s, int tab) {
        return Indenter.convertIndentation(s, true, tab);
    }

    private static String toTabs(String s, int tab) {
        return Indenter.convertIndentation(s, false, tab);
    }

    @Test
    void tabsToSpaces() {
        assertEquals("    x", toSpaces("\tx", 4));
        assertEquals("        y", toSpaces("\t\ty", 4));
    }

    @Test
    void spacesToTabs() {
        assertEquals("\tx", toTabs("    x", 4));
        assertEquals("\t\ty", toTabs("        y", 4));
    }

    @Test
    void onlyTheLeadingRunIsTouched() {
        // a tab between words (alignment) and a tab inside a string must survive toSpaces
        assertEquals("    a\tb", toSpaces("\ta\tb", 4));
        assertEquals("    s = \"\\t\"", toSpaces("\ts = \"\\t\"", 4));
    }

    @Test
    void partialColumnsBecomeTabsPlusSpaces() {
        // 6 spaces at tabSize 4 → one tab (4) + two spaces
        assertEquals("\t  x", toTabs("      x", 4));
    }

    @Test
    void mixedLeadingWhitespaceIsMeasuredByTabStops() {
        // two spaces then a tab → the tab advances to column 4, so total column = 4 → one tab / 4 spaces
        assertEquals("    x", toSpaces("  \tx", 4));
        assertEquals("\tx", toTabs("  \tx", 4));
    }

    @Test
    void whitespaceOnlyLinesAreLeftAlone() {
        // a blank line with a tab is not rewritten (would change trailing whitespace)
        assertEquals("\ta\n\t\n\tb", toSpaces("\ta\n\t\n\tb", 4).replace("    ", "\t"));
        // more directly: the all-whitespace middle line keeps its tab
        String in = "\tx\n\t\n";
        assertEquals("    x\n\t\n", toSpaces(in, 4));
    }

    @Test
    void noIndentationLinesAreUnchanged() {
        assertEquals("a\nb\nc", toSpaces("a\nb\nc", 4));
        assertEquals("a\nb\nc", toTabs("a\nb\nc", 4));
    }

    @Test
    void trailingNewlinePreserved() {
        assertEquals("    x\n", toSpaces("\tx\n", 4));
        assertEquals("\tx", toTabs("    x", 4)); // no trailing newline
    }

    @Test
    void idempotentAndReversible() {
        String spaced = "    a\n        b\n    c";
        assertEquals(spaced, toSpaces(spaced, 4), "toSpaces is idempotent");
        assertEquals(spaced, toSpaces(toTabs(spaced, 4), 4), "toTabs then toSpaces round-trips");
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertEquals(null, Indenter.convertIndentation(null, true, 4));
        assertEquals("", Indenter.convertIndentation("", true, 4));
    }
}
