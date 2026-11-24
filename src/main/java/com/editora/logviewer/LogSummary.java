package com.editora.logviewer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What a log holds, counted: records per level, records over time, the messages that repeat most, and the
 * longest silences. What Log: Summarize… reports. Pure (java.base only).
 *
 * <p>A record is a line with a level and the lines after it with none (as the filter has it); a log whose
 * lines have no level counts each timed line as a record of no level. A message's <em>template</em> is its
 * first line with the timestamp and the variable parts masked — numbers, hex ids, UUIDs, quoted values — so
 * "user 41 timed out after 3000 ms" and "user 7 timed out after 2500 ms" are counted as one.
 */
public final class LogSummary {

    private LogSummary() {}

    /** How many of the most frequent templates are kept. */
    public static final int TOP_MESSAGES = 15;
    /** How many of the longest silences are kept. */
    public static final int TOP_GAPS = 5;
    /** Most buckets the records-over-time histogram is cut into. */
    public static final int MAX_BUCKETS = 40;

    /** Bucket widths the histogram picks from, smallest first: 1 s … 1 day. */
    private static final long[] BUCKET_MILLIS = {
        1_000,
        5_000,
        10_000,
        30_000,
        60_000,
        300_000,
        600_000,
        900_000,
        1_800_000,
        3_600_000,
        10_800_000,
        21_600_000,
        43_200_000,
        86_400_000
    };

    private static final Pattern UUID =
            Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b(?:0x)?(?=[0-9a-fA-F]*\\d)[0-9a-fA-F]{8,}\\b");
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:]\\d+)*");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern MASKED_TIME = Pattern.compile("^\\[?#(?:[-/T ]#)*(?:Z|[+-]#)?]?\\s*");
    private static final int TEMPLATE_CHARS = 200;

    /**
     * A repeated message.
     *
     * @param template its masked text
     * @param count how many records had it
     * @param level the level of its first record, or null
     * @param firstLine the 0-based line of its first record
     * @param example that first record's line as written
     */
    public record Message(
            String template, int count, @Nullable LogLevel level, int firstLine, String example) {}

    /** A silence: no timed line between {@code fromLine}'s time and the next timed line's. */
    public record Gap(long from, long to, int fromLine, int toLine) {
        public long millis() {
            return to - from;
        }
    }

    /**
     * One histogram bucket, {@code [start, start + width)}.
     *
     * @param problems records at WARN or above in it
     */
    public record Bucket(long start, int records, int problems) {}

    /**
     * The counts.
     *
     * @param first the earliest time seen, or {@link LogTimes#NONE}
     * @param last the latest time seen, or {@link LogTimes#NONE}
     * @param bucketMillis the histogram's bucket width (0 when there are no times)
     */
    public record Summary(
            int lines,
            int records,
            Map<LogLevel, Integer> levels,
            int unleveled,
            long first,
            long last,
            long bucketMillis,
            List<Bucket> buckets,
            List<Message> messages,
            List<Gap> gaps) {}

    private static final class Counter {
        final String template;
        final @Nullable LogLevel level;
        final int firstLine;
        final String example;
        int count;

        Counter(String template, @Nullable LogLevel level, int firstLine, String example) {
            this.template = template;
            this.level = level;
            this.firstLine = firstLine;
            this.example = example;
        }
    }

    /** {@code line} with its timestamp dropped and its variable parts masked (see the class comment). */
    public static String template(String line) {
        String text = line.strip();
        if (text.length() > TEMPLATE_CHARS) {
            text = text.substring(0, TEMPLATE_CHARS);
        }
        text = UUID.matcher(text).replaceAll("<uuid>");
        text = QUOTED.matcher(text).replaceAll("\"…\"");
        text = HEX.matcher(text).replaceAll("<hex>");
        text = NUMBER.matcher(text).replaceAll("#");
        // The timestamp is masked to "#-#-# #", "#-#-#T#Z" and the like: drop it from the start.
        text = MASKED_TIME.matcher(text).replaceFirst("");
        return SPACES.matcher(text.strip()).replaceAll(" ");
    }

    /** Counts {@code text} (see the class comment). */
    public static Summary of(String text, LogTimes.Context context) {
        Map<LogLevel, Integer> levels = new EnumMap<>(LogLevel.class);
        Map<String, Counter> templates = new HashMap<>();
        List<long[]> timed = new ArrayList<>(); // {time, line, problem 0/1}
        List<Gap> gaps = new ArrayList<>();
        int lines = 0;
        int records = 0;
        int unleveled = 0;
        long first = LogTimes.NONE;
        long last = LogTimes.NONE;
        long previous = LogTimes.NONE;
        int previousLine = -1;
        boolean anyLevel = false;
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            int stop = end < 0 ? text.length() : end;
            String line = text.substring(start, stop > start && text.charAt(stop - 1) == '\r' ? stop - 1 : stop);
            int index = lines++;
            start = stop + 1;
            LogLevel level = LogPatterns.levelOf(line);
            long time = LogTimes.of(line, context);
            boolean recordStart = level != null || (time != LogTimes.NONE && !anyLevel);
            anyLevel |= level != null;
            if (time != LogTimes.NONE) {
                first = first == LogTimes.NONE ? time : Math.min(first, time);
                last = last == LogTimes.NONE ? time : Math.max(last, time);
                if (previous != LogTimes.NONE && time > previous) {
                    gaps.add(new Gap(previous, time, previousLine, index));
                    if (gaps.size() > TOP_GAPS * 4) {
                        keepLongest(gaps);
                    }
                }
                if (previous == LogTimes.NONE || time >= previous) {
                    previous = time;
                    previousLine = index;
                }
            }
            if (!recordStart) {
                continue;
            }
            records++;
            if (level != null) {
                levels.merge(level, 1, Integer::sum);
            } else {
                unleveled++;
            }
            if (time != LogTimes.NONE) {
                timed.add(new long[] {time, index, level != null && level.atLeast(LogLevel.WARN) ? 1 : 0});
            }
            String template = template(line);
            if (!template.isEmpty()) {
                templates.computeIfAbsent(template, t -> new Counter(t, level, index, line.strip())).count++;
            }
        }
        keepLongest(gaps);
        List<Message> messages = templates.values().stream()
                .filter(c -> c.count > 1)
                .sorted(Comparator.comparingInt((Counter c) -> -c.count).thenComparingInt(c -> c.firstLine))
                .limit(TOP_MESSAGES)
                .map(c -> new Message(c.template, c.count, c.level, c.firstLine, c.example))
                .toList();
        long width = first == LogTimes.NONE ? 0 : bucketWidth(last - first);
        return new Summary(
                lines,
                records,
                levels,
                unleveled,
                first,
                last,
                width,
                buckets(timed, first, last, width),
                messages,
                gaps);
    }

    private static void keepLongest(List<Gap> gaps) {
        gaps.sort(Comparator.comparingLong(Gap::millis).reversed().thenComparingInt(Gap::fromLine));
        if (gaps.size() > TOP_GAPS) {
            gaps.subList(TOP_GAPS, gaps.size()).clear();
        }
    }

    /** The smallest bucket width that cuts {@code span} into at most {@link #MAX_BUCKETS}. */
    static long bucketWidth(long span) {
        for (long width : BUCKET_MILLIS) {
            if (span / width < MAX_BUCKETS) {
                return width;
            }
        }
        long day = BUCKET_MILLIS[BUCKET_MILLIS.length - 1];
        return (span / MAX_BUCKETS / day + 1) * day;
    }

    private static List<Bucket> buckets(List<long[]> timed, long first, long last, long width) {
        if (width == 0 || timed.isEmpty()) {
            return List.of();
        }
        long base = first - Math.floorMod(first, width); // whole minutes, hours: readable starts
        int n = (int) ((last - base) / width) + 1;
        int[] records = new int[n];
        int[] problems = new int[n];
        for (long[] t : timed) {
            int i = (int) ((t[0] - base) / width);
            records[i]++;
            problems[i] += (int) t[2];
        }
        List<Bucket> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new Bucket(base + i * width, records[i], problems[i]));
        }
        return out;
    }
}
