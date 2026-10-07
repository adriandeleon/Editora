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
}
