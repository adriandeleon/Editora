package com.editora.logviewer;

import java.util.Arrays;
import java.util.function.BooleanSupplier;

import org.jspecify.annotations.Nullable;

/**
 * The lines of a log at {@link LogLevel#WARN} or above, in order, with their level: what the log's overview
 * stripe marks. Only a record's first line carries a level ({@link LogPatterns#levelOf}); its stack trace
 * is not marked again. Immutable.
 *
 * @param lines the 0-based line numbers, ascending
 * @param ranks each line's {@link LogLevel#rank()}
 * @param lineCount how many lines the scanned text had
 */
public record LogMarks(int[] lines, byte[] ranks, int lineCount) {

    /** No marks. */
    public static final LogMarks NONE = new LogMarks(new int[0], new byte[0], 0);

    /** Lines between two looks at {@code cancelled}. */
    private static final int CANCEL_CHECK = 4096;

    /**
     * Scans {@code text} line by line. Returns null when {@code cancelled} turned true meanwhile (newer text is
     * on its way); runs off the FX thread for a large log.
     */
    public static @Nullable LogMarks scan(String text, BooleanSupplier cancelled) {
        int[] lines = new int[64];
        byte[] ranks = new byte[64];
        int count = 0;
        int line = 0;
        int start = 0;
        int length = text.length();
        while (start <= length) {
            int end = text.indexOf('\n', start);
            if (end < 0) {
                end = length;
            }
            if ((line & (CANCEL_CHECK - 1)) == 0 && cancelled.getAsBoolean()) {
                return null;
            }
            int stop = end > start && text.charAt(end - 1) == '\r' ? end - 1 : end;
            if (stop > start) {
                LogLevel level = LogPatterns.levelOf(text.substring(start, stop));
                if (level != null && level.rank() >= LogLevel.WARN.rank()) {
                    if (count == lines.length) {
                        lines = Arrays.copyOf(lines, count * 2);
                        ranks = Arrays.copyOf(ranks, count * 2);
                    }
                    lines[count] = line;
                    ranks[count] = (byte) level.rank();
                    count++;
                }
            }
            line++;
            start = end + 1;
        }
        return new LogMarks(Arrays.copyOf(lines, count), Arrays.copyOf(ranks, count), line);
    }

    /** How many lines are marked. */
    public int size() {
        return lines.length;
    }

    /** The level of mark {@code i}. */
    public LogLevel level(int i) {
        return LogLevel.values()[ranks[i]];
    }

    /** The index of the mark nearest to {@code line} (ties go to the earlier one), or -1 when there are none. */
    public int nearest(int line) {
        if (lines.length == 0) {
            return -1;
        }
        int at = Arrays.binarySearch(lines, line);
        if (at >= 0) {
            return at;
        }
        int after = -at - 1;
        if (after == 0) {
            return 0;
        }
        if (after == lines.length) {
            return lines.length - 1;
        }
        return line - lines[after - 1] <= lines[after] - line ? after - 1 : after;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        return o instanceof LogMarks m
                && lineCount == m.lineCount
                && Arrays.equals(lines, m.lines)
                && Arrays.equals(ranks, m.ranks);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Arrays.hashCode(lines) + Arrays.hashCode(ranks)) + lineCount;
    }

    @Override
    public String toString() {
        return "LogMarks" + Arrays.toString(lines);
    }
}
