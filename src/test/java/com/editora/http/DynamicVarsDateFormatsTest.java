package com.editora.http;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code $datetime} formats as the server receives them (round-2 F5, F7, F8): full month and weekday names, the
 * Day.js tokens the VS Code REST Client documents, Java patterns that merely contain a Day.js letter, and offsets
 * too large for a date.
 */
class DynamicVarsDateFormatsTest {

    /** Thursday 31 December 2026, 05:07:09.042 UTC — the 365th day, quarter 4, ISO week 53. */
    private static final ZonedDateTime T = ZonedDateTime.of(2026, 12, 31, 5, 7, 9, 42_000_000, ZoneOffset.UTC);

    @BeforeAll
    static void messages() {
        Messages.init("en");
    }

    private static String dt(String format) {
        return DynamicVars.value("$datetime " + format, T, null);
    }

    @Test
    void fullMonthAndWeekdayNamesAreNotAbbreviated() {
        assertEquals("Thursday, December 31 2026", dt("'dddd, MMMM D YYYY'"), "Day.js");
        assertEquals("Thursday, 31 December 2026", dt("\"EEEE, dd MMMM yyyy\""), "Java");
        assertEquals("Thu, 31 Dec 2026", dt("\"EEE, dd MMM yyyy\""));
    }

    @Test
    void dayJsTokensMeanWhatDayJsSays() {
        assertEquals("2026-12-31 4", dt("\"YYYY-MM-DD d\""), "d is the weekday number, Sunday 0");
        assertEquals("Thu Th", dt("\"ddd dd\""), "dd is the two-letter weekday");
        assertEquals("31st December 2026", dt("\"Do MMMM YYYY\""), "ordinal day");
        assertEquals("2026-W53", dt("\"YYYY-[W]WW\""), "a literal next to a token");
        assertEquals("2026 Q4", dt("\"YYYY [Q]Q\""), "quarter");
        assertEquals("20261231 " + T.toEpochSecond(), dt("\"YYYYMMDD X\""), "unix seconds inside a pattern");
        assertEquals(T.toInstant().toEpochMilli() + "", dt("\"x\""));
        assertEquals("2026 05:07:09.042 am +00:00", dt("\"YYYY hh:mm:ss.SSS a Z\""), "a is lower case");
        assertEquals("5:7 AM +0000", dt("\"h:m A ZZ\""));
        assertEquals("26-1-365", dt("\"YY-[1]-DDD\""), "day of the year");
        assertEquals("2026-12-31T05:07:09", dt("\"YYYY-MM-DDTHH:mm:ss\""), "an unbracketed T stays as written");
    }

    @Test
    void javaPatternsThatContainADayJsLetterStayJava() {
        assertEquals("Date: 2026-12-31", dt("\"'Date:' yyyy-MM-dd\""), "a quoted literal holding D");
        assertEquals("2026-365", dt("\"yyyy-DDD\""), "Java's day of the year");
        assertEquals("2026-12-31T05:07:09Z", dt("\"yyyy-MM-dd'T'HH:mm:ss'Z'\""));
        assertEquals("31-12-2026", dt("\"DD-MM-YYYY\""), "still Day.js");
    }

    @Test
    void anOffsetTooLargeForADateIsReportedNotThrown() {
        String[] why = new String[1];
        assertNull(DynamicVars.resolve("$timestamp 999999999 y", T, null, why));
        assertTrue(why[0] != null && why[0].contains("out of range"), why[0]);
        why[0] = null;
        assertNull(DynamicVars.resolve("$datetime iso8601 999999999 y", T, null, why));
        assertTrue(why[0] != null && why[0].contains("out of range"), why[0]);

        // What a run sends and reports: an empty value and one warning, nothing thrown.
        HttpVars.Unresolved unresolved = new HttpVars.Unresolved();
        String sent = HttpVars.substitute(
                "t={{$timestamp 999999999 y}}", HttpVars.Scope.of(Map.of()), T, null, null, unresolved);
        assertEquals("t=", sent);
        assertEquals(
                List.of("{{$timestamp 999999999 y}}"),
                List.copyOf(unresolved.entries().keySet()));
        // And the editor's check of the same text completes with a mark instead of failing.
        List<HttpVariableIndex.Problem> marks =
                HttpVariableIndex.unresolved("GET http://h/?t={{$timestamp 999999999 y}}\n", null, T);
        assertEquals(1, marks.size());
    }
}
