package com.editora.logviewer;

import java.util.Locale;

/**
 * A normalized server-log severity level, ordered least-to-most severe by {@link #rank()}.
 *
 * <p>{@link #fromToken(String)} maps the many spellings emitted by real-world frameworks — Logback /
 * Log4j ({@code TRACE..ERROR}), {@code java.util.logging} ({@code FINEST..SEVERE}), syslog
 * ({@code debug..emerg}), and the .NET console logger ({@code trce..crit}) — onto these six buckets. Pure (java.base only) so it is unit-tested.
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
     * Every spelling {@link #fromToken} understands, upper case. This is the one vocabulary: the line
     * patterns in {@link LogPatterns} are built from it, so a word cannot be mapped here and unmatchable there.
     */
    private static final java.util.Map<String, LogLevel> TOKENS = tokens();

    private static java.util.Map<String, LogLevel> tokens() {
        java.util.Map<String, LogLevel> map = new java.util.LinkedHashMap<>();
        put(map, TRACE, "TRACE", "TRC", "TRCE", "FINEST", "FINER", "VERBOSE");
        put(map, DEBUG, "DEBUG", "DBG", "DBUG", "FINE", "CONFIG");
        put(map, INFO, "INFO", "INF", "INFORMATION", "NOTICE");
        put(map, WARN, "WARN", "WRN", "WARNING");
        put(map, ERROR, "ERROR", "ERR", "SEVERE", "FAILURE", "FAIL");
        put(map, FATAL, "FATAL", "FTL", "CRIT", "CRITICAL", "ALERT", "EMERG", "EMERGENCY", "PANIC", "PNC");
        return java.util.Collections.unmodifiableMap(map);
    }

    private static void put(java.util.Map<String, LogLevel> map, LogLevel level, String... words) {
        for (String word : words) {
            map.put(word, level);
        }
    }

    /**
     * Maps a level token (case-insensitive, any of the common framework spellings) to a {@link LogLevel},
     * or {@code null} when the token is not a recognized level.
     */
    public static LogLevel fromToken(String token) {
        return token == null ? null : TOKENS.get(token.toUpperCase(Locale.ROOT));
    }

    /** The known level words as a regex alternation, longest first so {@code INFORMATION} wins over {@code INFO}. */
    static String wordAlternation() {
        return TOKENS.keySet().stream()
                .sorted(java.util.Comparator.comparingInt(String::length)
                        .reversed()
                        .thenComparing(w -> w))
                .collect(java.util.stream.Collectors.joining("|"));
    }

    /** Every known level word, upper case (for tests that keep the grammar in step). */
    public static java.util.Set<String> words() {
        return TOKENS.keySet();
    }
}
