package com.editora.logviewer;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The time a log line was written, read from its timestamp, as epoch milliseconds. Pure (java.base only).
 *
 * <p>Understood: an ISO-like date and time at the start of the line ({@code 2026-11-24T10:00:00.123Z},
 * Logback's {@code 2026-11-24 10:00:00,123}, {@code 2026/11/24 10:00:00}, also in brackets); Tomcat's
 * {@code 24-Nov-2026 10:00:00.123}; syslog's {@code Nov 24 10:00:00}; klog's {@code I1124 10:00:00.000000};
 * an access log's {@code [24/Nov/2026:10:00:00 +0000]}; and a JSON line's {@code time}, {@code timestamp},
 * {@code ts} or {@code @timestamp}, as text or as epoch seconds, milliseconds, microseconds or nanoseconds.
 * A time without a zone is in {@link Context#zone}; a date without a year (syslog, klog) is in
 * {@link Context#year}.
 */
public final class LogTimes {

    private LogTimes() {}

    /** What a line returns when it carries no time. */
    public static final long NONE = Long.MIN_VALUE;

    /** How far into a line a timestamp is looked for (an access log's comes after the client address). */
    private static final int SCAN_PREFIX = 200;
    /** How far into a JSON line its time key is looked for. */
    private static final int JSON_PREFIX = 2000;

    /**
     * What a timestamp that leaves something out is completed with.
     *
     * @param zone the zone of a time written without one
     * @param year the year of a date written without one
     */
    public record Context(ZoneId zone, int year) {
        /** This machine's zone and the current year. */
        public static Context local() {
            ZoneId zone = ZoneId.systemDefault();
            return new Context(zone, LocalDate.now(zone).getYear());
        }
    }

    private static final String ZONE = "(Z|[+-]\\d{2}:?\\d{2})?";
    private static final Pattern ISO = Pattern.compile(
            "^\\s*\\[?(\\d{4})[-/](\\d{2})[-/](\\d{2})[ T](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?\\s?"
                    + ZONE);
    private static final Pattern TOMCAT =
            Pattern.compile("^\\s*\\[?(\\d{2})-([A-Za-z]{3})-(\\d{4}) (\\d{2}):(\\d{2}):(\\d{2})(?:[.,](\\d{1,9}))?");
    private static final Pattern SYSLOG =
            Pattern.compile("^\\s*([A-Z][a-z]{2}) {1,2}(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?");
    private static final Pattern KLOG =
            Pattern.compile("^[IWEF](\\d{2})(\\d{2}) (\\d{2}):(\\d{2}):(\\d{2})\\.(\\d{1,9})");
    private static final Pattern ACCESS =
            Pattern.compile("\\[(\\d{2})/([A-Za-z]{3})/(\\d{4}):(\\d{2}):(\\d{2}):(\\d{2}) ([+-]\\d{4})]");
    private static final Pattern JSON_TIME = Pattern.compile(
            "\"(?:time|timestamp|ts|@timestamp|datetime)\"\\s*:\\s*(?:\"([^\"]{8,40})\"|(\\d{9,19}(?:\\.\\d+)?))");

    private static final String[] MONTHS = {
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"
    };

    /** The time {@code line} was written, in epoch milliseconds, or {@link #NONE}. */
    public static long of(String line, Context context) {
        if (line.isEmpty()) {
            return NONE;
        }
        char first = line.charAt(0);
        if (first == '{') {
            return json(line, context);
        }
        String head = line.length() > SCAN_PREFIX ? line.substring(0, SCAN_PREFIX) : line;
        Matcher m = ISO.matcher(head);
        if (m.find()) {
            return at(
                    num(m, 1), num(m, 2), num(m, 3), num(m, 4), num(m, 5), num(m, 6), m.group(7), m.group(8), context);
        }
        if (first >= 'A' && first <= 'Z' || first == ' ') {
            m = SYSLOG.matcher(head);
            if (m.find()) {
                int month = month(m.group(1));
                return month < 0
                        ? NONE
                        : at(
                                context.year(),
                                month,
                                num(m, 2),
                                num(m, 3),
                                num(m, 4),
                                num(m, 5),
                                m.group(6),
                                null,
                                context);
            }
            m = KLOG.matcher(head);
            if (m.find()) {
                return at(
                        context.year(),
                        num(m, 1),
                        num(m, 2),
                        num(m, 3),
                        num(m, 4),
                        num(m, 5),
                        m.group(6),
                        null,
                        context);
            }
        }
        m = TOMCAT.matcher(head);
        if (m.find()) {
            int month = month(m.group(2));
            return month < 0
                    ? NONE
                    : at(num(m, 3), month, num(m, 1), num(m, 4), num(m, 5), num(m, 6), m.group(7), null, context);
        }
        m = ACCESS.matcher(head);
        if (m.find()) {
            int month = month(m.group(2));
            return month < 0
                    ? NONE
                    : at(num(m, 3), month, num(m, 1), num(m, 4), num(m, 5), num(m, 6), null, m.group(7), context);
        }
        return NONE;
    }

    private static long json(String line, Context context) {
        String fields = line.length() > JSON_PREFIX ? line.substring(0, JSON_PREFIX) : line;
        Matcher m = JSON_TIME.matcher(fields);
        if (!m.find()) {
            return NONE;
        }
        if (m.group(1) != null) {
            Matcher iso = ISO.matcher(m.group(1));
            return iso.find()
                    ? at(
                            num(iso, 1),
                            num(iso, 2),
                            num(iso, 3),
                            num(iso, 4),
                            num(iso, 5),
                            num(iso, 6),
                            iso.group(7),
                            iso.group(8),
                            context)
                    : NONE;
        }
        String number = m.group(2);
        int dot = number.indexOf('.');
        String whole = dot < 0 ? number : number.substring(0, dot);
        try {
            long value = Long.parseLong(whole);
            return switch (whole.length()) {
                case 9, 10 -> value * 1000 + fractionMillis(dot < 0 ? null : number.substring(dot + 1));
                case 12, 13 -> value;
                case 15, 16 -> value / 1000;
                case 18, 19 -> value / 1_000_000;
                default -> NONE;
            };
        } catch (NumberFormatException e) {
            return NONE;
        }
    }

    private static int num(Matcher m, int group) {
        String s = m.group(group);
        return s == null ? 0 : Integer.parseInt(s);
    }

    /** 1–12 for a month's English abbreviation, or -1. */
    static int month(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < MONTHS.length; i++) {
            if (MONTHS[i].equals(lower)) {
                return i + 1;
            }
        }
        return -1;
    }

    private static long fractionMillis(@Nullable String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            return 0;
        }
        String three = (fraction + "00").substring(0, 3);
        return Integer.parseInt(three);
    }

    private static long at(
            int year,
            int month,
            int day,
            int hour,
            int minute,
            int second,
            @Nullable String fraction,
            @Nullable String zone,
            Context context) {
        try {
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second);
            long seconds =
                    zone == null ? local.atZone(context.zone()).toEpochSecond() : local.toEpochSecond(offset(zone));
            return seconds * 1000 + fractionMillis(fraction);
        } catch (DateTimeException e) {
            return NONE; // 2026-13-45: digits, not a date
        }
    }

    private static ZoneOffset offset(String zone) {
        if (zone.equals("Z")) {
            return ZoneOffset.UTC;
        }
        String digits = zone.replace(":", "");
        int hours = Integer.parseInt(digits.substring(1, 3));
        int minutes = Integer.parseInt(digits.substring(3, 5));
        int sign = digits.charAt(0) == '-' ? -1 : 1;
        return ZoneOffset.ofHoursMinutes(sign * hours, sign * minutes);
    }

    private static final Pattern TIME_ONLY =
            Pattern.compile("^\\s*(\\d{1,2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?\\s*$");

    /**
     * A time the user typed: a full timestamp in any of the line formats, or a time of day alone
     * ({@code 10:05}, {@code 10:05:30.250}), which is on {@code day}'s date. {@link #NONE} when it is neither.
     */
    public static long parseTyped(String text, @Nullable LocalDate day, Context context) {
        String trimmed = text.strip();
        Matcher t = TIME_ONLY.matcher(trimmed);
        if (t.find()) {
            if (day == null) {
                return NONE;
            }
            try {
                LocalTime time = LocalTime.of(num(t, 1), num(t, 2), num(t, 3));
                return LocalDateTime.of(day, time).atZone(context.zone()).toEpochSecond() * 1000
                        + fractionMillis(t.group(4));
            } catch (DateTimeException e) {
                return NONE;
            }
        }
        String date = trimmed.matches("\\d{4}[-/]\\d{2}[-/]\\d{2}") ? trimmed + " 00:00:00" : trimmed;
        return of(date, context);
    }

    /** The local date of {@code millis} in the context's zone. */
    public static LocalDate dateOf(long millis, Context context) {
        return java.time.Instant.ofEpochMilli(millis).atZone(context.zone()).toLocalDate();
    }

    /** {@code millis} as the user reads it: {@code 2026-11-24 10:05:30} in the context's zone (milliseconds if any). */
    public static String format(long millis, Context context) {
        LocalDateTime t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), context.zone());
        String base = String.format(
                Locale.ROOT,
                "%04d-%02d-%02d %02d:%02d:%02d",
                t.getYear(),
                t.getMonthValue(),
                t.getDayOfMonth(),
                t.getHour(),
                t.getMinute(),
                t.getSecond());
        int ms = t.getNano() / 1_000_000;
        return ms == 0 ? base : base + String.format(Locale.ROOT, ".%03d", ms);
    }

    /** Lines looked through, up or down, for a time near a line that has none of its own. */
    static final int NEAR_SCAN = 5000;

    /**
     * The time line {@code index} was written: its own timestamp, or that of the nearest line above it with one
     * (a stack trace was written with its record). {@link #NONE} when none is found within {@link #NEAR_SCAN}.
     */
    public static long timeAt(java.util.function.IntFunction<String> lines, int index, Context context) {
        for (int i = index; i >= 0 && i > index - NEAR_SCAN; i--) {
            long t = of(lines.apply(i), context);
            if (t != NONE) {
                return t;
            }
        }
        return NONE;
    }

    /** The first time found from line {@code index} downwards, or {@link #NONE}. */
    public static long firstTimeFrom(
            java.util.function.IntFunction<String> lines, int count, int index, Context context) {
        for (int i = Math.max(0, index); i < count && i < index + NEAR_SCAN; i++) {
            long t = of(lines.apply(i), context);
            if (t != NONE) {
                return t;
            }
        }
        return NONE;
    }

    /**
     * The first line written at or after {@code target}, or -1. Logs are nearly in order, not strictly (threads
     * write a little out of turn), so this reads from the top rather than bisecting.
     */
    public static int firstAtOrAfter(
            java.util.function.IntFunction<String> lines, int count, long target, Context context) {
        for (int i = 0; i < count; i++) {
            long t = of(lines.apply(i), context);
            if (t != NONE && t >= target) {
                return i;
            }
        }
        return -1;
    }

    /** A typed range's two ends; either may be null (open). */
    public record Typed(@Nullable Long from, @Nullable Long to) {}

    private static final Pattern RANGE_SEPARATOR = Pattern.compile("\\s*(?:\\.\\.|\u2013|\u2014)\\s*|\\s+(?:-|to)\\s+");

    /**
     * A range the user typed: {@code 10:05 - 10:20}, {@code 2026-11-24 10:05 .. 2026-11-25 08:00}, {@code 10:05 ..}
     * (from then on), {@code .. 10:20} (up to then); the ends are anything {@link #parseTyped} reads. A single
     * time is where the range starts. Null when an end is not a time, or the range ends before it starts.
     */
    public static @Nullable Typed parseRange(String text, @Nullable LocalDate day, Context context) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return new Typed(null, null);
        }
        Matcher m = RANGE_SEPARATOR.matcher(trimmed);
        String first = trimmed;
        String second = null;
        if (m.find()) {
            first = trimmed.substring(0, m.start()).strip();
            second = trimmed.substring(m.end()).strip();
        }
        Long from = first.isEmpty() ? null : typed(first, day, context, false);
        Long to = second == null || second.isEmpty() ? null : typed(second, day, context, true);
        if ((!first.isEmpty() && from == null) || (second != null && !second.isEmpty() && to == null)) {
            return null;
        }
        if (from != null && to != null && to < from) {
            return null;
        }
        return new Typed(from, to);
    }

    private static final Pattern DATE_ONLY = Pattern.compile("^\\d{4}[-/]\\d{2}[-/]\\d{2}$");
    private static final Pattern TO_MINUTE = Pattern.compile("(?:^|[ T])\\d{1,2}:\\d{2}$");
    private static final Pattern TO_SECOND = Pattern.compile(":\\d{2}:\\d{2}$");

    /**
     * A typed end. The end of a range covers all of what it names: {@code 10:20} runs to 10:20:59.999 and a date
     * to the end of that day, so "10:05 - 10:20" keeps a line written at 10:20:30.
     */
    private static @Nullable Long typed(String text, @Nullable LocalDate day, Context context, boolean end) {
        long t = parseTyped(text, day, context);
        if (t == NONE) {
            return null;
        }
        if (end) {
            if (DATE_ONLY.matcher(text).find()) {
                return t + 86_399_999L;
            }
            if (TO_MINUTE.matcher(text).find()) {
                return t + 59_999L;
            }
            if (TO_SECOND.matcher(text).find()) {
                return t + 999L;
            }
        }
        return t;
    }
}
