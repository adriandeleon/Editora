package com.editora.cron;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Field-level parse errors and the crontab line shapes that are easy to get wrong. */
class CronEdgesTest {

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "*/0 * * * *|step must be positive in \"*/0\"",
                "5-3 * * * *|range start after end in \"5-3\"",
                "x * * * *|invalid value \"x\"",
                "*/x * * * *|invalid step \"x\"",
                "0 0 * foo *|unknown name \"foo\"",
                "60 * * * *|60 is out of range (0–59)",
                "0 0 0 * *|0 is out of range (1–31)",
                "0 0 * * -1|-1 is out of range (0–7)",
                "0 0 * * 1-|invalid value \"\"",
                ", * * * *|no matching values in \",\"",
            })
    void aBadFieldSaysWhy(String schedule, String message) {
        CronExpression.Parsed p = CronExpression.parse(schedule);
        assertFalse(p.ok());
        assertNull(p.expr());
        assertEquals(message, p.error());
    }

    @Test
    void aBareStartWithAStepRunsToTheEndOfTheField() {
        CronExpression e = CronExpression.parse("5/15 * * * *").expr();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        assertEquals(
                List.of(from.withMinute(5), from.withMinute(20), from.withMinute(35), from.withMinute(50)),
                e.nextRuns(from, 4));
    }

    @Test
    void aFieldKnowsItsBoundsAndRejectsAnEmptyToken() {
        CronField f = CronField.parse("1-5", 0, 6, null, true);
        assertEquals(0, f.min());
        assertEquals(6, f.max());
        assertFalse(f.isStar());
        assertNull(f.step());
        CronField.ParseException e =
                assertThrows(CronField.ParseException.class, () -> CronField.parse("  ", 0, 59, null, false));
        assertEquals("empty field", e.getMessage());
    }

    @Test
    void noCrontabTextMeansNoLines() {
        Crontab none = Crontab.parse(null);
        assertTrue(none.jobs().isEmpty());
        assertTrue(none.assignments().isEmpty());
    }

    @Test
    void aMacroOrScheduleMayComeWithoutACommand() {
        Crontab t = Crontab.parse("@reboot\n* * * * *\n@daily   /opt/run.sh --now\n");
        assertEquals(3, t.jobs().size());
        Crontab.Job reboot = t.jobs().get(0);
        assertTrue(reboot.ok());
        assertEquals("@reboot", reboot.rawSchedule());
        assertEquals("", reboot.command());
        Crontab.Job bare = t.jobs().get(1);
        assertTrue(bare.ok());
        assertEquals("* * * * *", bare.rawSchedule());
        assertEquals("", bare.command());
        assertEquals("/opt/run.sh --now", t.jobs().get(2).command());
        assertEquals(3, t.jobs().get(2).line());
    }

    @Test
    void anEqualsSignInsideACommandDoesNotMakeAnAssignment() {
        Crontab t = Crontab.parse("0 0 * * * FOO=bar /opt/run.sh\n=orphan\n");
        assertTrue(t.assignments().isEmpty());
        assertEquals(2, t.jobs().size());
        assertEquals("FOO=bar /opt/run.sh", t.jobs().get(0).command());
        // "=orphan" has no name, so it is read as a (broken) job line.
        assertFalse(t.jobs().get(1).ok());
        assertEquals("expected 5 schedule fields", t.jobs().get(1).error());
    }

    @Test
    void assignmentValuesLoseOnlyAMatchingPairOfQuotes() {
        String[][] cases = {
            {"A=\"x y\"", "x y"},
            {"A='x y'", "x y"},
            {"A = plain ", "plain"},
            // Mismatched or lone quotes are kept as written.
            {"A=\"x'", "\"x'"},
            {"A='x\"", "'x\""},
            {"A=\"", "\""},
            {"A=", ""},
        };
        for (String[] c : cases) {
            Crontab t = Crontab.parse(c[0] + "\n");
            assertEquals(1, t.assignments().size(), c[0]);
            assertEquals("A", t.assignments().get(0).name(), c[0]);
            assertEquals(c[1], t.assignments().get(0).value(), c[0]);
        }
    }
}
