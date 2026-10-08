package com.editora.systemd;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The less common {@code OnCalendar=} forms: the remaining shorthands, partial dates, lists, and the errors. */
class SystemdCalendarFormsTest {

    private static SystemdCalendar ok(String s) {
        SystemdCalendar.Parsed p = SystemdCalendar.parse(s);
        assertTrue(p.ok(), () -> "expected valid, got: " + p.error());
        return p.calendar();
    }

    private static String error(String s) {
        SystemdCalendar.Parsed p = SystemdCalendar.parse(s);
        assertFalse(p.ok(), () -> "expected an error for " + s);
        return p.error();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "yearly|At midnight, on day 1 of the month, in January",
                "annually|At midnight, on day 1 of the month, in January",
                "quarterly|At midnight, on day 1 of the month, in January, April, July, October",
                "semiannually|At midnight, on day 1 of the month, in January, July",
                "midnight|Daily at midnight",
                "Yearly|At midnight, on day 1 of the month, in January",
            })
    void describesTheLongPeriodShorthands(String expr, String text) {
        assertEquals(text, ok(expr).describe());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // A full star spelled out is still "every minute".
                "*-*-* *:*:00|Every minute",
                "*-*-* 0..23:0..59|Every minute",
                "*-*-* *:30:00|At minute 30 past every hour",
                "*-*-* 12:00|Daily at noon",
                "*-*-* 12:30|Daily at 12:30",
                "*-*-* 00:30|Daily at 00:30",
                "*-*-* 8,20:00|Daily at 08, 20:00",
                "*-*-* 9..17:00|Daily at 9 through 17:00",
                "*-*-* 03:0/15|Daily at 03:00, 15, 30, 45",
                // A date without a year.
                "12-25 08:00|At 08:00, on day 25 of the month, in December",
                "*-*-1,15 06:00|At 06:00, on days 01, 15",
                "*-*-10..12 06:00|At 06:00, on days 10 through 12",
                // No time at all means midnight.
                "Mon|At midnight, on Monday",
                "Tue 12:00|At noon, on Tuesday",
                "Sat,Sun 10:00|At 10:00, Saturday through Sunday",
                "sunday,tuesday 10:00|At 10:00, on Tuesday, Sunday",
                // All seven weekdays is no restriction.
                "Mon..Sun 01:00|Daily at 01:00",
                // A range that wraps the week.
                "Sat..Mon 01:00|At 01:00, on Monday, Saturday, Sunday",
            })
    void describesTheGeneralForms(String expr, String text) {
        assertEquals(text, ok(expr).describe());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "2026-1-2-3 00:00|bad date \"2026-1-2-3\"",
                "*-*-* 1:2:3:4|bad time \"1:2:3:4\"",
                "*-*-* 7:|bad time \"7:\"",
                "12|unknown weekday \"12\"",
                "*-*-* 0/0:00|step must be positive in \"0/0\"",
                "*-*-* 5..3:00|range start after end in \"5..3\"",
                "*-*-* 1x:00|invalid number \"1x\"",
                "*-*-* 25:00|25 is out of range (0..23)",
                "*-0-01 00:00|0 is out of range (1..12)",
                "*-*-32 00:00|32 is out of range (1..31)",
            })
    void reportsWhatIsWrong(String expr, String message) {
        assertEquals(message, error(expr));
    }

    @Test
    void anAbsentExpressionIsAnError() {
        assertEquals("empty calendar expression", error(null));
        assertEquals("empty calendar expression", error("   "));
    }

    @Test
    void aSteppedHourFiresOnEveryStep() {
        SystemdCalendar c = ok("*-*-* */2:00");
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 30);
        assertEquals(
                List.of(LocalDateTime.of(2026, 1, 1, 2, 0), LocalDateTime.of(2026, 1, 1, 4, 0)), c.nextRuns(from, 2));
    }

    @Test
    void theDateRestrictsMonthAndDay() {
        SystemdCalendar c = ok("*-02-15 00:00");
        LocalDateTime from = LocalDateTime.of(2026, 1, 20, 9, 0);
        assertEquals(List.of(LocalDateTime.of(2026, 2, 15, 0, 0)), c.nextRuns(from, 1));
        assertFalse(c.matches(LocalDateTime.of(2026, 3, 15, 0, 0)));
        assertFalse(c.matches(LocalDateTime.of(2026, 2, 16, 0, 0)));
        assertTrue(c.matches(LocalDateTime.of(2027, 2, 15, 0, 0)));
    }

    @Test
    void aDateThatNeverComesHasNoRuns() {
        // 30 February: the scan gives up rather than looping for ever.
        assertEquals(List.of(), ok("*-02-30 00:00").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 3));
    }

    @Test
    void askingForNoRunsReturnsNone() {
        assertEquals(List.of(), ok("daily").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 0));
    }
}
