package com.editora.logviewer;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Filtering a whole log document for the viewer: the lines of the records whose (inherited) severity is at
 * least {@code minLevel} and which match an optional pattern. Pure (java.base only), so it is unit-tested and
 * can run off the FX thread; the per-line rules are {@link LogRecordFilter}'s.
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

    /** Whether {@code query} is a valid regular expression (an invalid one is matched as plain text). */
    public static boolean isValidRegex(String query) {
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
    public static Run run(String source, LogLevel minLevel, Pattern pattern) {
        String text = source == null ? "" : source;
        LogRecordFilter filter = new LogRecordFilter(minLevel, pattern);
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
        public void keep(String line, int index, LogLevel level) {
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
