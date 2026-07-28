package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageLogSeverityTest {

    @Test
    void messagesDefaultToInfo() {
        MessageLog log = new MessageLog();
        log.add("saved", 1L);

        assertEquals(MessageLog.Severity.INFO, log.entries().get(0).severity());
        assertEquals(0, log.unreadErrors(), "nothing to flag");
    }

    @Test
    void errorsAreCountedUntilRead() {
        MessageLog log = new MessageLog();
        log.add("routine", MessageLog.Severity.INFO, 1L);
        log.add("could not save", MessageLog.Severity.ERROR, 2L);
        log.add("also broke", MessageLog.Severity.ERROR, 3L);
        log.add("routine again", MessageLog.Severity.INFO, 4L);

        assertEquals(2, log.unreadErrors(), "warnings and info do not count");

        log.markRead();
        assertEquals(0, log.unreadErrors());
    }

    @Test
    void warningsDoNotCountAsUnreadErrors() {
        MessageLog log = new MessageLog();
        log.add("careful", MessageLog.Severity.WARN, 1L);

        assertEquals(0, log.unreadErrors());
        assertEquals(MessageLog.Severity.WARN, log.entries().get(0).severity());
    }

    /**
     * The count tracks "something failed that you haven't looked at", not "this entry is still retained" — so
     * an error pushed out by the 200-entry cap must not silently take its marker with it.
     */
    @Test
    void anEvictedErrorStillCounts() {
        MessageLog log = new MessageLog();
        log.add("the failure", MessageLog.Severity.ERROR, 1L);
        for (int i = 0; i < MessageLog.MAX_ENTRIES + 5; i++) {
            log.add("chatter " + i, MessageLog.Severity.INFO, 10L + i);
        }

        assertEquals(MessageLog.MAX_ENTRIES, log.size(), "the error was evicted");
        assertEquals(1, log.unreadErrors(), "but it is still flagged as unseen");
    }

    @Test
    void clearingTheLogCountsAsHavingSeenThem() {
        MessageLog log = new MessageLog();
        log.add("boom", MessageLog.Severity.ERROR, 1L);
        log.clear();

        assertEquals(0, log.unreadErrors());
    }

    @Test
    void blankMessagesAreStillIgnoredWhateverTheirSeverity() {
        MessageLog log = new MessageLog();
        log.add("  ", MessageLog.Severity.ERROR, 1L);
        log.add(null, MessageLog.Severity.ERROR, 2L);

        assertEquals(0, log.size());
        assertEquals(0, log.unreadErrors(), "a blank error is not a failure to flag");
    }
}
