package com.editora.markwhen;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed Markwhen date at a coarse-to-fine {@link Granularity}, modeled as a <b>half-open interval</b>
 * {@code [startEpochDay, endEpochDayExclusive)} so a bare {@code 2023} spans the whole year and
 * {@code 2023-01} the whole month — range widths/positions on the timeline are then well-defined with no
 * special cases. Pure + unit-tested; {@link #parse} returns {@code null} (never throws) on anything it
 * doesn't understand, so a bad date just drops its line during parsing.
 *
 * <p>Supported (the v1 subset): ISO {@code 2023} / {@code 2023-01} / {@code 2023-01-15} (also {@code /}
 * separated), and English month-name forms the docs feature — {@code Dec 1 2025}, {@code Dec 2025},
 * {@code 1 Dec 2025} (optional {@code ,}/{@code .}/ordinal). <i>Deferred:</i> relative dates ({@code now},
 * durations), times-of-day/timezones, non-English months.
 */
public record MwDate(LocalDate start, Granularity granularity) {

    public enum Granularity {
        YEAR,
        MONTH,
        DAY
    }

    private static final Pattern ISO = Pattern.compile("^(\\d{4})(?:[-/](\\d{1,2})(?:[-/](\\d{1,2}))?)?$");
    private static final Pattern MONTH_DAY_YEAR =
            Pattern.compile("^([A-Za-z]{3,9})\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})$");
    private static final Pattern DAY_MONTH_YEAR =
            Pattern.compile("^(\\d{1,2})(?:st|nd|rd|th)?\\s+([A-Za-z]{3,9})\\.?,?\\s+(\\d{4})$");
    private static final Pattern MONTH_YEAR = Pattern.compile("^([A-Za-z]{3,9})\\.?\\s+(\\d{4})$");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(
            Map.entry("jan", 1),
            Map.entry("feb", 2),
            Map.entry("mar", 3),
            Map.entry("apr", 4),
            Map.entry("may", 5),
            Map.entry("jun", 6),
            Map.entry("jul", 7),
            Map.entry("aug", 8),
            Map.entry("sep", 9),
            Map.entry("oct", 10),
            Map.entry("nov", 11),
            Map.entry("dec", 12));

    /** Parses {@code s} into an {@link MwDate}, or {@code null} when it isn't a recognized date. */
    public static MwDate parse(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.isEmpty()) {
            return null;
        }
        Matcher iso = ISO.matcher(t);
        if (iso.matches()) {
            int y = Integer.parseInt(iso.group(1));
            if (iso.group(2) == null) {
                return at(y, 1, 1, Granularity.YEAR);
            }
            int mo = Integer.parseInt(iso.group(2));
            if (iso.group(3) == null) {
                return at(y, mo, 1, Granularity.MONTH);
            }
            return at(y, mo, Integer.parseInt(iso.group(3)), Granularity.DAY);
        }
        Matcher mdy = MONTH_DAY_YEAR.matcher(t);
        if (mdy.matches()) {
            return byName(mdy.group(1), mdy.group(2), mdy.group(3));
        }
        Matcher dmy = DAY_MONTH_YEAR.matcher(t);
        if (dmy.matches()) {
            return byName(dmy.group(2), dmy.group(1), dmy.group(3));
        }
        Matcher my = MONTH_YEAR.matcher(t);
        if (my.matches()) {
            Integer mo = monthOf(my.group(1));
            return mo == null ? null : at(Integer.parseInt(my.group(2)), mo, 1, Granularity.MONTH);
        }
        return null;
    }

    private static MwDate byName(String month, String day, String year) {
        Integer mo = monthOf(month);
        return mo == null ? null : at(Integer.parseInt(year), mo, Integer.parseInt(day), Granularity.DAY);
    }

    private static Integer monthOf(String word) {
        String key = word.toLowerCase(Locale.ROOT);
        return MONTHS.get(key.length() > 3 ? key.substring(0, 3) : key);
    }

    private static MwDate at(int year, int month, int day, Granularity g) {
        if (month < 1 || month > 12) {
            return null;
        }
        try {
            return new MwDate(LocalDate.of(year, month, day), g);
        } catch (DateTimeException e) {
            return null; // e.g. 2023-02-30, 2023-13-01
        }
    }

    public long startEpochDay() {
        return start.toEpochDay();
    }

    /** The exclusive end of this date's span (start + one unit of its granularity). */
    public LocalDate endExclusive() {
        return switch (granularity) {
            case YEAR -> start.plusYears(1);
            case MONTH -> start.plusMonths(1);
            case DAY -> start.plusDays(1);
        };
    }

    public long endEpochDayExclusive() {
        return endExclusive().toEpochDay();
    }
}
