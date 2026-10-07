package com.editora.logviewer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure heuristics for recognizing server-log lines: detecting a line's severity {@link LogLevel} and
 * sniffing whether a sample of text looks like a log file at all. java.base only, so it is unit-tested.
 *
 * <p>Detection is intentionally conservative and prefix-scoped — a level token is only honored when it
 * appears near the start of the line (where frameworks put it: {@code "<timestamp> LEVEL logger: msg"}
 * or {@code "[LEVEL]"}), so the word "error" inside a message body never reclassifies an INFO line. A
 * line with no level (a stack-trace frame, a wrapped message) yields {@code null}; callers treat such
 * lines as a continuation of the preceding line.
 */
public final class LogPatterns {

    /** Only the leading slice of a line is scanned for a level token (frameworks front-load it). */
    private static final int LEVEL_SCAN_PREFIX = 96;

    /** A JSON record is one object per line with its keys in any order, so the level may sit anywhere in it. */
    private static final int JSON_SCAN_LIMIT = 4096;

    private static final String LEVEL_WORDS = LogLevel.wordAlternation();

    private static final String TIMESTAMP = "(?:\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}"
            + "|\\d{2}:\\d{2}:\\d{2}"
            + "|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})";

    /**
     * An UPPERCASE level keyword as a standalone token (case-sensitive on purpose). Real logs emit the
     * level in upper case — matching case-insensitively would colour the lowercase word "error" inside an
     * ordinary message/prose line. Lowercase levels are recognized only in the positions below.
     *
     * <p>A word that is part of an identifier or a file name is not a level: {@code ERROR_CODES},
     * {@code com.example.ERROR}, {@code CONFIG.java}.
     */
    private static final Pattern LEVEL_UPPER =
            Pattern.compile("(?<![A-Za-z0-9_.$])(?:" + LEVEL_WORDS + ")(?![A-Za-z0-9_])(?!\\.[A-Za-z])");

    /** A bracketed level, any case — nginx ({@code [error]}), many C/Go loggers ({@code [warn]}). */
    private static final Pattern LEVEL_BRACKETED = Pattern.compile("(?i)\\[\\s*(" + LEVEL_WORDS + ")\\s*\\]");

    /** A {@code level=error} / {@code "level":"warn"} / {@code severity: info} field (structured logs). */
    private static final Pattern LEVEL_KEYVALUE =
            Pattern.compile("(?i)\"?(?:level|lvl|severity|levelname)\"?\\s*[=:]\\s*\"?(" + LEVEL_WORDS + ")");

    /** pino / bunyan: {@code "level":50} — 10 trace, 20 debug, 30 info, 40 warn, 50 error, 60 fatal. */
    private static final Pattern LEVEL_NUMERIC = Pattern.compile("\"level\"\\s*:\\s*(\\d{2})\\b");

    /**
     * A level of any case that opens the line: after a timestamp ({@code 2026-10-06T12:00:00Z error …}), or as
     * the first word followed by a colon ({@code info: …} — .NET, and most command-line tools' {@code error:}).
     * Without the colon a first word is not enough: "Note the …" and "Fail fast …" are prose.
     */
    private static final Pattern LEVEL_LEADING = Pattern.compile("(?i)^\\s*(?:\\[?" + TIMESTAMP
            + "[.,\\d]*(?:Z|[+-]\\d{2}:?\\d{2})?\\]?\\s+(" + LEVEL_WORDS + ")(?=[:\\s\\]]|$)"
            + "|(" + LEVEL_WORDS + ")\\s*:)");

    /** syslog with a message-level prefix: {@code Oct  6 12:00:00 host sshd[123]: error: …}. */
    private static final Pattern LEVEL_SYSLOG = Pattern.compile(
            "(?i)^[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}\\s+\\S+\\s+[^:\\s]+:\\s+(" + LEVEL_WORDS + "):");

    /** klog (Kubernetes): the level is the first letter — {@code E1006 12:00:00.000000 1 file.go:1] …}. */
    private static final Pattern LEVEL_KLOG = Pattern.compile("^([IWEF])\\d{4} \\d{2}:\\d{2}:\\d{2}\\.\\d+\\s");

    /** A stack-trace line: never a record of its own, whatever words its class and file names contain. */
    private static final Pattern CONTINUATION =
            Pattern.compile("^(?:\\s+at\\s|\\s*\\.\\.\\. \\d+ |Caused by:|\\s*Suppressed:)");

    /** Apache/Nginx combined-log-format-ish request + status: {@code "GET /path HTTP/1.1" 500}. */
    private static final Pattern ACCESS_STATUS = Pattern.compile("\"[A-Z]+ [^\"]*HTTP/\\d(?:\\.\\d)?\"\\s+(\\d{3})\\b");

    /** A leading date/time stamp (ISO-8601, {@code yyyy-MM-dd HH:mm:ss}, syslog {@code Mon dd HH:mm:ss}). */
    private static final Pattern LEADING_TIMESTAMP = Pattern.compile("^\\s*(?:\\[)?" + TIMESTAMP);

    private LogPatterns() {}

    /**
     * The severity level of a single log line, or {@code null} if the line carries no level (a blank line,
     * a stack-trace frame, or a wrapped continuation). A textual level token near the line start wins;
     * failing that, an HTTP access-log status code maps 5xx→ERROR, 4xx→WARN, 2xx/3xx→INFO.
     */
    public static LogLevel levelOf(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        if (CONTINUATION.matcher(line).find()) {
            return null;
        }
        Matcher klog = LEVEL_KLOG.matcher(line);
        if (klog.find()) {
            return switch (klog.group(1).charAt(0)) {
                case 'E' -> LogLevel.ERROR;
                case 'W' -> LogLevel.WARN;
                case 'F' -> LogLevel.FATAL;
                default -> LogLevel.INFO;
            };
        }
        String prefix = line.length() > LEVEL_SCAN_PREFIX ? line.substring(0, LEVEL_SCAN_PREFIX) : line;
        Matcher upper = LEVEL_UPPER.matcher(prefix);
        if (upper.find()) {
            LogLevel level = LogLevel.fromToken(upper.group());
            if (level != null) {
                return level;
            }
        }
        Matcher bracketed = LEVEL_BRACKETED.matcher(prefix);
        if (bracketed.find()) {
            LogLevel level = LogLevel.fromToken(bracketed.group(1));
            if (level != null) {
                return level;
            }
        }
        boolean json = line.charAt(0) == '{';
        String fields = !json ? prefix : line.length() > JSON_SCAN_LIMIT ? line.substring(0, JSON_SCAN_LIMIT) : line;
        Matcher kv = LEVEL_KEYVALUE.matcher(fields);
        if (kv.find()) {
            LogLevel level = LogLevel.fromToken(kv.group(1));
            if (level != null) {
                return level;
            }
        }
        if (json) {
            Matcher numeric = LEVEL_NUMERIC.matcher(fields);
            if (numeric.find()) {
                int n = Integer.parseInt(numeric.group(1));
                return n >= 60
                        ? LogLevel.FATAL
                        : n >= 50
                                ? LogLevel.ERROR
                                : n >= 40
                                        ? LogLevel.WARN
                                        : n >= 30 ? LogLevel.INFO : n >= 20 ? LogLevel.DEBUG : LogLevel.TRACE;
            }
        }
        Matcher leading = LEVEL_LEADING.matcher(prefix);
        if (leading.find()) {
            LogLevel level = LogLevel.fromToken(leading.group(1) != null ? leading.group(1) : leading.group(2));
            if (level != null) {
                return level;
            }
        }
        Matcher syslog = LEVEL_SYSLOG.matcher(prefix);
        if (syslog.find()) {
            LogLevel level = LogLevel.fromToken(syslog.group(1));
            if (level != null) {
                return level;
            }
        }
        Matcher access = ACCESS_STATUS.matcher(line);
        if (access.find()) {
            int status = Integer.parseInt(access.group(1));
            if (status >= 500) {
                return LogLevel.ERROR;
            }
            if (status >= 400) {
                return LogLevel.WARN;
            }
            if (status >= 200) {
                return LogLevel.INFO;
            }
        }
        return null;
    }

    /** Whether a line begins with a recognizable timestamp (used by the content sniff). */
    public static boolean hasLeadingTimestamp(String line) {
        return line != null && LEADING_TIMESTAMP.matcher(line).find();
    }

    /**
     * Whether {@code sample} (typically the first few KB of a file) looks like a log: a meaningful
     * fraction of its non-blank lines carry a level token or a leading timestamp. Conservative on
     * purpose — a false positive would mis-skin an ordinary text file as a log.
     */
    public static boolean looksLikeLog(String sample) {
        if (sample == null || sample.isBlank()) {
            return false;
        }
        int considered = 0;
        int loggy = 0;
        for (String line : sample.split("\n", 200)) {
            if (line.isBlank()) {
                continue;
            }
            if (++considered > 100) {
                break;
            }
            if (levelOf(line) != null || hasLeadingTimestamp(line)) {
                loggy++;
            }
        }
        // Need at least a few sampled lines and a third of them looking like log records.
        return considered >= 3 && loggy * 3 >= considered;
    }
}
