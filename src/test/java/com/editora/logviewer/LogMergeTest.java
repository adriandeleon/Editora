package com.editora.logviewer;

import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LogMergeTest {

    private static final LogTimes.Context UTC = new LogTimes.Context(ZoneOffset.UTC, 2026);

    @Test
    void recordsInterleaveByTimeAndTracesStayWithTheirRecord() {
        String api = String.join(
                "\n",
                "2026-11-24 10:00:00 INFO  api started",
                "2026-11-24 10:00:02 ERROR api failed",
                "java.lang.IllegalStateException: closed",
                "\tat com.example.Api.call(Api.java:1)",
                "2026-11-24 10:00:04 INFO  api recovered");
        String db = "2026-11-24 10:00:01 INFO  db up\r\n2026-11-24 10:00:03 WARN  db slow\r\n";
        LogMerge.Merged m = LogMerge.merge(List.of(new LogMerge.Input("api", api), new LogMerge.Input("db", db)), UTC);
        assertEquals(
                String.join(
                                "\n",
                                "2026-11-24 10:00:00 INFO  api started",
                                "2026-11-24 10:00:01 INFO  db up",
                                "2026-11-24 10:00:02 ERROR api failed",
                                "java.lang.IllegalStateException: closed",
                                "\tat com.example.Api.call(Api.java:1)",
                                "2026-11-24 10:00:03 WARN  db slow",
                                "2026-11-24 10:00:04 INFO  api recovered")
                        + "\n",
                m.text());
        assertArrayEquals(new int[] {0, 1, 0, 0, 0, 1, 0}, m.sources());
        assertEquals(List.of("api", "db"), m.names());
        assertEquals(0, m.untimed());
    }

    @Test
    void aLogKeepsItsOwnOrderAndTiesGoToTheEarlierLog() {
        String a = "2026-11-24 10:00:05 INFO a1\n2026-11-24 10:00:01 INFO a2 written late by its thread\n";
        String b = "2026-11-24 10:00:05 INFO b1\n";
        LogMerge.Merged m = LogMerge.merge(List.of(new LogMerge.Input("a", a), new LogMerge.Input("b", b)), UTC);
        assertEquals(
                "2026-11-24 10:00:05 INFO a1\n2026-11-24 10:00:01 INFO a2 written late by its thread\n"
                        + "2026-11-24 10:00:05 INFO b1\n",
                m.text(),
                "a2 is not pulled ahead of a1: a merge of runs, not a sort");
    }

    @Test
    void linesWithoutATimeComeFirstAndAreCounted() {
        String a = "banner line\nsecond banner\n2026-11-24 10:00:00 INFO a\n";
        String b = "no time at all\n";
        LogMerge.Merged m = LogMerge.merge(List.of(new LogMerge.Input("a", a), new LogMerge.Input("b", b)), UTC);
        assertEquals("banner line\nsecond banner\nno time at all\n2026-11-24 10:00:00 INFO a\n", m.text());
        assertEquals(3, m.untimed());
        assertEquals(4, m.lines());
    }

    @Test
    void emptyLogsMergeToNothing() {
        LogMerge.Merged m = LogMerge.merge(List.of(new LogMerge.Input("a", ""), new LogMerge.Input("b", "")), UTC);
        assertEquals("", m.text());
        assertEquals(0, m.lines());
        LogSources sources = new LogSources(m.names(), m.sources());
        assertEquals(-1, sources.sourceOf(0));
    }
}
