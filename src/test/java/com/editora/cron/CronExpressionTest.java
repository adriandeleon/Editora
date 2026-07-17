package com.editora.cron;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for cron parsing, English description, and next-run computation. */
class CronExpressionTest {

    private static CronExpression ok(String s) {
        CronExpression.Parsed p = CronExpression.parse(s);
        assertTrue(p.ok(), () -> "expected valid, got error: " + p.error());
        return p.expr();
    }

    private static String describe(String s) {
        return ok(s).describe();
    }

    // ---- description --------------------------------------------------------------------------

    @Test
    void describesCommonSchedules() {
        assertEquals("Every 15 minutes", describe("*/15 * * * *"));
        assertEquals("At 02:30, Monday through Friday", describe("30 2 * * 1-5"));
        assertEquals("At midnight, on day 1 of the month", describe("0 0 1 * *"));
        assertEquals("Every minute", describe("* * * * *"));
        assertEquals("At noon", describe("0 12 * * *"));
        assertEquals("At minute 5 past every hour", describe("5 * * * *"));
    }

    @Test
    void describesTheDayOfMonthOrDayOfWeekRuleAsOrNotAnd() {
        // The most famous cron gotcha: when BOTH day fields are restricted, cron fires when EITHER matches.
        // "day 13 of the month and on Friday" reads as Friday the 13th; it actually fires on every Friday and
        // every 13th. The preview renders describe() and the next-run times in the same row, so saying "and"
        // made it contradict itself on screen.
        assertEquals("At midnight, on day 13 of the month or on Friday", describe("0 0 13 * 5"));
        assertEquals(
                List.of(
                        LocalDateTime.of(2026, 1, 2, 0, 0), // a Friday
                        LocalDateTime.of(2026, 1, 9, 0, 0), // a Friday
                        LocalDateTime.of(2026, 1, 13, 0, 0)), // the 13th, a Tuesday
                ok("0 0 13 * 5").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 3),
                "the description must agree with what the schedule actually does");
    }

    @Test
    void describesADayFieldThatCoversEveryValueRatherThanDroppingIt() {
        // `1-31` is not a star, so it still forces the OR-rule — and OR'd with Friday that fires EVERY day.
        // Describing it as just "on Friday" dropped the very field that changes the meaning.
        assertEquals("At midnight, on days 1 through 31 of the month or on Friday", describe("0 0 1-31 * 5"));
        assertEquals(
                List.of(LocalDateTime.of(2026, 1, 2, 0, 0), LocalDateTime.of(2026, 1, 3, 0, 0)),
                ok("0 0 1-31 * 5").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 2),
                "consecutive days — the schedule is daily, not weekly");
    }

    @Test
    void acceptsAWeekendRangeThatCrossesTheSundayAlias() {
        // `6-7` (Sat–Sun) is the canonical weekend cron. 7 is an alias for Sunday, but Vixie expands the range
        // over 0-7 FIRST and folds 7 into 0 afterwards; folding first makes it lo=6, hi=0 — rejected as
        // "range start after end" for a schedule real cron runs happily.
        assertEquals(
                List.of(
                        LocalDateTime.of(2026, 1, 3, 0, 0), // Saturday
                        LocalDateTime.of(2026, 1, 4, 0, 0), // Sunday
                        LocalDateTime.of(2026, 1, 10, 0, 0)), // Saturday
                ok("0 0 * * 6-7").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 3));
        assertEquals(
                List.of(
                        LocalDateTime.of(2026, 1, 2, 0, 0), // Friday
                        LocalDateTime.of(2026, 1, 3, 0, 0),
                        LocalDateTime.of(2026, 1, 4, 0, 0)),
                ok("0 0 * * 5-7").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 3));
        assertTrue(CronExpression.parse("0 0 * * 0-7").ok(), "the whole legal day-of-week range");
        assertEquals(
                ok("0 0 * * 0").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 3),
                ok("0 0 * * 7").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 3),
                "7 and 0 are the same day");
    }

    @Test
    void describesNamesAndMonths() {
        assertEquals("At 09:00, on Monday", describe("0 9 * * MON"));
        assertEquals("At midnight, on day 1 of the month, in June, December", describe("0 0 1 6,12 *"));
    }

    @Test
    void describesMonthRange() {
        assertEquals("At midnight, on day 1 of the month, in January through March", describe("0 0 1 1-3 *"));
    }

    @Test
    void describesMacros() {
        assertEquals("At midnight", describe("@daily"));
        assertEquals("At system startup", describe("@reboot"));
        assertEquals("At midnight, on Sunday", describe("@weekly"));
    }

    // ---- validation ---------------------------------------------------------------------------

    @Test
    void rejectsOutOfRange() {
        CronExpression.Parsed p = CronExpression.parse("99 * * * *");
        assertFalse(p.ok());
        assertNull(p.expr());
        assertTrue(p.error().contains("99"));
    }

    @Test
    void rejectsWrongFieldCount() {
        assertFalse(CronExpression.parse("* * * *").ok());
        assertFalse(CronExpression.parse("* * * * * *").ok());
    }

    @Test
    void rejectsUnknownMacroAndName() {
        assertFalse(CronExpression.parse("@yearlyish").ok());
        assertFalse(CronExpression.parse("0 0 * * FUNDAY").ok());
    }

    @Test
    void sundaySevenIsSunday() {
        assertTrue(ok("0 0 * * 7").matches(LocalDateTime.of(2026, 7, 12, 0, 0))); // 2026-07-12 is a Sunday
    }

    // ---- next runs ----------------------------------------------------------------------------

    @Test
    void nextRunsForWeekdayJob() {
        // Fri 2026-07-10 09:41 → next 02:30 weekday runs.
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 9, 41);
        List<LocalDateTime> runs = ok("30 2 * * 1-5").nextRuns(from, 3);
        assertEquals(
                List.of(
                        LocalDateTime.of(2026, 7, 13, 2, 30), // Mon
                        LocalDateTime.of(2026, 7, 14, 2, 30), // Tue
                        LocalDateTime.of(2026, 7, 15, 2, 30)), // Wed
                runs);
    }

    @Test
    void nextRunsEveryFifteenMinutes() {
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 9, 41);
        List<LocalDateTime> runs = ok("*/15 * * * *").nextRuns(from, 3);
        assertEquals(
                List.of(
                        LocalDateTime.of(2026, 7, 10, 9, 45),
                        LocalDateTime.of(2026, 7, 10, 10, 0),
                        LocalDateTime.of(2026, 7, 10, 10, 15)),
                runs);
    }

    @Test
    void rebootHasNoRuns() {
        assertTrue(ok("@reboot").isReboot());
        assertTrue(ok("@reboot").nextRuns(LocalDateTime.now(), 3).isEmpty());
    }

    @Test
    void domDowOrSemantics() {
        // Both restricted → fires if DOM==1 OR it's a Monday.
        CronExpression e = ok("0 0 1 * 1");
        assertTrue(e.matches(LocalDateTime.of(2026, 7, 1, 0, 0))); // the 1st (a Wednesday)
        assertTrue(e.matches(LocalDateTime.of(2026, 7, 6, 0, 0))); // a Monday (not the 1st)
        assertFalse(e.matches(LocalDateTime.of(2026, 7, 2, 0, 0))); // neither
    }

    @Test
    void impossibleScheduleTerminates() {
        // Feb 30 never occurs — nextRuns must bound-scan and return empty, not hang.
        assertTrue(
                ok("0 0 30 2 *").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 1).isEmpty());
    }
}
