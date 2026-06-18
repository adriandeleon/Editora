package com.editora.logviewer;

import java.util.Locale;

/**
 * A normalized server-log severity level, ordered least-to-most severe by {@link #rank()}.
 *
 * <p>{@link #fromToken(String)} maps the many spellings emitted by real-world frameworks — Logback /
 * Log4j ({@code TRACE..ERROR}), {@code java.util.logging} ({@code FINEST..SEVERE}), and syslog
 * ({@code debug..emerg}) — onto these six buckets. Pure (java.base only) so it is unit-tested.
 */
public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5);

    private final int rank;

    LogLevel(int rank) {
        this.rank = rank;
    }

    /** Severity rank, TRACE = 0 … FATAL = 5; higher is more severe. */
    public int rank() {
        return rank;
    }

    /** Whether this level is at least as severe as {@code other}. */
    public boolean atLeast(LogLevel other) {
        return other == null || rank >= other.rank;
    }

    /**
     * Maps a level token (case-insensitive, any of the common framework spellings) to a {@link LogLevel},
     * or {@code null} when the token is not a recognized level.
     */
    public static LogLevel fromToken(String token) {
        if (token == null) {
            return null;
        }
        return switch (token.toUpperCase(Locale.ROOT)) {
            case "TRACE", "TRC", "FINEST", "FINER", "VERBOSE", "VERB" -> TRACE;
            case "DEBUG", "DBG", "FINE", "CONFIG" -> DEBUG;
            case "INFO", "INF", "INFORMATION", "NOTICE", "NOTE" -> INFO;
            case "WARN", "WRN", "WARNING", "WARNINGS" -> WARN;
            case "ERROR", "ERR", "SEVERE", "FAILURE", "FAIL" -> ERROR;
            case "FATAL", "FTL", "CRIT", "CRITICAL", "ALERT", "EMERG", "EMERGENCY", "PANIC", "PNC" -> FATAL;
            default -> null;
        };
    }
}
