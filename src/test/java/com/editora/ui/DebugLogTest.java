package com.editora.ui;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertBoundedAndEvictsOldest();
    }

    /**
     * The same guarantee while another thread appends, which is what a full run looks like: the fork is
     * reused, so an earlier test can leave the root-logger handler installed and a scene still logging CSS
     * warnings from the FX thread.
     */
    @Test
    void bufferIsBoundedAndEvictsOldestWhileAnotherThreadLogs() throws Exception {
        AtomicBoolean running = new AtomicBoolean(true);
        CountDownLatch logging = new CountDownLatch(1);
        Thread noise = new Thread(
                () -> {
                    while (running.get()) {
                        DebugLog.append("WARNING  CssStyleHelper: noise" + System.lineSeparator() + "\tat a.Frame");
                        logging.countDown();
                        Thread.onSpinWait();
                    }
                },
                "debug-log-noise");
        noise.setDaemon(true);
        noise.start();
        try {
            assertTrue(logging.await(10, TimeUnit.SECONDS), "the noise thread never started");
            assertBoundedAndEvictsOldest();
        } finally {
            running.set(false);
            noise.join();
            DebugLog.clear();
        }
    }

    /**
     * Fills the buffer past its bound and checks what is left. Only this test's own records are compared:
     * a record logged by another thread meanwhile takes a slot too, so it pushes out one more of ours —
     * which must still be the oldest one.
     */
    private static void assertBoundedAndEvictsOldest() {
        String prefix = "bounded-rec-";
        int appended = DebugLog.MAX_RECORDS + 5;
        DebugLog.clear();
        for (int i = 0; i < appended; i++) {
            DebugLog.append(prefix + i);
        }
        List<String> records = DebugLog.recordsForTest();
        DebugLog.clear();

        assertEquals(DebugLog.MAX_RECORDS, records.size());
        List<String> own = records.stream().filter(r -> r.startsWith(prefix)).toList();
        assertFalse(own.isEmpty());
        // What survives is the newest run, in order: at least the first five are gone, never a later one.
        List<String> newest = IntStream.range(appended - own.size(), appended)
                .mapToObj(i -> prefix + i)
                .toList();
        assertEquals(newest, own);
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
        List<String> records = DebugLog.recordsForTest();
        DebugLog.clear();
        // Its own record, not whichever one another thread logged at WARNING meanwhile.
        List<String> captured =
                records.stream().filter(r -> r.contains("hello-capture-marker")).toList();
        assertEquals(1, captured.size(), records.toString());
        assertTrue(captured.get(0).contains("WARNING"), captured.get(0));
        assertTrue(captured.get(0).contains("Capture"), captured.get(0));
    }
}
