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

    private static final String LEVEL_WORDS = "TRACE|TRC|FINEST|FINER|VERBOSE|DEBUG|DBG|FINE|CONFIG"
            + "|INFO(?:RMATION)?|INF|NOTICE|WARN(?:ING)?|WRN|ERROR|ERR|SEVERE|FAIL(?:URE)?"
            + "|FATAL|FTL|CRIT(?:ICAL)?|ALERT|EMERG(?:ENCY)?|PANIC|PNC";

    /**
     * An UPPERCASE level keyword as a standalone token (case-sensitive on purpose). Real logs emit the
     * level in upper case — matching case-insensitively would colour the lowercase word "error" inside an
     * ordinary message/prose line. Lowercase levels are still recognized when bracketed or key=value (below).
     */
    private static final Pattern LEVEL_UPPER = Pattern.compile("(?<![A-Za-z])(?:" + LEVEL_WORDS + ")(?![A-Za-z])");

    /** A bracketed level, any case — nginx ({@code [error]}), many C/Go loggers ({@code [warn]}). */
    private static final Pattern LEVEL_BRACKETED = Pattern.compile("(?i)\\[\\s*(" + LEVEL_WORDS + ")\\s*\\]");

    /** A {@code level=error} / {@code "level":"warn"} / {@code severity: info} field (structured logs). */
    private static final Pattern LEVEL_KEYVALUE =
            Pattern.compile("(?i)\"?(?:level|lvl|severity|levelname)\"?\\s*[=:]\\s*\"?(" + LEVEL_WORDS + ")");

    /** Apache/Nginx combined-log-format-ish request + status: {@code "GET /path HTTP/1.1" 500}. */
    private static final Pattern ACCESS_STATUS = Pattern.compile("\"[A-Z]+ [^\"]*HTTP/\\d(?:\\.\\d)?\"\\s+(\\d{3})\\b");

    /** A leading date/time stamp (ISO-8601, {@code yyyy-MM-dd HH:mm:ss}, syslog {@code Mon dd HH:mm:ss}). */
    private static final Pattern LEADING_TIMESTAMP =
            Pattern.compile("^\\s*(?:\\[)?(?:\\d{4}[-/]\\d{2}[-/]\\d{2}[ T]\\d{2}:\\d{2}:\\d{2}"
                    + "|\\d{2}:\\d{2}:\\d{2}"
                    + "|[A-Z][a-z]{2}\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2})");

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
        Matcher kv = LEVEL_KEYVALUE.matcher(prefix);
        if (kv.find()) {
            LogLevel level = LogLevel.fromToken(kv.group(1));
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
