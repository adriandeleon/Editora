package com.editora.ui;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.ClosedFileSystemException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where the Project Map notes a failure it recovered from but did not expect. The Map keeps going after any
 * failure (an empty listing, an unmarked row, no outline), so without a record a bug would only show as a map
 * that looks wrong. Failures to read the disk or a remote folder are expected and stay quiet; anything else
 * reaches the debug log ({@link DebugLog}) once per place and exception type, so a repeating failure does not
 * flood it.
 */
final class ProjectMapLog {

    private static final Logger LOG = Logger.getLogger(ProjectMapLog.class.getName());
    private static final int MAX_REPORTED = 256;
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

    private ProjectMapLog() {}

    /** Records {@code failure} at {@code where} (a few words: "listing a folder") unless it is an I/O failure. */
    static void unexpected(String where, Throwable failure) {
        if (isInputOutput(failure)) {
            return;
        }
        if (REPORTED.size() < MAX_REPORTED
                && REPORTED.add(where + '|' + failure.getClass().getName())) {
            LOG.log(Level.WARNING, "Project Map: " + where + " failed; the map carries on without it", failure);
        }
    }

    /** Whether {@code failure} is a disk or connection failure, which the Map shows rather than logs. */
    static boolean isInputOutput(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException
                    || cause instanceof UncheckedIOException
                    || cause instanceof ClosedFileSystemException) {
                return true;
            }
        }
        return false;
    }
}
