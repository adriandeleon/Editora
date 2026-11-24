package com.editora.logviewer;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.jspecify.annotations.Nullable;

/**
 * Filtering a whole log document for the viewer: the lines of the records whose (inherited) severity is at
 * least {@code minLevel}, which match an optional pattern and do not match an optional one to hide. Pure (java.base only), so it is unit-tested and
 * can run off the FX thread; the per-line rules are {@link LogRecordFilter}'s.
 */
public final class LogFilter {

    private LogFilter() {}

    /**
     * Compiles a filter pattern, case-insensitively, treating it as a regular expression and <em>falling back to
     * a literal substring match</em> when it isn't a valid regex — so the filter works for both a regex (e.g.
     * {@code ERROR|WARN}, {@code timed?out}) and a plain string with metacharacters (e.g. {@code GET /api(v2)}).
     * Returns {@code null} for null/empty input (no filter).
     */
    public static @Nullable Pattern compileFilter(@Nullable String query) {
        return compileFilter(query, false);
    }

    /** {@link #compileFilter(String)}, matching case when {@code matchCase}. */
    public static @Nullable Pattern compileFilter(@Nullable String query, boolean matchCase) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        int flags = matchCase ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
        try {
            return Pattern.compile(query, flags);
        } catch (PatternSyntaxException e) {
            return Pattern.compile(query, flags | Pattern.LITERAL);
        }
    }

    /** Whether {@code query} is a valid regular expression (an invalid one is matched as plain text). */
    public static boolean isValidRegex(@Nullable String query) {
        if (query == null || query.isEmpty()) {
            return true;
        }
        try {
            Pattern.compile(query);
            return true;
        } catch (PatternSyntaxException e) {
            return false;
        }
    }

    /**
     * A {@link LogQuery} compiled: the pattern whose records are kept and the one whose records are hidden.
     *
     * @param include records to keep, or {@code null} for all
     * @param exclude records to hide, or {@code null} for none
     */
    public record Patterns(
            @Nullable Pattern include, @Nullable Pattern exclude) {
        /** Filters nothing. */
        public static final Patterns NONE = new Patterns(null, null);

        /** Only a pattern of records to keep. */
        public static Patterns including(@Nullable Pattern include) {
            return new Patterns(include, null);
        }

        public boolean isEmpty() {
            return include == null && exclude == null;
        }
    }

    /** Compiles both patterns of {@code query} (see {@link #compileFilter(String, boolean)}). */
    public static Patterns compile(LogQuery query) {
        if (query.isEmpty()) {
            return Patterns.NONE;
        }
        return new Patterns(
                compileFilter(query.include(), query.matchCase()), compileFilter(query.exclude(), query.matchCase()));
    }

    /**
     * The outcome of filtering {@code source}: the kept lines, each one terminated by {@code '\n'}, with the
     * source line and level of each, and the filter itself — positioned after the last complete line, ready
     * for the lines a followed log appends.
     *
     * <p>Only complete lines are judged. An unfinished last line (a file that does not end in a newline, or a
     * line still being written) is the caller's to show: {@code source.substring(consumed)}.
     *
     * @param source the text that was filtered — the identity a caller checks before installing the result
     * @param text the kept lines, each followed by {@code '\n'}
     * @param lines for each kept line, its 0-based line index in {@code source}
     * @param levels for each kept line, its inherited level as an ordinal, or {@code -1} for none
     * @param kept number of kept lines
     * @param total number of complete lines in {@code source}
     * @param consumed length of the prefix of {@code source} made of complete lines
     * @param filter the filter state after those lines
     */
    public record Run(
            String source,
            String text,
            int[] lines,
            byte[] levels,
            int kept,
            int total,
            int consumed,
            LogRecordFilter filter) {}

    /** Filters every complete line of {@code source}. Safe on any thread: it touches nothing but its arguments. */
    public static Run run(@Nullable String source, @Nullable LogLevel minLevel, Patterns patterns) {
        String text = source == null ? "" : source;
        LogRecordFilter filter = new LogRecordFilter(minLevel, patterns);
        Collector out = new Collector(Math.min(text.length(), 1 << 16));
        int pos = 0;
        int index = 0;
        for (int nl = text.indexOf('\n'); nl >= 0; nl = text.indexOf('\n', pos)) {
            filter.accept(text.substring(pos, nl), index++, out);
            pos = nl + 1;
        }
        return new Run(text, out.text.toString(), out.lines(), out.levels(), out.count, index, pos, filter);
    }

    /** Accumulates kept lines as text plus the parallel line/level arrays. */
    public static final class Collector implements LogRecordFilter.Sink {
        public final StringBuilder text;
        private int[] lines = new int[64];
        private byte[] levels = new byte[64];
        public int count;

        public Collector(int capacity) {
            text = new StringBuilder(capacity);
        }

        @Override
        public void keep(String line, int index, @Nullable LogLevel level) {
            if (count == lines.length) {
                lines = Arrays.copyOf(lines, count * 2);
                levels = Arrays.copyOf(levels, count * 2);
            }
            lines[count] = index;
            levels[count] = (byte) (level == null ? -1 : level.ordinal());
            count++;
            text.append(line).append('\n');
        }

        public int[] lines() {
            return Arrays.copyOf(lines, count);
        }

        public byte[] levels() {
            return Arrays.copyOf(levels, count);
        }

        public int lineAt(int i) {
            return lines[i];
        }

        public byte levelAt(int i) {
            return levels[i];
        }
    }
}
