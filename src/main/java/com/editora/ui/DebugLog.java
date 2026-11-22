package com.editora.ui;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.jspecify.annotations.Nullable;

/**
 * In-memory capture of the app's {@code java.util.logging} output plus uncaught exceptions, so a
 * packaged build (DMG / MSI / DEB) — where stderr and the console aren't visible — can still show a
 * debug log <em>in-app</em> (the {@code view.debugLog} command and the Settings → Advanced button, both
 * surfaced through {@link DebugLogWindow}). The same records are also mirrored to a session file in the
 * config dir ({@code editora-session.log}, the one before kept as {@code editora-session.prev.log}) so they
 * survive a crash and can go into a problem report ({@code help.reportProblem}, {@code editora --report}).
 *
 * <p>Install once from {@code App.main} (after the headless property) via {@link #install()}; call
 * {@link #attachFile(Path)} from {@code App.start} once the config dir is known. All operations are
 * thread-safe (log records arrive on many threads) and bounded to {@link #MAX_RECORDS} entries.
 */
public final class DebugLog {

    /** Max retained records (each may be multi-line, e.g. a stack trace); oldest evicted past this. */
    static final int MAX_RECORDS = 2000;

    static final String SESSION_FILE = "editora-session.log";
    static final String PREVIOUS_SESSION_FILE = "editora-session.prev.log";

    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Object LOCK = new Object();
    private static final Deque<String> RECORDS = new ArrayDeque<>();
    private static boolean installed;
    private static boolean captureSuspendedForTest; // guarded by LOCK; see suspendCaptureForTest
    private static @Nullable PrintWriter file; // optional mirror to <configDir>/editora-session.log

    /** Most characters written to the session file in one session: a record logged in a loop must not fill the disk. */
    static final long MAX_FILE_CHARS = 8L * 1024 * 1024;

    private static long fileLimit = MAX_FILE_CHARS; // guarded by LOCK; a test lowers it
    private static long fileWritten; // guarded by LOCK: characters sent to the session file so far
    private static boolean fileFull; // guarded by LOCK: the limit was reached, the closing note written

    /** The last line of a session file that reached {@link #MAX_FILE_CHARS}. */
    static final String FILE_FULL_NOTE =
            "[The session log reached its size limit: later records are only in the in-app log (View: Application Log).]";

    /**
     * Where the session file and the console are written: {@code debug-log-writer}, never the thread that logs
     * (PERF-01, Pauta 6.2.0: a record logged on the FX thread must not write a file there). Heard by
     * {@link com.editora.perf.BlockingSites} on the writer, once per batch it writes.
     */
    public static final String WRITE_SITE = "DebugLog.write";

    /** One write for the writer, in the order logged: a line for a file, text for stderr, or a flush marker. */
    private record Pending(
            @Nullable PrintWriter to, @Nullable String text, java.util.concurrent.@Nullable CountDownLatch flushed) {}

    private static final java.util.concurrent.LinkedBlockingQueue<Pending> PENDING =
            new java.util.concurrent.LinkedBlockingQueue<>();

    private static @Nullable Thread writer; // guarded by LOCK; started with the first write

    /** The root logger's console handlers, whose output the writer prints instead (see {@link #install()}). */
    private static final List<Handler> CONSOLE = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** The least time between two echo messages about uncaught exceptions: one that repeats must not flood. */
    static final long UNCAUGHT_ECHO_INTERVAL_MS = 10_000;

    /** Where an uncaught exception is announced besides this log: the focused window's echo area. */
    private static volatile java.util.function.@Nullable Consumer<String> uncaughtEcho;

    private static final java.util.concurrent.atomic.AtomicLong LAST_UNCAUGHT_ECHO =
            new java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE);

    private DebugLog() {}

    /** Attaches a root-logger handler + a default uncaught-exception handler. Idempotent. */
    public static void install() {
        synchronized (LOCK) {
            if (installed) {
                return;
            }
            installed = true;
        }
        // The root logger's console handlers print on the thread that logs, the FX thread included; their text is
        // formatted there (a string, no I/O) and printed by the writer, in order with the session file (PERF-01).
        Logger root = Logger.getLogger("");
        for (Handler h : root.getHandlers()) {
            if (h instanceof java.util.logging.ConsoleHandler) {
                root.removeHandler(h);
                CONSOLE.add(h);
            }
        }
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    capture(format(record));
                }
                for (Handler console : CONSOLE) {
                    toConsole(console, record);
                }
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL); // capture everything the (already level-gated) loggers emit
        Logger.getLogger("").addHandler(handler);

        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            String record = line(
                            Instant.now(),
                            "SEVERE",
                            "uncaught",
                            "Uncaught exception in thread \"" + thread.getName() + "\"" + focusContext())
                    + System.lineSeparator()
                    + stackTrace(error);
            capture(record);
            // Native Image smoke runs can fail before start() attaches the session log.
            // Keep those early failures visible in the captured process stderr.
            if (Boolean.getBoolean("editora.debug.stderr")) {
                enqueue(new Pending(null, record + System.lineSeparator(), null));
            }
            announceUncaught(error, System.currentTimeMillis(), javafx.application.Platform::runLater);
        });
    }

    /**
     * Sets where an uncaught exception is announced besides this log (the window manager passes the focused
     * window's echo area), or null for nowhere. The log alone is easy to miss: the action the user took did
     * nothing, and nothing said why.
     */
    static void setUncaughtEcho(java.util.function.@Nullable Consumer<String> echo) {
        uncaughtEcho = echo;
    }

    /**
     * Posts one echo message for an uncaught exception: an error that names the kind of failure and points
     * at this log. Never a dialog, and never a stack trace in the status bar. At most one every
     * {@link #UNCAUGHT_ECHO_INTERVAL_MS}: an exception thrown on every pulse or keystroke must not turn the
     * echo area into a strobe, and each one is in the log anyway.
     *
     * @return whether a message was handed to {@code onFx}
     */
    static boolean announceUncaught(Throwable error, long nowMillis, java.util.function.Consumer<Runnable> onFx) {
        try {
            java.util.function.Consumer<String> echo = uncaughtEcho;
            if (echo == null) {
                return false;
            }
            long last = LAST_UNCAUGHT_ECHO.get();
            if (last != Long.MIN_VALUE && nowMillis - last < UNCAUGHT_ECHO_INTERVAL_MS) {
                return false;
            }
            if (!LAST_UNCAUGHT_ECHO.compareAndSet(last, nowMillis)) {
                return false; // another thread's exception is being announced right now
            }
            String message = com.editora.i18n.Messages.tr(
                    "status.uncaughtError", error.getClass().getSimpleName());
            onFx.accept(() -> echo.accept(message));
            return true;
        } catch (Throwable ignored) {
            return false; // a diagnostic must never become the failure it is describing
        }
    }

    /** Forgets when the last uncaught exception was announced, so a test starts from a clean slate. */
    static void resetUncaughtEchoForTest() {
        LAST_UNCAUGHT_ECHO.set(Long.MIN_VALUE);
    }

    /**
     * What had the keyboard focus, appended to an uncaught FX-thread exception — {@code [focus:
     * ListView.git-tree in Git Log]}.
     *
     * <p>A crash inside a JavaFX control's own event handling produces a stack with <b>no application
     * frames at all</b>, so it names the control's class and nothing about which of the app's dozen
     * ListViews it was. That turns a bug report into guesswork: an IOOBE thrown from
     * {@code ListViewBehavior} while selecting a row could have come from any panel. The focus owner plus
     * its style classes usually identifies it outright.
     *
     * <p>Best-effort and defensive by construction: it runs while the toolkit is already in a bad state, so
     * everything is guarded and it only reads plain properties — never calls back into layout or user code.
     * Returns {@code ""} off the FX thread (where the scene graph must not be touched) and on any failure.
     */
    private static String focusContext() {
        try {
            if (!javafx.application.Platform.isFxApplicationThread()) {
                return "";
            }
            javafx.stage.Window window = javafx.stage.Window.getWindows().stream()
                    .filter(javafx.stage.Window::isFocused)
                    .findFirst()
                    .orElse(null);
            javafx.scene.Scene scene = window == null ? null : window.getScene();
            return describeFocus(scene == null ? null : scene.getFocusOwner());
        } catch (Throwable ignored) {
            return ""; // a diagnostic must never become the failure it is describing
        }
    }

    /** Renders one focused node as {@code  [focus: ListView.git-tree]}; {@code ""} for none. */
    static String describeFocus(javafx.scene.@Nullable Node node) {
        if (node == null) {
            return "";
        }
        String classes = String.join(".", node.getStyleClass());
        return " [focus: " + node.getClass().getSimpleName() + (classes.isEmpty() ? "" : "." + classes) + "]";
    }

    /**
     * Begins mirroring captured records to {@code <configDir>/editora-session.log}, then flushes everything
     * captured so far. The prior session's file is kept first as {@code editora-session.prev.log}: a crash is
     * reported from the launch after it, and that launch must not erase the log that recorded it. Best-effort:
     * I/O errors disable the mirror (or skip the copy) without affecting in-memory capture.
     */
    public static void attachFile(Path configDir) {
        if (configDir == null) {
            return;
        }
        com.editora.perf.BlockingSites.enter("DebugLog.attachFile");
        flushWrites(1000); // the writes queued for a file attached before go out before it is replaced
        synchronized (LOCK) {
            if (file != null) {
                file.close(); // the file attached before: its queued writes went out above
                file = null;
            }
            try {
                Path path = configDir.resolve(SESSION_FILE);
                keepPreviousSession(path, configDir.resolve(PREVIOUS_SESSION_FILE));
                // Owner-only, like everything else in the config dir: this records the user's file paths,
                // project names and error text. newBufferedWriter truncates and keeps the mode.
                com.editora.config.ConfigWriter.createOwnerOnly(path);
                PrintWriter out = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8));
                file = out;
                fileWritten = 0;
                fileFull = false;
                for (String record : RECORDS) {
                    String line = toFile(record);
                    if (line == null) {
                        break;
                    }
                    out.println(line);
                }
                out.flush();
            } catch (IOException | RuntimeException e) {
                file = null;
            }
        }
    }

    /** Moves a non-empty prior session log aside; an empty one (a session that logged nothing) is not worth it. */
    private static void keepPreviousSession(Path current, Path previous) {
        try {
            if (Files.isRegularFile(current) && Files.size(current) > 0) {
                Files.move(current, previous, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // the new session is still logged; only the old one is overwritten, as before
        }
    }

    /** TEST SEAM — stops mirroring and closes the session file, so a test can delete the folder it attached. */
    public static void detachFileForTest() {
        flushWrites(5000);
        synchronized (LOCK) {
            if (file != null) {
                file.close();
                file = null;
            }
        }
    }

    /**
     * TEST SEAM — while suspended, records arriving through the installed log handler and the uncaught
     * exception handler are dropped; {@link #append(String)} still works. Surefire reuses the fork, so once
     * any test has called {@link #install()} every thread's log records land in this buffer — CSS warnings
     * from a scene an earlier test left pulsing among them — and a test asserting exact contents would race
     * them. Checked under the same lock as the append, so nothing captured after this returns can slip in.
     * Always resume in a {@code finally} or {@code @AfterEach}.
     */
    static void suspendCaptureForTest(boolean suspended) {
        synchronized (LOCK) {
            captureSuspendedForTest = suspended;
        }
    }

    /** The session log file path under {@code configDir}, for display in the viewer. */
    public static @Nullable Path sessionFile(Path configDir) {
        return configDir == null ? null : configDir.resolve(SESSION_FILE);
    }

    /** The previous session's log under {@code configDir}, kept by {@link #attachFile} for a problem report. */
    public static @Nullable Path previousSessionFile(Path configDir) {
        return configDir == null ? null : configDir.resolve(PREVIOUS_SESSION_FILE);
    }

    /** The full captured log as one newline-joined string (oldest first). */
    public static String snapshot() {
        synchronized (LOCK) {
            return String.join(System.lineSeparator(), RECORDS);
        }
    }

    /**
     * TEST SEAM — the retained records, oldest first, one element per record. {@link #snapshot()} cannot be
     * split back into records (a record with a stack trace spans lines), and a test must be able to tell its
     * own records from whatever another thread logged meanwhile.
     */
    static List<String> recordsForTest() {
        synchronized (LOCK) {
            return List.copyOf(RECORDS);
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
            String line = file == null ? null : toFile(record);
            if (line != null) {
                enqueue(new Pending(file, line, null)); // written on the writer, in this order
            }
        }
    }

    /**
     * What goes to the session file for {@code record}: the record, or — once, when it would take the file past
     * {@link #fileLimit} — {@link #FILE_FULL_NOTE}; null once the file is full. Counts what it lets through. Under
     * {@link #LOCK}.
     */
    private static @Nullable String toFile(String record) {
        if (fileFull) {
            return null;
        }
        long size = record.length() + 1L;
        if (fileWritten + size > fileLimit) {
            fileFull = true;
            return FILE_FULL_NOTE;
        }
        fileWritten += size;
        return record;
    }

    /** TEST SEAM — the session file's limit in characters; {@link #MAX_FILE_CHARS} again with a negative value. */
    static void setFileLimitForTest(long chars) {
        synchronized (LOCK) {
            fileLimit = chars < 0 ? MAX_FILE_CHARS : chars;
        }
    }

    /** TEST SEAM — the session file now written to, or null. */
    static @Nullable PrintWriter fileForTest() {
        synchronized (LOCK) {
            return file;
        }
    }

    /** {@code console}'s text for {@code record}, printed by the writer, if the handler would print it. */
    private static void toConsole(Handler console, LogRecord record) {
        try {
            if (console.isLoggable(record)) {
                enqueue(new Pending(null, console.getFormatter().format(record), null));
            }
        } catch (RuntimeException ignored) {
            // a diagnostic must never become the failure it is describing
        }
    }

    private static void enqueue(Pending pending) {
        synchronized (LOCK) {
            if (writer == null) {
                Thread t = Thread.ofPlatform().daemon().name("debug-log-writer").unstarted(DebugLog::writeLoop);
                writer = t;
                t.start();
                Runtime.getRuntime()
                        .addShutdownHook(
                                Thread.ofPlatform().name("debug-log-flush").unstarted(() -> flushWrites(1000)));
            }
            PENDING.add(pending);
        }
    }

    /** The writer: everything queued, in order, then one flush of what it wrote to, then the waiting markers. */
    private static void writeLoop() {
        List<Pending> batch = new java.util.ArrayList<>();
        while (true) {
            try {
                batch.add(PENDING.take());
            } catch (InterruptedException e) {
                return;
            }
            PENDING.drainTo(batch);
            com.editora.perf.BlockingSites.enter(WRITE_SITE);
            java.util.Set<PrintWriter> written = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            boolean console = false;
            for (Pending p : batch) {
                PrintWriter to = p.to();
                String text = p.text();
                try {
                    if (to != null && text != null) {
                        to.println(text);
                        written.add(to);
                    } else if (text != null) {
                        System.err.print(text);
                        console = true;
                    }
                } catch (RuntimeException ignored) {
                    // a closed or failing file loses this line; the in-memory log keeps it
                }
            }
            written.forEach(PrintWriter::flush);
            if (console) {
                System.err.flush();
            }
            for (Pending p : batch) {
                java.util.concurrent.CountDownLatch flushed = p.flushed();
                if (flushed != null) {
                    flushed.countDown();
                }
            }
            batch.clear();
        }
    }

    /**
     * Waits until every record logged before the call is in the session file and on the console, up to {@code
     * timeoutMs}. For the quit ({@code Application.stop()}, the shutdown hook), tests and threads that are not
     * the FX thread; true when it got there in time (or nothing was ever written).
     */
    public static boolean flushWrites(long timeoutMs) {
        synchronized (LOCK) {
            if (writer == null) {
                return true;
            }
        }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        enqueue(new Pending(null, null, done));
        try {
            return done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A record from the log handler or the uncaught-exception handler, unless a test suspended capture. */
    private static void capture(String record) {
        synchronized (LOCK) {
            if (!captureSuspendedForTest) {
                append(record);
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
