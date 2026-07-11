package com.editora.systemd;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses and describes systemd time spans (used by monotonic timers + {@code RestartSec=} etc.): a sequence
 * of {@code <number><unit>} tokens such as {@code 15min}, {@code 1h 30min}, {@code 2d}, or a bare number of
 * seconds. Pure, java.base-only, unit-tested. {@link #describe(String)} renders it in plain English
 * ("1 hour 30 minutes"); an unrecognized span is echoed verbatim.
 */
public final class TimeSpan {

    private TimeSpan() {}

    /** Unit token → (seconds, singular English name). Order = coarsest-first for describe(). */
    private static final Map<String, long[]> UNIT_SECONDS = new LinkedHashMap<>();

    private static final Map<String, String> UNIT_NAME = new LinkedHashMap<>();

    static {
        put(new String[] {"years", "year", "y"}, 31_557_600L, "year");
        put(new String[] {"months", "month", "M"}, 2_629_800L, "month");
        put(new String[] {"weeks", "week", "w"}, 604_800L, "week");
        put(new String[] {"days", "day", "d"}, 86_400L, "day");
        put(new String[] {"hours", "hour", "hr", "h"}, 3_600L, "hour");
        put(new String[] {"minutes", "minute", "min", "m"}, 60L, "minute");
        put(new String[] {"seconds", "second", "sec", "s"}, 1L, "second");
    }

    private static void put(String[] tokens, long seconds, String name) {
        for (String t : tokens) {
            UNIT_SECONDS.put(t, new long[] {seconds});
            UNIT_NAME.put(t, name);
        }
    }

    private static final Pattern TOKEN = Pattern.compile("(\\d+)\\s*([a-zA-Z]*)");

    /** Total seconds for a span, or {@code -1} if it can't be parsed. A bare number is seconds. */
    public static long seconds(String span) {
        if (span == null || span.isBlank()) {
            return -1;
        }
        String s = span.strip();
        if (s.equals("infinity")) {
            return Long.MAX_VALUE;
        }
        Matcher m = TOKEN.matcher(s);
        long total = 0;
        int matchedTo = 0;
        boolean any = false;
        while (m.find()) {
            if (m.start() != matchedTo && !s.substring(matchedTo, m.start()).isBlank()) {
                return -1; // junk between tokens
            }
            long n = Long.parseLong(m.group(1));
            String unit = m.group(2);
            long per = unit.isEmpty()
                    ? 1L
                    : (UNIT_SECONDS.containsKey(unit) ? UNIT_SECONDS.get(unit)[0] : -1L);
            if (per < 0) {
                return -1;
            }
            total += n * per;
            matchedTo = m.end();
            any = true;
        }
        return (any && s.substring(matchedTo).isBlank()) ? total : -1;
    }

    /** Plain-English rendering of a span ("1 hour 30 minutes"), or the raw text if it can't be parsed. */
    public static String describe(String span) {
        long secs = seconds(span);
        if (secs < 0) {
            return span == null ? "" : span.strip();
        }
        if (secs == 0) {
            return "immediately";
        }
        if (secs == Long.MAX_VALUE) {
            return "never";
        }
        StringBuilder sb = new StringBuilder();
        long remaining = secs;
        for (Map.Entry<String, long[]> e : distinctUnitsCoarseFirst()) {
            long per = e.getValue()[0];
            long count = remaining / per;
            if (count > 0) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(count).append(' ').append(plural(UNIT_NAME.get(e.getKey()), count));
                remaining -= count * per;
            }
        }
        return sb.toString();
    }

    private static Iterable<Map.Entry<String, long[]>> distinctUnitsCoarseFirst() {
        // The canonical singular token per unit size, coarsest first (year..second).
        Map<Long, Map.Entry<String, long[]>> bySize = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : UNIT_SECONDS.entrySet()) {
            bySize.putIfAbsent(e.getValue()[0], e);
        }
        return bySize.values();
    }

    private static String plural(String unit, long n) {
        return n == 1 ? unit : unit + "s";
    }
}
