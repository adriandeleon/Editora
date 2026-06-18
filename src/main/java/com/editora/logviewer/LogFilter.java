package com.editora.logviewer;

import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure line-level filtering for the log viewer: keep only the lines whose (inherited) severity is at
 * least {@code minLevel} and which match an optional {@code regex}. java.base only, so it is unit-tested.
 *
 * <p>A line with no level of its own (a stack-trace frame, a wrapped message) <em>inherits</em> the
 * preceding line's level — so filtering to {@code ERROR+} keeps an exception's full stack trace, not
 * just its first line. The regex, when present, is applied to each line's literal text and combined
 * with the level test by AND.
 */
public final class LogFilter {

    private LogFilter() {}

    /**
     * Compiles a filter query as a case-insensitive {@link Pattern}, treating it as a regular expression and
     * <em>falling back to a literal substring match</em> when it isn't a valid regex — so the filter works
     * for both a regex (e.g. {@code ERROR|WARN}, {@code timed?out}) and a plain string with metacharacters
     * (e.g. {@code GET /api(v2)}). Returns {@code null} for null/empty input (no filter).
     */
    public static Pattern compileFilter(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(query, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        }
    }

    /** The effective level of {@code line}: its own level, or {@code carry} (the previous line's) when it has none. */
    public static LogLevel effectiveLevel(String line, LogLevel carry) {
        LogLevel own = LogPatterns.levelOf(line);
        return own != null ? own : carry;
    }

    /** Whether a line with effective level {@code effective} passes the {@code minLevel} + {@code regex} filter. */
    public static boolean keep(String line, LogLevel effective, LogLevel minLevel, Pattern regex) {
        if (minLevel != null) {
            // Unknown-level lines (no level seen yet at the top of a file) are kept only when no level
            // floor is set; under a floor they are hidden until a record establishes a level to inherit.
            if (effective == null || !effective.atLeast(minLevel)) {
                return false;
            }
        }
        return regex == null || regex.matcher(line).find();
    }

    /**
     * Filters {@code text} (a whole document or an appended chunk), returning only the kept lines joined
     * by {@code '\n'}. {@code startCarry} is the inherited level entering the first line (null for a fresh
     * document; the previous chunk's {@link #endCarry} when filtering an appended tail).
     */
    public static String filter(String text, LogLevel minLevel, Pattern regex, LogLevel startCarry) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        LogLevel carry = startCarry;
        int pos = 0;
        int len = text.length();
        boolean first = true;
        while (pos <= len) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? len : nl;
            String line = text.substring(pos, end);
            carry = effectiveLevel(line, carry);
            if (keep(line, carry, minLevel, regex)) {
                if (!first) {
                    out.append('\n');
                }
                out.append(line);
                first = false;
            }
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        return out.toString();
    }

    /** The inherited level after scanning all of {@code text} starting from {@code startCarry} (for incremental appends). */
    public static LogLevel endCarry(String text, LogLevel startCarry) {
        if (text == null || text.isEmpty()) {
            return startCarry;
        }
        LogLevel carry = startCarry;
        int pos = 0;
        int len = text.length();
        while (pos <= len) {
            int nl = text.indexOf('\n', pos);
            int end = nl < 0 ? len : nl;
            carry = effectiveLevel(text.substring(pos, end), carry);
            if (nl < 0) {
                break;
            }
            pos = nl + 1;
        }
        return carry;
    }
}
