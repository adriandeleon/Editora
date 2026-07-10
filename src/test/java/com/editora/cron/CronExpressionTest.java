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
