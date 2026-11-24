package com.editora.logviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LogMarksTest {

    private static final String LOG = """
            2026-11-24 10:00:00 INFO  started
            2026-11-24 10:00:01 WARN  pool low\r
            2026-11-24 10:00:02 ERROR request failed
            java.lang.IllegalStateException: closed
            \tat com.example.Pool.take(Pool.java:42)

            2026-11-24 10:00:03 FATAL out of memory
            2026-11-24 10:00:04 DEBUG done""";

    @Test
    void marksWarningsAndWorseByTheirRecordsFirstLine() {
        LogMarks marks = LogMarks.scan(LOG, () -> false);
        assertArrayEquals(new int[] {1, 2, 6}, marks.lines(), "the stack trace is not marked again");
        assertEquals(LogLevel.WARN, marks.level(0));
        assertEquals(LogLevel.ERROR, marks.level(1));
        assertEquals(LogLevel.FATAL, marks.level(2));
        assertEquals(8, marks.lineCount());
    }

    @Test
    void aCancelledScanReturnsNothing() {
        assertNull(LogMarks.scan(LOG, () -> true));
    }

    @Test
    void anEmptyTextHasOneLineAndNoMarks() {
        LogMarks marks = LogMarks.scan("", () -> false);
        assertEquals(0, marks.size());
        assertEquals(1, marks.lineCount());
        assertEquals(-1, marks.nearest(0));
    }

    @Test
    void nearestFindsTheClosestMarkEitherSide() {
        LogMarks marks = new LogMarks(new int[] {10, 20, 40}, new byte[] {3, 4, 4}, 50);
        assertEquals(0, marks.nearest(0));
        assertEquals(0, marks.nearest(15), "a tie goes to the earlier mark");
        assertEquals(1, marks.nearest(16));
        assertEquals(1, marks.nearest(20));
        assertEquals(2, marks.nearest(49));
    }
}
