package com.editora.logviewer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogTimesTest {

    private static final LogTimes.Context UTC = new LogTimes.Context(ZoneOffset.UTC, 2026);

    private static long utc(int month, int day, int h, int m, int s, int ms) {
        return LocalDateTime.of(2026, month, day, h, m, s).toEpochSecond(ZoneOffset.UTC) * 1000 + ms;
    }

    private static long of(String line) {
        return LogTimes.of(line, UTC);
    }

    @Test
    void readsTheCommonTimestamps() {
        long t = utc(11, 24, 10, 0, 5, 123);
        assertEquals(t, of("2026-11-24 10:00:05,123 INFO  [main] c.e.App - up"), "Logback");
        assertEquals(t, of("2026-11-24T10:00:05.123Z INFO up"), "ISO with Z");
        assertEquals(t, of("2026-11-24T11:00:05.123+01:00 INFO up"), "ISO with an offset");
        assertEquals(t, of("[2026/11/24 10:00:05.123] ERROR boom"), "bracketed, slashes");
        assertEquals(t, of("24-Nov-2026 10:00:05.123 SEVERE [main] org.apache.catalina"), "Tomcat");
        assertEquals(
                utc(11, 24, 10, 0, 5, 0),
                of("Nov 24 10:00:05 host sshd[1]: error: x"),
                "syslog, year from the context");
        assertEquals(utc(11, 4, 10, 0, 5, 0), of("Nov  4 10:00:05 host cron[2]: ok"), "syslog, padded day");
        assertEquals(t, of("E1124 10:00:05.123456 1 pod.go:12] failed"), "klog");
        assertEquals(
                utc(11, 24, 9, 0, 5, 0),
                of("127.0.0.1 - - [24/Nov/2026:10:00:05 +0100] \"GET / HTTP/1.1\" 500 12"),
                "access log");
        assertEquals(t, of("{\"level\":\"error\",\"time\":\"2026-11-24T10:00:05.123Z\",\"msg\":\"x\"}"), "JSON text");
        assertEquals(t, of("{\"level\":50,\"time\":" + t + ",\"msg\":\"pino\"}"), "JSON epoch ms");
        assertEquals(t, of("{\"ts\":" + (t / 1000) + ".123,\"msg\":\"zap\"}"), "JSON epoch seconds");
        assertEquals(t, of("{\"ts\":" + t * 1_000_000 + ",\"msg\":\"ns\"}"), "JSON epoch nanoseconds");
    }

    @Test
    void aLineWithoutATimeHasNone() {
        for (String line : List.of(
                "",
                "\tat com.example.Pool.take(Pool.java:42)",
                "Caused by: java.io.IOException",
                "2026-13-45 10:00:00 bad",
                "{\"msg\":\"no time\"}",
                "Foo 24 10:00:00 not a month")) {
            assertEquals(LogTimes.NONE, of(line), line);
        }
    }

    @Test
    void aTimeWithoutAZoneIsInTheContextsZone() {
        LogTimes.Context berlin = new LogTimes.Context(ZoneId.of("Europe/Berlin"), 2026);
        assertEquals(utc(11, 24, 9, 0, 0, 0), LogTimes.of("2026-11-24 10:00:00 INFO x", berlin));
    }

    @Test
    void aTypedTimeOfDayIsOnTheGivenDay() {
        LocalDate day = LocalDate.of(2026, 11, 24);
        assertEquals(utc(11, 24, 10, 5, 0, 0), LogTimes.parseTyped("10:05", day, UTC));
        assertEquals(utc(11, 24, 10, 5, 30, 250), LogTimes.parseTyped(" 10:05:30.25 ", day, UTC));
        assertEquals(utc(11, 25, 8, 0, 0, 0), LogTimes.parseTyped("2026-11-25 08:00", day, UTC));
        assertEquals(utc(11, 25, 0, 0, 0, 0), LogTimes.parseTyped("2026-11-25", day, UTC));
        assertEquals(LogTimes.NONE, LogTimes.parseTyped("10:05", null, UTC), "no day to put it on");
        assertEquals(LogTimes.NONE, LogTimes.parseTyped("soon", day, UTC));
    }

    @Test
    void aTypedRangesEndCoversWhatItNames() {
        LocalDate day = LocalDate.of(2026, 11, 24);
        LogTimes.Typed r = LogTimes.parseRange("10:05 - 10:20", day, UTC);
        assertEquals(utc(11, 24, 10, 5, 0, 0), r.from());
        assertEquals(utc(11, 24, 10, 20, 59, 999), r.to(), "10:20 runs to its last millisecond");
        assertEquals(
                utc(11, 24, 10, 20, 30, 999),
                LogTimes.parseRange("10:05 .. 10:20:30", day, UTC).to());
        assertEquals(
                utc(11, 25, 23, 59, 59, 999),
                LogTimes.parseRange("2026-11-24 – 2026-11-25", day, UTC).to());
        assertEquals(
                new LogTimes.Typed(utc(11, 24, 10, 5, 0, 0), null),
                LogTimes.parseRange("10:05 ..", day, UTC),
                "open end");
        assertEquals(new LogTimes.Typed(null, utc(11, 24, 10, 20, 59, 999)), LogTimes.parseRange("..10:20", day, UTC));
        assertEquals(new LogTimes.Typed(utc(11, 24, 10, 5, 0, 0), null), LogTimes.parseRange("10:05", day, UTC));
        assertEquals(
                new LogTimes.Typed(utc(11, 24, 10, 0, 0, 0), utc(11, 24, 11, 0, 59, 999)),
                LogTimes.parseRange("2026-11-24 10:00 to 2026-11-24 11:00", day, UTC));
        assertEquals(new LogTimes.Typed(null, null), LogTimes.parseRange("  ", day, UTC));
        assertNull(LogTimes.parseRange("10:20 - 10:05", day, UTC), "ends the wrong way round");
        assertNull(LogTimes.parseRange("10:05 - later", day, UTC));
    }

    @Test
    void findsTimesNearALineAndTheFirstLineAtATime() {
        List<String> lines = List.of(
                "header",
                "2026-11-24 10:00:00 INFO a",
                "2026-11-24 10:05:00 ERROR b",
                "\tat x.Y.z(Y.java:1)",
                "2026-11-24 10:04:59 INFO late thread",
                "2026-11-24 10:10:00 INFO c");
        assertEquals(utc(11, 24, 10, 5, 0, 0), LogTimes.timeAt(lines::get, 3, UTC), "a trace line takes its record's");
        assertEquals(LogTimes.NONE, LogTimes.timeAt(lines::get, 0, UTC));
        assertEquals(utc(11, 24, 10, 0, 0, 0), LogTimes.firstTimeFrom(lines::get, lines.size(), 0, UTC));
        assertEquals(2, LogTimes.firstAtOrAfter(lines::get, lines.size(), utc(11, 24, 10, 1, 0, 0), UTC));
        assertEquals(-1, LogTimes.firstAtOrAfter(lines::get, lines.size(), utc(11, 24, 11, 0, 0, 0), UTC));
    }

    @Test
    void formatsForTheUser() {
        assertEquals("2026-11-24 10:00:05", LogTimes.format(utc(11, 24, 10, 0, 5, 0), UTC));
        assertEquals("2026-11-24 10:00:05.120", LogTimes.format(utc(11, 24, 10, 0, 5, 120), UTC));
    }
}
