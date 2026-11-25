package com.editora.ui;

import java.time.ZoneOffset;

import com.editora.logviewer.LogSummary;
import com.editora.logviewer.LogTimes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSummaryReportTest {

    private static final LogTimes.Context UTC = new LogTimes.Context(ZoneOffset.UTC, 2026);

    @org.junit.jupiter.api.BeforeAll
    static void english() {
        com.editora.i18n.Messages.init("en"); // the report's words, as the assertions spell them
    }

    @Test
    void theReportHasEverySectionAsMarkdownTables() {
        String log = "2026-11-24 10:00:00 ERROR a|b failed 1\n2026-11-24 10:10:00 ERROR a|b failed 2\n"
                + "2026-11-24 10:10:01 INFO `quoted` ok\n";
        String md = LogSummaryReport.markdown(LogSummary.of(log, UTC), "app.log", UTC);
        assertTrue(md.startsWith("# Summary of app.log\n"), md);
        assertTrue(md.contains("| Records | 3 |"), md);
        assertTrue(md.contains("| Span | 10 min 1 s |"), md);
        assertTrue(md.contains("| ERROR | 2 |"), md);
        assertTrue(md.contains("## Records over time (each row 30 s)"), md);
        assertTrue(md.contains("| 2 | ERROR | 1 | `ERROR a\\|b failed #` |"), "pipes escaped: " + md);
        assertTrue(md.contains("## Longest silences"), md);
        assertTrue(md.contains("| 10 min | 2026-11-24 10:00:00 | 2026-11-24 10:10:00 | 1–2 |"), md);
    }

    @Test
    void aLogWithNothingRepeatedSaysSo() {
        String md = LogSummaryReport.markdown(LogSummary.of("2026-11-24 10:00:00 INFO once\n", UTC), "x.log", UTC);
        assertTrue(md.contains("No message appears more than once."), md);
    }

    @Test
    void durationsAndBarsReadAtAGlance() {
        assertEquals("350 ms", LogSummaryReport.duration(350));
        assertEquals("42 s", LogSummaryReport.duration(42_000));
        assertEquals("5 min", LogSummaryReport.duration(300_000));
        assertEquals("3 h 12 min", LogSummaryReport.duration((3 * 60 + 12) * 60_000L));
        assertEquals("2 d 4 h", LogSummaryReport.duration((2 * 24 + 4) * 3_600_000L));
        assertEquals("", LogSummaryReport.bar(0, 10));
        assertEquals("█", LogSummaryReport.bar(1, 1000), "anything at all shows");
        assertEquals(LogSummaryReport.BAR, LogSummaryReport.bar(10, 10).length());
        assertEquals("`it's`", LogSummaryReport.code("it`s"));
    }
}
