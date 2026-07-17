package com.editora.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * In-memory capture of the app's {@code java.util.logging} output plus uncaught exceptions, so a
 * packaged build (DMG / MSI / DEB) — where stderr and the console aren't visible — can still show a
 * debug log <em>in-app</em> (the {@code view.debugLog} command and the Settings → Advanced button, both
 * surfaced through {@link DebugLogWindow}). The same records are also mirrored to a session file in the
 * config dir ({@code editora-session.log}) so they survive a crash and can be attached to a bug report.
 *
 * <p>Install once from {@code App.main} (after the headless property) via {@link #install()}; call
 * {@link #attachFile(Path)} from {@code App.start} once the config dir is known. All operations are
 * thread-safe (log records arrive on many threads) and bounded to {@link #MAX_RECORDS} entries.
 */
public final class DebugLog {

    /** Max retained records (each may be multi-line, e.g. a stack trace); oldest evicted past this. */
    static final int MAX_RECORDS = 2000;

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Object LOCK = new Object();
    private static final Deque<String> RECORDS = new ArrayDeque<>();
    private static boolean installed;
    private static PrintWriter file; // optional mirror to <configDir>/editora-session.log

    private DebugLog() {}

    /** Attaches a root-logger handler + a default uncaught-exception handler. Idempotent. */
    public static void install() {
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            installed = true;
        }
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    append(format(record));
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL); // capture everything the (already level-gated) loggers emit
        Logger.getLogger("").addHandler(handler);

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> append(
                line(Instant.now(), "SEVERE", "uncaught", "Uncaught exception in thread \"" + thread.getName() + "\"")
                        + System.lineSeparator()
                        + stackTrace(error)));
    }

    /**
     * Begins mirroring captured records to {@code <configDir>/editora-session.log} (truncating any prior
     * session's file first, then flushing everything captured so far). Best-effort: I/O errors disable
     * the mirror without affecting in-memory capture.
     */
    public static void attachFile(Path configDir) {
        if (configDir == null) {
            return;
        }
        synchronized (LOCK) {
            try {
                Path path = configDir.resolve("editora-session.log");
                // Owner-only, like everything else in the config dir: this records the user's file paths,
                // project names and error text. newBufferedWriter truncates and keeps the mode.
                com.editora.config.ConfigWriter.createOwnerOnly(path);
                file = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
                for (String record : RECORDS) {
                    file.println(record);
                }
                file.flush();
            } catch (IOException | RuntimeException e) {
                file = null;
            }
        }
    }

    /** The session log file path under {@code configDir}, for display in the viewer. */
    public static Path sessionFile(Path configDir) {
        return configDir == null ? null : configDir.resolve("editora-session.log");
    }

    /** The full captured log as one newline-joined string (oldest first). */
    public static String snapshot() {
        synchronized (LOCK) {
            return String.join(System.lineSeparator(), RECORDS);
        }
    }

    /** Clears the in-memory buffer (does not delete the session file). */
    public static void clear() {
        synchronized (LOCK) {
            RECORDS.clear();
        }
    }

    // --- internals (package-private for testing) -------------------------------------------------

    static void append(String record) {
        synchronized (LOCK) {
            RECORDS.addLast(record);
            while (RECORDS.size() > MAX_RECORDS) {
                RECORDS.removeFirst();
            }
            if (file != null) {
                file.println(record);
                file.flush();
            }
        }
    }

    static String format(LogRecord record) {
        String message = formatMessage(record);
        String base =
                line(record.getInstant(), record.getLevel().getName(), shortName(record.getLoggerName()), message);
        return record.getThrown() == null ? base : base + System.lineSeparator() + stackTrace(record.getThrown());
    }

    private static String line(Instant when, String level, String logger, String message) {
        return TIME.format(when) + "  " + level + "  " + logger + ": " + message;
    }

    /** The simple (last-segment) name of a dotted logger name; {@code "?"} when absent. */
    static String shortName(String loggerName) {
        if (loggerName == null || loggerName.isBlank()) {
            return "?";
        }
        int dot = loggerName.lastIndexOf('.');
        return dot >= 0 && dot < loggerName.length() - 1 ? loggerName.substring(dot + 1) : loggerName;
    }

    private static String formatMessage(LogRecord record) {
        String message = record.getMessage();
        if (message == null) {
            return "";
        }
        Object[] params = record.getParameters();
        if (params != null && params.length > 0 && message.contains("{0")) {
            try {
                return MessageFormat.format(message, params);
            } catch (RuntimeException ignored) {
                // fall back to the raw message
            }
        }
        return message;
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString().stripTrailing();
    }
}
