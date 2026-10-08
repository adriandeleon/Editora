package com.editora.cron;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The macros, list/range/step phrasings and error messages {@code CronExpressionTest} does not reach. */
class CronExpressionFormsTest {

    private static CronExpression ok(String s) {
        CronExpression.Parsed p = CronExpression.parse(s);
        assertTrue(p.ok(), () -> "expected valid, got error: " + p.error());
        return p.expr();
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "@yearly|At midnight, on day 1 of the month, in January",
                "@annually|At midnight, on day 1 of the month, in January",
                "@monthly|At midnight, on day 1 of the month",
                "@weekly|At midnight, on Sunday",
                "@daily|At midnight",
                "@midnight|At midnight",
                "@hourly|At minute 0 past every hour",
                "@HOURLY|At minute 0 past every hour",
                "@reboot|At system startup",
            })
    void describesEveryMacro(String macro, String text) {
        assertEquals(text, ok(macro).describe());
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "0 */2 * * *|At minute 0 past every 2nd hour",
                "5 */3 * * *|At minute 5 past every 3rd hour",
                "0 */4 * * *|At minute 0 past every 4th hour",
                "0 */11 * * *|At minute 0 past every 11th hour",
                "0 */12 * * *|At minute 0 past every 12th hour",
                "0 */13 * * *|At minute 0 past every 13th hour",
                "0 */21 * * *|At minute 0 past every 21st hour",
                "0 */22 * * *|At minute 0 past every 22nd hour",
                "0 */23 * * *|At minute 0 past every 23rd hour",
                "30 0 * * *|At 00:30",
                "30 12 * * *|At 12:30",
                "0,30 * * * *|Minute 0, 30",
                "0,30 9-17 * * *|Minute 0, 30 past hour 9 through 17",
                "15 9,17 * * *|Minute 15 past hour 9, 17",
                "* 9 * * *|Every minute past hour 9",
                "10-12 * * * *|Minute 10 through 12",
                "0 0 * * 1,3,5|At midnight, on Monday, Wednesday, Friday",
                "0 0 1,15 * *|At midnight, on days 1, 15 of the month",
                "0 0 10-12 * *|At midnight, on days 10 through 12 of the month",
                "0 0 1 1,6 *|At midnight, on day 1 of the month, in January, June",
                "0 0 1 3-5 *|At midnight, on day 1 of the month, in March through May",
                "0 0 * dec *|At midnight, in December",
            })
    void describesListsRangesAndSteps(String schedule, String text) {
        assertEquals(text, ok(schedule).describe());
    }

    @Test
    void reportsAnEmptyOrMalformedSchedule() {
        assertEquals("empty schedule", CronExpression.parse(null).error());
        assertEquals("empty schedule", CronExpression.parse("  \t ").error());
        assertEquals(
                "unknown macro \"@fortnightly\"",
                CronExpression.parse("@fortnightly").error());
        assertEquals("expected 5 fields, found 3", CronExpression.parse("* * *").error());
        assertEquals(
                "expected 5 fields, found 6",
                CronExpression.parse("0 0 * * * root").error());
        assertNull(CronExpression.parse("* * *").expr());
        assertFalse(CronExpression.parse("61 * * * *").ok());
    }

    @Test
    void rebootNeverMatchesAClockTime() {
        CronExpression reboot = ok("@reboot");
        assertTrue(reboot.isReboot());
        assertFalse(reboot.matches(LocalDateTime.of(2026, 1, 1, 0, 0)));
        assertEquals(List.of(), reboot.nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 5));
    }

    @Test
    void askingForNoRunsReturnsNone() {
        CronExpression daily = ok("@daily");
        assertFalse(daily.isReboot());
        assertEquals(List.of(), daily.nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 0));
        assertEquals(List.of(), daily.nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), -1));
    }

    @Test
    void aSteppedHourFiresOnEveryStep() {
        assertEquals(
                List.of(LocalDateTime.of(2026, 1, 1, 6, 0), LocalDateTime.of(2026, 1, 1, 12, 0)),
                ok("0 */6 * * *").nextRuns(LocalDateTime.of(2026, 1, 1, 0, 0), 2));
    }
}
