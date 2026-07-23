package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for the pure tabify/untabify engine (no toolkit). */
class TabConvertTest {

    // --- untabify --------------------------------------------------------------------------------

    @Test
    void untabifyExpandsATabToTheNextTabStop() {
        assertEquals("    x", TabConvert.untabify("\tx", 4), "a leading tab becomes 4 spaces");
    }

    @Test
    void untabifyExpandsAMidLineTabToTheRemainderOfTheStop() {
        assertEquals("ab  cd", TabConvert.untabify("ab\tcd", 4), "a tab at column 2 fills to column 4");
    }

    @Test
    void untabifyResetsTheColumnAtEachLine() {
        assertEquals("a   b\n\tc".replace("\t", "    "), TabConvert.untabify("a\tb\n\tc", 4));
        // each line's tab is measured from that line's column 0
        assertEquals("a   b\n    c", TabConvert.untabify("a\tb\n\tc", 4));
    }

    @Test
    void untabifyOfTabFreeTextReturnsTheSameInstance() {
        String s = "no tabs here\njust spaces  ";
        assertSame(s, TabConvert.untabify(s, 4), "cheap no-op when there is nothing to expand");
    }

    @Test
    void untabifyHonoursTheTabWidth() {
        assertEquals("  x", TabConvert.untabify("\tx", 2));
        assertEquals("        x", TabConvert.untabify("\tx", 8));
    }

    // --- tabify ----------------------------------------------------------------------------------

    @Test
    void tabifyTurnsAFullTabStopOfSpacesIntoATab() {
        assertEquals("\tx", TabConvert.tabify("    x", 4));
    }

    @Test
    void tabifyLeavesAShortRunThatDoesNotReachAStop() {
        assertEquals("  x", TabConvert.tabify("  x", 4), "two spaces from column 0 do not reach column 4");
    }

    @Test
    void tabifyEmitsTabsThenTrailingSpaces() {
        assertEquals("\t  x", TabConvert.tabify("      x", 4), "6 spaces = one tab (to col 4) + 2 spaces");
    }

    @Test
    void tabifyLeavesALoneSpaceAlone() {
        assertEquals("a b", TabConvert.tabify("a b", 4), "a single space is never worth a tab");
    }

    @Test
    void tabifyMeasuresFromTheLineStartNotTheRunStart() {
        // "ab" then spaces to column 4: from column 2, two spaces reach the stop → one tab.
        assertEquals("ab\tx", TabConvert.tabify("ab  x", 4));
    }

    @Test
    void tabifyResetsColumnPerLine() {
        assertEquals("\tx\n\ty", TabConvert.tabify("    x\n    y", 4));
    }

    // --- round trips -----------------------------------------------------------------------------

    @Test
    void untabifyThenTabifyIsStableForAlignedIndentation() {
        String tabs = "\t\tdeep\n\tless\nflush";
        String spaces = TabConvert.untabify(tabs, 4);
        assertEquals(tabs, TabConvert.tabify(spaces, 4), "space indentation on tab stops round-trips back to tabs");
    }

    @Test
    void tabifyIsIdempotent() {
        String once = TabConvert.tabify("        deep\n    x", 4);
        assertEquals(once, TabConvert.tabify(once, 4));
    }

    @Test
    void untabifyIsIdempotent() {
        String once = TabConvert.untabify("\t\tx", 4);
        assertEquals(once, TabConvert.untabify(once, 4));
    }
}
