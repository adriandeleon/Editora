package com.editora.logviewer;

import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogSummaryTest {

    private static final LogTimes.Context UTC = new LogTimes.Context(ZoneOffset.UTC, 2026);

    @Test
    void templatesMaskWhatVaries() {
        assertEquals(
                "INFO [main] user # timed out after # ms",
                LogSummary.template("2026-11-24 10:00:05,123 INFO [main] user 41 timed out after 3000 ms"));
        assertEquals(
                LogSummary.template(
                        "2026-11-24T10:00:05Z WARN id=deadbeef12 path=\"/a/b\" uuid 123e4567-e89b-12d3-a456-426614174000"),
                LogSummary.template(
                        "2026-11-25T11:00:00Z WARN id=0badc0de99 path=\"/c\" uuid 00000000-0000-0000-0000-000000000000"));
        assertEquals("Timeout waiting for #", LogSummary.template("Timeout waiting for 3"), "a word is not a time");
    }

    @Test
    void countsLevelsMessagesBucketsAndSilences() {
        String log = String.join(
                "\n",
                "2026-11-24 10:00:00 INFO  started",
                "2026-11-24 10:00:10 WARN  pool low: 3 free",
                "2026-11-24 10:00:20 ERROR request 41 failed",
                "java.io.IOException: closed",
                "\tat com.example.Pool.take(Pool.java:42)",
                "2026-11-24 10:05:20 ERROR request 42 failed",
                "2026-11-24 10:05:30 WARN  pool low: 1 free",
                "2026-11-24 10:05:40 INFO  done");
        LogSummary.Summary s = LogSummary.of(log, UTC);
        assertEquals(8, s.lines());
        assertEquals(6, s.records(), "the stack trace is part of its record");
        assertEquals(2, s.levels().get(LogLevel.ERROR));
        assertEquals(2, s.levels().get(LogLevel.WARN));
        assertEquals(2, s.levels().get(LogLevel.INFO));

        assertEquals(2, s.messages().size(), "only what repeats");
        LogSummary.Message first = s.messages().get(0);
        assertEquals("WARN pool low: # free", first.template());
        assertEquals(2, first.count());
        assertEquals(1, first.firstLine(), "ties are in the order they first appeared");
        assertEquals("ERROR request # failed", s.messages().get(1).template());

        assertEquals(10_000, s.bucketMillis(), "5 min 40 s cut into at most 40 rows: 34 of 10 s");
        assertEquals(35, s.buckets().size());
        assertEquals(1, s.buckets().get(0).records());
        assertEquals(0, s.buckets().get(0).problems());
        assertEquals(1, s.buckets().get(2).problems(), "the ERROR at 10:00:20");
        assertEquals(0, s.buckets().get(3).records(), "an empty interval shows as one");

        LogSummary.Gap longest = s.gaps().get(0);
        assertEquals(300_000, longest.millis());
        assertEquals(2, longest.fromLine());
        assertEquals(5, longest.toLine());
        assertTrue(s.gaps().size() <= LogSummary.TOP_GAPS);
    }

    @Test
    void aLogWithoutLevelsOrTimesStillCounts() {
        LogSummary.Summary s = LogSummary.of("hello\nhello\nworld\n", UTC);
        assertEquals(3, s.lines());
        assertEquals(0, s.records(), "no line starts a record: no level and no time");
        assertEquals(List.of(), s.buckets());
        assertEquals(LogTimes.NONE, s.first());

        LogSummary.Summary timed = LogSummary.of("2026-11-24 10:00:00 hello\n2026-11-24 10:00:01 hello\n", UTC);
        assertEquals(2, timed.records(), "with no levels, each timed line is a record");
        assertEquals(2, timed.unleveled());
        assertEquals(1, timed.messages().size());
    }

    @Test
    void bucketWidthsAreRound() {
        assertEquals(1_000, LogSummary.bucketWidth(30_000));
        assertEquals(3_600_000, LogSummary.bucketWidth(30 * 3_600_000L));
        assertEquals(86_400_000, LogSummary.bucketWidth(20 * 86_400_000L));
        assertTrue(LogSummary.bucketWidth(400 * 86_400_000L) % 86_400_000 == 0, "whole days past the table");
    }
}
