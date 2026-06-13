package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Unit tests for the in-memory status message log (pure, no toolkit). */
class MessageLogTest {

    @Test
    void recordsNewestFirst() {
        MessageLog log = new MessageLog();
        log.add("first", 1_000);
        log.add("second", 2_000);
        log.add("third", 3_000);
        List<MessageLog.Entry> entries = log.entries();
        assertEquals(3, entries.size());
        assertEquals("third", entries.get(0).text());
        assertEquals("second", entries.get(1).text());
        assertEquals("first", entries.get(2).text());
        assertEquals(3_000, entries.get(0).epochMillis());
    }

    @Test
    void skipsBlankAndNull() {
        MessageLog log = new MessageLog();
        log.add(null, 1);
        log.add("", 2);
        log.add("   ", 3);
        assertTrue(log.isEmpty());
        assertEquals(0, log.entries().size());
    }

    @Test
    void evictsOldestPastCap() {
        MessageLog log = new MessageLog();
        int total = MessageLog.MAX_ENTRIES + 50;
        for (int i = 0; i < total; i++) {
            log.add("m" + i, i);
        }
        assertEquals(MessageLog.MAX_ENTRIES, log.size());
        List<MessageLog.Entry> entries = log.entries();
        // Newest first: the last added is on top; the oldest 50 were evicted.
        assertEquals("m" + (total - 1), entries.get(0).text());
        assertEquals("m50", entries.get(entries.size() - 1).text());
    }

    @Test
    void clearEmptiesTheLog() {
        MessageLog log = new MessageLog();
        log.add("a", 1);
        log.add("b", 2);
        log.clear();
        assertTrue(log.isEmpty());
        assertEquals(0, log.size());
    }
}
