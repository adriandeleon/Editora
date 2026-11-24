package com.editora.logviewer;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogRecordFilterTest {

    private static String feed(LogRecordFilter filter, String... lines) {
        LogFilter.Collector out = new LogFilter.Collector(64);
        for (int i = 0; i < lines.length; i++) {
            filter.accept(lines[i], i, out);
        }
        return out.text.toString();
    }

    private static LogFilter.Patterns between(String from, String to) {
        LogTimes.Context utc = new LogTimes.Context(java.time.ZoneOffset.UTC, 2026);
        return new LogFilter.Patterns(
                null, null, new LogFilter.TimeRange(LogTimes.of(from, utc), LogTimes.of(to, utc), utc));
    }

    @Test
    void aTimeRangeKeepsTheRecordsWrittenInItWithTheirTraces() {
        LogRecordFilter filter = new LogRecordFilter(null, between("2026-11-24 10:01:00", "2026-11-24 10:02:00"));
        assertEquals(
                "2026-11-24 10:01:00 ERROR b\n\tat y\n2026-11-24 10:02:00 INFO c\n",
                feed(
                        filter,
                        "header without a time",
                        "2026-11-24 10:00:00 ERROR a",
                        "\tat x",
                        "2026-11-24 10:01:00 ERROR b",
                        "\tat y",
                        "2026-11-24 10:02:00 INFO c",
                        "2026-11-24 10:03:00 INFO d"),
                "both ends included; a line before any time is not kept");
        assertFalse(filter.wouldKeep("\tat z"), "an unfinished trace line takes the last time seen, 10:03");
        assertTrue(filter.wouldKeep("2026-11-24 10:01:30 INFO late thread"), "its own time, in the range");
    }

    @Test
    void aTimeRangeAndALevelFloorBothApply() {
        LogRecordFilter filter =
                new LogRecordFilter(LogLevel.ERROR, between("2026-11-24 10:01:00", "2026-11-24 10:09:00"));
        assertEquals(
                "2026-11-24 10:02:00 ERROR b\n",
                feed(
                        filter,
                        "2026-11-24 10:00:00 ERROR a",
                        "2026-11-24 10:01:00 INFO x",
                        "2026-11-24 10:02:00 ERROR b"));
    }

    @Test
    void anUnmatchedRecordIsDroppedWhenTheNextOneStarts() {
        LogRecordFilter filter = new LogRecordFilter(null, Pattern.compile("timeout"));
        assertEquals(
                "ERROR b timeout\n\tat y\n",
                feed(filter, "ERROR a", "\tat x", "ERROR b timeout", "\tat y", "INFO c"),
                "the first record's held lines must not leak into the second");
    }

    @Test
    void heldLinesComeOutInOrderWithTheirOwnIndexes() {
        LogRecordFilter filter = new LogRecordFilter(null, Pattern.compile("Caused by"));
        LogFilter.Collector out = new LogFilter.Collector(64);
        String[] lines = {"INFO ok", "ERROR boom", "\tat a", "Caused by: java.io.IOException", "\tat b"};
        for (int i = 0; i < lines.length; i++) {
            filter.accept(lines[i], i, out);
        }
        assertEquals("ERROR boom\n\tat a\nCaused by: java.io.IOException\n\tat b\n", out.text.toString());
        assertEquals(1, out.lineAt(0));
        assertEquals(4, out.lineAt(3));
        assertEquals(LogLevel.ERROR.ordinal(), out.levelAt(2));
    }

    @Test
    void aRunawayRecordHoldsOnlySoManyLines() {
        LogRecordFilter filter = new LogRecordFilter(null, Pattern.compile("needle"));
        LogFilter.Collector out = new LogFilter.Collector(64);
        filter.accept("ERROR start", 0, out);
        for (int i = 1; i <= LogRecordFilter.MAX_HELD + 500; i++) {
            filter.accept("  filler " + i, i, out);
        }
        filter.accept("  the needle", LogRecordFilter.MAX_HELD + 501, out);
        assertEquals(LogRecordFilter.MAX_HELD + 1, out.count, "the held lines and the match; the overflow was let go");
    }

    @Test
    void wouldKeepJudgesWithoutConsuming() {
        LogRecordFilter filter = new LogRecordFilter(LogLevel.WARN, Pattern.compile("disk"));
        feed(filter, "WARN disk almost full");
        assertTrue(filter.wouldKeep("  92% used"), "a continuation of a record that is already showing");
        assertFalse(filter.wouldKeep("INFO disk fine"), "below the floor");
        assertFalse(filter.wouldKeep("ERROR cpu"), "a new record that does not match");
        assertTrue(filter.wouldKeep("ERROR disk gone"));
        assertEquals(LogLevel.WARN, filter.carry(), "nothing was consumed");
        assertEquals(LogLevel.WARN, filter.levelIfNext("  92% used"));
        assertEquals(LogLevel.ERROR, filter.levelIfNext("ERROR disk gone"));
    }

    private static LogRecordFilter hiding(String include, String exclude) {
        return new LogRecordFilter(null, LogFilter.compile(new LogQuery(include, exclude, false)));
    }

    @Test
    void hidingARecordsFirstLineHidesItsStackTrace() {
        assertEquals(
                "INFO a\nINFO c\n",
                feed(hiding(null, "boom"), "INFO a", "ERROR boom", "\tat x", "Caused by: y", "INFO c"));
    }

    @Test
    void hidingAContinuationLineHidesOnlyThatLine() {
        assertEquals(
                "ERROR boom\n\tat com.example.A\nINFO c\n",
                feed(
                        hiding(null, "springframework"),
                        "ERROR boom",
                        "\tat org.springframework.X",
                        "\tat com.example.A",
                        "INFO c"));
    }

    @Test
    void hideWinsOverShowAndAHiddenLineIsNotHeld() {
        LogFilter.Collector out = new LogFilter.Collector(64);
        LogRecordFilter filter = hiding("timeout", "springframework");
        String[] lines = {"ERROR boom", "\tat org.springframework.X", "Caused by: timeout", "ERROR timeout too"};
        for (int i = 0; i < lines.length; i++) {
            filter.accept(lines[i], i, out);
        }
        assertEquals("ERROR boom\nCaused by: timeout\nERROR timeout too\n", out.text.toString());
        assertEquals(0, out.lineAt(0));
        assertEquals(2, out.lineAt(1), "the held line keeps its own index past the hidden one");

        assertEquals("", feed(hiding("timeout", "timeout"), "ERROR timeout"), "matching both: hidden");
    }

    @Test
    void wouldKeepAppliesTheHidePattern() {
        LogRecordFilter filter = hiding(null, "noisy");
        feed(filter, "INFO noisy heartbeat");
        assertFalse(filter.wouldKeep("  continuation"), "a continuation of a hidden record");
        assertFalse(filter.wouldKeep("INFO noisy again"));
        assertTrue(filter.wouldKeep("INFO quiet"));
    }

    @Test
    void matchCaseAppliesToBothPatterns() {
        LogRecordFilter filter = new LogRecordFilter(null, LogFilter.compile(new LogQuery("Pay", "DEBUG", true)));
        assertEquals("INFO Pay ok\n", feed(filter, "INFO Pay ok", "INFO pay ok", "INFO Pay DEBUG"));
    }

    @Test
    void aPatternThatBacktracksForeverCountsAsNoMatchAndIsReported() {
        LogRecordFilter filter = new LogRecordFilter(null, LogFilter.compile(LogQuery.including(LogMatcherTest.EVIL)));
        String evil = "INFO " + LogMatcherTest.EVIL_LINE;
        long start = System.nanoTime();
        assertEquals("", feed(filter, evil, evil, evil, evil, evil, evil, evil, evil), "no match, after all");
        assertTrue(System.nanoTime() - start < 5_000_000_000L, "it gave up instead of running for ever");
        assertTrue(filter.slow());
    }
}
