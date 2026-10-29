package com.editora.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedFileSystemException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A failure the Project Map recovers from is logged once when it is a bug, never when it is the disk's. */
class ProjectMapLogTest {

    @Test
    void diskAndConnectionFailuresAreExpected() {
        assertTrue(ProjectMapLog.isInputOutput(new UncheckedIOException(new IOException("gone"))));
        assertTrue(ProjectMapLog.isInputOutput(new ClosedFileSystemException()));
        assertTrue(ProjectMapLog.isInputOutput(new IllegalStateException("wrapped", new IOException("sftp"))));
        assertFalse(ProjectMapLog.isInputOutput(new NullPointerException()));
    }

    @Test
    void anUnexpectedFailureIsLoggedOncePerPlaceAndKind() {
        Logger logger = Logger.getLogger(ProjectMapLog.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.addHandler(handler);
        try {
            String where = "a test place " + System.nanoTime();
            NullPointerException bug = new NullPointerException("a bug");
            ProjectMapLog.unexpected(where, bug);
            ProjectMapLog.unexpected(where, new NullPointerException("the same bug again"));
            ProjectMapLog.unexpected(where, new UncheckedIOException(new IOException("the disk")));
            assertEquals(1, records.size(), "once per place and kind, and never for the disk");
            assertSame(bug, records.getFirst().getThrown());
            assertTrue(records.getFirst().getMessage().contains(where));
        } finally {
            logger.removeHandler(handler);
        }
    }
}
