package com.editora.systemd;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit-file parsing edges (continuations, CRLF, comments, stray lines) and the odd time spans. */
class SystemdUnitEdgesTest {

    @Test
    void noTextIsAUnitWithoutSections() {
        SystemdUnit u = SystemdUnit.parse(null);
        assertTrue(u.sections().isEmpty());
        assertFalse(u.hasSection("Unit"));
        assertNull(u.first("Unit", "Description"));
        assertEquals(List.of(), u.all("Unit", "Description"));
    }

    @Test
    void aTrailingBackslashJoinsTheNextLine() {
        SystemdUnit u = SystemdUnit.parse("[Service]\nExecStart=/bin/run \\\n  --one \\\n  --two\nUser=me\n");
        assertEquals("/bin/run   --one   --two", u.first("Service", "ExecStart"));
        // The directive is reported on the line it starts on; the one after it on its own.
        SystemdUnit.Section s = u.sections().get(0);
        assertEquals(2, s.directives().get(0).line());
        assertEquals(5, s.directives().get(1).line());
    }

    @Test
    void aBackslashOnTheLastLineHasNothingToJoin() {
        SystemdUnit u = SystemdUnit.parse("[Service]\nExecStart=/bin/run \\");
        assertEquals("/bin/run \\", u.first("Service", "ExecStart"));
    }

    @Test
    void windowsLineEndingsAndBothCommentStylesAreAccepted() {
        SystemdUnit u = SystemdUnit.parse(
                "; a comment\r\n# another\r\n[ Unit ]\r\nDescription = Windows made\r\n\r\n[Timer]\r\nOnCalendar=daily\r\n");
        assertEquals("Windows made", u.first("unit", "description"));
        assertEquals("daily", u.first("Timer", "OnCalendar"));
        assertEquals(2, u.sections().size());
    }

    @Test
    void linesOutsideASectionOrWithoutAValueAreSkipped() {
        SystemdUnit u = SystemdUnit.parse("Stray=1\n[Unit]\nnot a directive\n[broken\nDescription=x\n");
        assertEquals(1, u.sections().size());
        assertEquals(1, u.sections().get(0).directives().size());
        assertEquals("x", u.first("Unit", "Description"));
        // Asked for in a section that exists, a key that does not: nothing.
        assertNull(u.first("Unit", "Stray"));
        assertNull(u.first("Service", "Description"));
    }

    @Test
    void timeSpansThatAreNotDurations() {
        assertEquals(Long.MAX_VALUE, TimeSpan.seconds("infinity"));
        assertEquals("never", TimeSpan.describe("infinity"));
        assertEquals("immediately", TimeSpan.describe("0"));
        assertEquals("immediately", TimeSpan.describe("0s"));
        assertEquals("", TimeSpan.describe(null));
        assertEquals(-1, TimeSpan.seconds(null));
        assertEquals(-1, TimeSpan.seconds("  "));
    }

    @Test
    void anUnparseableSpanIsShownAsWritten() {
        assertEquals("5 parsecs", TimeSpan.describe(" 5 parsecs "));
        assertEquals("1h junk 5m", TimeSpan.describe("1h junk 5m"));
        assertEquals("5m later", TimeSpan.describe("5m later"));
        // Too many digits for a long, and a product that overflows one.
        assertEquals(-1, TimeSpan.seconds("99999999999999999999"));
        assertEquals(-1, TimeSpan.seconds("9223372036854775807y"));
        assertEquals(-1, TimeSpan.seconds("9223372036854775807s 1s"));
    }

    @Test
    void everyUnitSpellingIsUnderstood() {
        assertEquals("1 year 1 month 1 week 1 day 1 hour 1 minute 1 second", TimeSpan.describe("1y 1M 1w 1d 1h 1m 1s"));
        assertEquals("2 hours", TimeSpan.describe("1hr 1hour"));
        assertEquals("3 minutes", TimeSpan.describe("1min 1minute 60sec"));
        assertEquals(2 * 604_800L, TimeSpan.seconds("2weeks"));
    }
}
