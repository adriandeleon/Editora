package com.editora.editops;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Unit tests for the pure align-regexp engine (no toolkit). */
class AlignRegexpTest {

    @Test
    void alignsAssignmentsOnTheEqualsSign() {
        String in = "int a = 1;\nString bb = 2;\nx = 3;";
        String out = "int a     = 1;\nString bb = 2;\nx         = 3;";
        assertEquals(out, AlignRegexp.align(in, "="));
    }

    @Test
    void padsOnlyLinesThatNeedItAndLeavesTheWidestAlone() {
        assertEquals("a =1\nbb=2", AlignRegexp.align("a=1\nbb=2", "="), "the widest match-start sets the column");
    }

    @Test
    void leavesLinesWithNoMatchUntouched() {
        String in = "a=1\n// a comment\nbb=2";
        assertEquals("a =1\n// a comment\nbb=2", AlignRegexp.align(in, "="));
    }

    @Test
    void aligningOnTheFirstMatchOnlyIgnoresLaterOnesOnTheSameLine() {
        assertEquals("a =1=2\nbb=3=4", AlignRegexp.align("a=1=2\nbb=3=4", "="), "only the first = per line moves");
    }

    @Test
    void isIdempotent() {
        String once = AlignRegexp.align("a=1\nbb=2\nccc=3", "=");
        assertEquals(once, AlignRegexp.align(once, "="), "aligning already-aligned text changes nothing");
    }

    @Test
    void alignsOnAColonForKeyValueBlocks() {
        assertEquals("host  : x\nport  : y\nscheme: z", AlignRegexp.align("host: x\nport: y\nscheme: z", ":"));
    }

    @Test
    void aRegexWithSpecialCharactersWorks() {
        // align on the arrow "->"
        assertEquals("a   -> 1\nbbb -> 2", AlignRegexp.align("a -> 1\nbbb -> 2", "->"));
    }

    @Test
    void noMatchesAnywhereReturnsTheInputUnchanged() {
        assertSame("no separators here\njust prose", AlignRegexp.align("no separators here\njust prose", "="));
    }

    @Test
    void aBadRegexReturnsTheInputUnchanged() {
        String in = "a=1\nbb=2";
        assertSame(in, AlignRegexp.align(in, "("));
    }

    @Test
    void nullOrEmptyRegexReturnsTheInputUnchanged() {
        String in = "a=1";
        assertSame(in, AlignRegexp.align(in, ""));
        assertSame(in, AlignRegexp.align(in, null));
    }

    @Test
    void preservesATrailingNewline() {
        assertEquals("a =1\nbb=2\n", AlignRegexp.align("a=1\nbb=2\n", "="), "the trailing empty line is kept");
    }
}
