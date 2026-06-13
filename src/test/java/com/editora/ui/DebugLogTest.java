package com.editora.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DebugLog}'s pure helpers (logger-name shortening, bounded buffer, formatting). */
class DebugLogTest {

    @Test
    void shortNameTakesLastSegment() {
        assertEquals("LspManager", DebugLog.shortName("com.editora.lsp.LspManager"));
        assertEquals("Bare", DebugLog.shortName("Bare"));
        assertEquals("?", DebugLog.shortName(null));
        assertEquals("?", DebugLog.shortName("   "));
    }

    @Test
    void bufferIsBoundedAndEvictsOldest() {
        DebugLog.clear();
        for (int i = 0; i < DebugLog.MAX_RECORDS + 5; i++) {
            DebugLog.append("rec" + i);
        }
        String[] lines = DebugLog.snapshot().split("\\R");
        assertEquals(DebugLog.MAX_RECORDS, lines.length);
        assertEquals("rec5", lines[0]); // the first five were evicted
        assertEquals("rec" + (DebugLog.MAX_RECORDS + 4), lines[lines.length - 1]);
        DebugLog.clear();
    }

    @Test
    void formatIncludesLevelLoggerMessageAndStackTrace() {
        LogRecord r = new LogRecord(Level.WARNING, "boom {0}");
        r.setLoggerName("com.editora.lsp.LspManager");
        r.setParameters(new Object[] {"here"});
        r.setThrown(new IllegalStateException("nope"));
        String formatted = DebugLog.format(r);
        assertTrue(formatted.contains("WARNING"), formatted);
        assertTrue(formatted.contains("LspManager"), formatted);
        assertTrue(formatted.contains("boom here"), formatted); // parameter substituted
        assertTrue(formatted.contains("IllegalStateException"), formatted); // stack trace appended
    }

    @Test
    void installedHandlerCapturesEmittedLogRecords() {
        DebugLog.install(); // idempotent
        DebugLog.clear();
        Logger.getLogger("com.editora.test.Capture").warning("hello-capture-marker");
        String snapshot = DebugLog.snapshot();
        assertTrue(snapshot.contains("hello-capture-marker"), snapshot);
        assertTrue(snapshot.contains("WARNING"), snapshot);
        DebugLog.clear();
    }
}
