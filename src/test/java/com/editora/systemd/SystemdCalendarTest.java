package com.editora.systemd;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the systemd OnCalendar parser/describe/next-runs and time-span decoding. */
class SystemdCalendarTest {

    private static String describe(String s) {
        SystemdCalendar.Parsed p = SystemdCalendar.parse(s);
        assertTrue(p.ok(), () -> "expected valid, got: " + p.error());
        return p.calendar().describe();
    }

    @Test
    void describesGeneralForms() {
        assertEquals("Daily at 02:00", describe("*-*-* 02:00:00"));
        assertEquals("At 09:00, Monday through Friday", describe("Mon..Fri *-*-* 09:00:00"));
        assertEquals("At midnight, on day 1 of the month", describe("*-*-01 00:00:00"));
        assertEquals("Every 15 minutes", describe("*:0/15"));
        assertEquals("At 04:30, on Monday, Thursday", describe("Mon,Thu *-*-* 04:30:00"));
    }

    @Test
    void describesShorthands() {
        assertEquals("Every minute", describe("minutely"));
        assertEquals("Every hour, at minute 0", describe("hourly"));
        assertEquals("Daily at midnight", describe("daily"));
        assertEquals("At midnight, on Monday", describe("weekly"));
        assertEquals("At midnight, on day 1 of the month", describe("monthly"));
    }

    @Test
    void nextRunsForWeekdayTimer() {
        // Fri 2026-07-10 09:41 → next 02:00 daily runs.
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 9, 41);
        SystemdCalendar c = SystemdCalendar.parse("*-*-* 02:00:00").calendar();
        assertEquals(
                List.of(LocalDateTime.of(2026, 7, 11, 2, 0), LocalDateTime.of(2026, 7, 12, 2, 0)), c.nextRuns(from, 2));
    }

    @Test
    void nextRunsWeekdayConstrained() {
        LocalDateTime from = LocalDateTime.of(2026, 7, 10, 12, 0); // a Friday
        SystemdCalendar c = SystemdCalendar.parse("Mon *-*-* 00:00:00").calendar();
        assertEquals(LocalDateTime.of(2026, 7, 13, 0, 0), c.nextRuns(from, 1).get(0)); // next Monday
    }

    @Test
    void rejectsGarbage() {
        assertFalse(SystemdCalendar.parse("Mon..Funday *-*-* 00:00:00").ok());
        assertFalse(SystemdCalendar.parse("*-13-01 00:00:00").ok()); // month 13
        assertFalse(SystemdCalendar.parse("").ok());
    }

    @Test
    void timeSpanDecoding() {
        assertEquals("15 minutes", TimeSpan.describe("15min"));
        assertEquals("1 hour 30 minutes", TimeSpan.describe("1h 30min"));
        assertEquals("2 days", TimeSpan.describe("2d"));
        assertEquals("10 seconds", TimeSpan.describe("10s"));
        assertEquals("5 minutes", TimeSpan.describe("300")); // bare number = seconds
        assertEquals(5400, TimeSpan.seconds("1h 30min"));
        assertEquals(-1, TimeSpan.seconds("soon"));
    }

    @Test
    void unitParsingKeepsOrderAndDuplicates() {
        SystemdUnit u = SystemdUnit.parse("""
                [Unit]
                Description=Nightly backup

                [Service]
                ExecStartPre=/bin/echo start
                ExecStart=/opt/backup.sh
                User=backup

                [Install]
                WantedBy=multi-user.target
                """);
        assertEquals("Nightly backup", u.first("Unit", "Description"));
        assertEquals(List.of("/opt/backup.sh"), u.all("Service", "ExecStart"));
        assertEquals("backup", u.first("Service", "User"));
        assertEquals("multi-user.target", u.first("Install", "WantedBy"));
        assertTrue(u.hasSection("Service"));
        assertFalse(u.hasSection("Timer"));
    }
}
