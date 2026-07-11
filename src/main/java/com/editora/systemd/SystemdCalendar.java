package com.editora.systemd;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

/**
 * Parses a systemd {@code OnCalendar=} expression, describes it in English, and computes its upcoming
 * trigger times. Pure, java.base-only, unit-tested. Supports the shorthand keywords ({@code minutely},
 * {@code hourly}, {@code daily}, {@code weekly}, {@code monthly}, {@code yearly}/{@code annually},
 * {@code quarterly}, {@code semiannually}) and the general
 * {@code [weekdays] [year-month-day] hour:minute[:second]} form, where each field may be {@code *}, a
 * number, a {@code a,b} list, a {@code a..b} range, or a {@code start/step} / {@code &#42;/step} repetition.
 *
 * <p>{@link #parse} returns a field-level error rather than throwing, so a preview can flag a bad line.
 * Next-run computation steps by minute (bounded ~4 years), matching to minute precision (the sub-minute
 * second is shown but not scanned — the systemd-timer common case uses whole minutes).
 */
public final class SystemdCalendar {

    private static final String[] DOW_NAMES = {
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    };
    private static final String[] MONTH_NAMES = {
        "",
        "January",
        "February",
        "March",
        "April",
        "May",
        "June",
        "July",
        "August",
        "September",
        "October",
        "November",
        "December"
    };
    private static final int MAX_SCAN_MINUTES = 4 * 366 * 24 * 60;

    private final Set<DayOfWeek> weekdays; // null = every day
    private final Field month;
    private final Field day;
    private final Field hour;
    private final Field minute;
    private final int displaySecond;
    private final String shorthand; // non-null when parsed from a keyword (nicer describe)

    private SystemdCalendar(
            Set<DayOfWeek> weekdays,
            Field month,
            Field day,
            Field hour,
            Field minute,
            int displaySecond,
            String shorthand) {
        this.weekdays = weekdays;
        this.month = month;
        this.day = day;
        this.hour = hour;
        this.minute = minute;
        this.displaySecond = displaySecond;
        this.shorthand = shorthand;
    }

    public record Parsed(SystemdCalendar calendar, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    public static Parsed parse(String expr) {
        if (expr == null || expr.isBlank()) {
            return new Parsed(null, "empty calendar expression");
        }
        String s = expr.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        String shorthand =
                switch (lower) {
                    case "minutely",
                            "hourly",
                            "daily",
                            "midnight",
                            "weekly",
                            "monthly",
                            "yearly",
                            "annually",
                            "quarterly",
                            "semiannually" -> lower;
                    default -> null;
                };
        String expanded = shorthand == null ? s : expand(shorthand);
        try {
            return new Parsed(build(expanded, shorthand), null);
        } catch (IllegalArgumentException e) {
            return new Parsed(null, e.getMessage());
        }
    }

    private static String expand(String shorthand) {
        return switch (shorthand) {
            case "minutely" -> "*-*-* *:*:00";
            case "hourly" -> "*-*-* *:00:00";
            case "daily", "midnight" -> "*-*-* 00:00:00";
            case "weekly" -> "Mon *-*-* 00:00:00";
            case "monthly" -> "*-*-01 00:00:00";
            case "yearly", "annually" -> "*-01-01 00:00:00";
            case "quarterly" -> "*-01,04,07,10-01 00:00:00";
            case "semiannually" -> "*-01,07-01 00:00:00";
            default -> shorthand;
        };
    }

    private static SystemdCalendar build(String s, String shorthand) {
        String[] parts = s.split("\\s+");
        String weekdaysTok = null;
        String dateTok = null;
        String timeTok = null;
        for (String p : parts) {
            if (p.indexOf(':') >= 0) {
                timeTok = p; // a time always contains ':'
            } else if (p.indexOf('-') >= 0) {
                dateTok = p; // a date always contains '-'
            } else {
                weekdaysTok = p; // weekday names / ranges
            }
        }
        Set<DayOfWeek> weekdays = weekdaysTok == null ? null : parseWeekdays(weekdaysTok);
        Field month;
        Field day;
        if (dateTok == null) {
            month = Field.all(1, 12);
            day = Field.all(1, 31);
        } else {
            String[] d = dateTok.split("-");
            // year-month-day or month-day; the year is ignored for matching (a bounded forward scan handles it).
            if (d.length == 3) {
                month = Field.parse(d[1], 1, 12);
                day = Field.parse(d[2], 1, 31);
            } else if (d.length == 2) {
                month = Field.parse(d[0], 1, 12);
                day = Field.parse(d[1], 1, 31);
            } else {
                throw new IllegalArgumentException("bad date \"" + dateTok + "\"");
            }
        }
        Field hour;
        Field minute;
        int second = 0;
        if (timeTok == null) {
            hour = Field.of(0, 0, 23);
            minute = Field.of(0, 0, 59);
        } else {
            String[] t = timeTok.split(":");
            if (t.length < 2 || t.length > 3) {
                throw new IllegalArgumentException("bad time \"" + timeTok + "\"");
            }
            hour = Field.parse(t[0], 0, 23);
            minute = Field.parse(t[1], 0, 59);
            if (t.length == 3) {
                Field sec = Field.parse(t[2], 0, 59);
                second = sec.values().first();
            }
        }
        return new SystemdCalendar(weekdays, month, day, hour, minute, second, shorthand);
    }

    private static Set<DayOfWeek> parseWeekdays(String tok) {
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String part : tok.split(",")) {
            int dots = part.indexOf("..");
            if (dots >= 0) {
                DayOfWeek a = dow(part.substring(0, dots));
                DayOfWeek b = dow(part.substring(dots + 2));
                int i = a.getValue();
                while (true) {
                    out.add(DayOfWeek.of(i));
                    if (i == b.getValue()) {
                        break;
                    }
                    i = i % 7 + 1;
                }
            } else {
                out.add(dow(part));
            }
        }
        return out;
    }

    private static DayOfWeek dow(String name) {
        return switch (name.strip().toLowerCase(Locale.ROOT)) {
            case "mon", "monday" -> DayOfWeek.MONDAY;
            case "tue", "tuesday" -> DayOfWeek.TUESDAY;
            case "wed", "wednesday" -> DayOfWeek.WEDNESDAY;
            case "thu", "thursday" -> DayOfWeek.THURSDAY;
            case "fri", "friday" -> DayOfWeek.FRIDAY;
            case "sat", "saturday" -> DayOfWeek.SATURDAY;
            case "sun", "sunday" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("unknown weekday \"" + name + "\"");
        };
    }

    public boolean matches(LocalDateTime t) {
        return (weekdays == null || weekdays.contains(t.getDayOfWeek()))
                && month.matches(t.getMonthValue())
                && day.matches(t.getDayOfMonth())
                && hour.matches(t.getHour())
                && minute.matches(t.getMinute());
    }

    public List<LocalDateTime> nextRuns(LocalDateTime from, int n) {
        List<LocalDateTime> out = new ArrayList<>();
        LocalDateTime t = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        for (int scanned = 0; scanned < MAX_SCAN_MINUTES && out.size() < n; scanned++, t = t.plusMinutes(1)) {
            if (matches(t)) {
                out.add(t.withSecond(displaySecond));
            }
        }
        return out;
    }

    public String describe() {
        if ("minutely".equals(shorthand)) {
            return "Every minute";
        }
        if ("hourly".equals(shorthand)) {
            return "Every hour, at minute 0";
        }
        String time = timePhrase();
        String qualifiers = dayPhrase();
        if (qualifiers.isEmpty()) {
            // Fully daily → "Daily at HH:MM" reads better than "At HH:MM".
            if (time.startsWith("At ")) {
                return "Daily " + Character.toLowerCase(time.charAt(0)) + time.substring(1);
            }
            return time;
        }
        return time + ", " + qualifiers;
    }

    private String timePhrase() {
        boolean hAll = hour.coversAll();
        boolean mAll = minute.coversAll();
        if (hAll && mAll) {
            return "Every minute";
        }
        if (hAll && minute.step() != null && minute.step() > 1) {
            return "Every " + minute.step() + " minutes";
        }
        Integer h = hour.single();
        Integer m = minute.single();
        if (h != null && m != null) {
            return atClock(h, m);
        }
        if (m != null && hAll) {
            return "At minute " + m + " past every hour";
        }
        return "At " + fieldList(hour) + ":" + fieldList(minute);
    }

    private static String atClock(int h, int m) {
        if (h == 0 && m == 0) {
            return "At midnight";
        }
        if (h == 12 && m == 0) {
            return "At noon";
        }
        return String.format("At %02d:%02d", h, m);
    }

    private String dayPhrase() {
        StringJoiner sj = new StringJoiner(", ");
        if (weekdays != null && weekdays.size() < 7) {
            sj.add(weekdayPhrase());
        }
        if (!day.coversAll()) {
            Integer d = day.single();
            sj.add(d != null ? "on day " + d + " of the month" : "on days " + fieldList(day));
        }
        if (!month.coversAll()) {
            sj.add("in " + monthPhrase());
        }
        return sj.toString();
    }

    private String weekdayPhrase() {
        // Contiguous Mon..Fri etc. → "Monday through Friday".
        List<DayOfWeek> sorted = new ArrayList<>(weekdays);
        sorted.sort((a, b) -> a.getValue() - b.getValue());
        boolean contiguous = true;
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).getValue() != sorted.get(i - 1).getValue() + 1) {
                contiguous = false;
                break;
            }
        }
        if (contiguous && sorted.size() > 1) {
            return DOW_NAMES[sorted.get(0).getValue() - 1] + " through "
                    + DOW_NAMES[sorted.get(sorted.size() - 1).getValue() - 1];
        }
        StringJoiner sj = new StringJoiner(", ");
        for (DayOfWeek d : sorted) {
            sj.add(DOW_NAMES[d.getValue() - 1]);
        }
        return "on " + sj;
    }

    private String monthPhrase() {
        StringJoiner sj = new StringJoiner(", ");
        for (int v : month.values()) {
            sj.add(MONTH_NAMES[v]);
        }
        return sj.toString();
    }

    private static String fieldList(Field f) {
        int[] range = f.contiguousRange();
        if (range != null) {
            return range[0] + " through " + range[1];
        }
        StringJoiner sj = new StringJoiner(", ");
        for (int v : f.values()) {
            sj.add(f.max() >= 10 && v < 10 ? "0" + v : Integer.toString(v));
        }
        return sj.toString();
    }

    /** One calendar field: the expanded matching values plus structure ({@code step}) for describe. */
    private static final class Field {
        private final TreeSet<Integer> values;
        private final boolean star;
        private final Integer step;
        private final int min;
        private final int max;

        private Field(TreeSet<Integer> values, boolean star, Integer step, int min, int max) {
            this.values = values;
            this.star = star;
            this.step = step;
            this.min = min;
            this.max = max;
        }

        static Field all(int min, int max) {
            TreeSet<Integer> v = new TreeSet<>();
            for (int i = min; i <= max; i++) {
                v.add(i);
            }
            return new Field(v, true, null, min, max);
        }

        static Field of(int single, int min, int max) {
            TreeSet<Integer> v = new TreeSet<>();
            v.add(single);
            return new Field(v, false, null, min, max);
        }

        static Field parse(String token, int min, int max) {
            String t = token.strip();
            if (t.equals("*")) {
                return all(min, max);
            }
            boolean star = false;
            Integer fieldStep = null;
            TreeSet<Integer> out = new TreeSet<>();
            boolean isList = t.contains(",");
            for (String el : t.split(",")) {
                int slash = el.indexOf('/');
                int step = 1;
                String rangePart = el;
                if (slash >= 0) {
                    rangePart = el.substring(0, slash);
                    step = parseInt(el.substring(slash + 1), min, max, false);
                    if (step <= 0) {
                        throw new IllegalArgumentException("step must be positive in \"" + el + "\"");
                    }
                    if (!isList) {
                        fieldStep = step;
                    }
                }
                int lo;
                int hi;
                if (rangePart.equals("*")) {
                    lo = min;
                    hi = max;
                    star = !isList && slash < 0;
                } else {
                    int dots = rangePart.indexOf("..");
                    if (dots >= 0) {
                        lo = parseInt(rangePart.substring(0, dots), min, max, true);
                        hi = parseInt(rangePart.substring(dots + 2), min, max, true);
                    } else {
                        lo = parseInt(rangePart, min, max, true);
                        hi = slash >= 0 ? max : lo;
                    }
                }
                if (lo > hi) {
                    throw new IllegalArgumentException("range start after end in \"" + el + "\"");
                }
                for (int v = lo; v <= hi; v += step) {
                    out.add(v);
                }
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("no values in \"" + token + "\"");
            }
            return new Field(out, star, fieldStep, min, max);
        }

        private static int parseInt(String s, int min, int max, boolean bounded) {
            int n;
            try {
                n = Integer.parseInt(s.strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("invalid number \"" + s + "\"");
            }
            if (bounded && (n < min || n > max)) {
                throw new IllegalArgumentException(n + " is out of range (" + min + ".." + max + ")");
            }
            return n;
        }

        boolean matches(int v) {
            return values.contains(v);
        }

        boolean coversAll() {
            return star || values.size() == (max - min + 1);
        }

        Integer step() {
            return step;
        }

        Integer single() {
            return values.size() == 1 ? values.first() : null;
        }

        int[] contiguousRange() {
            if (values.size() < 2) {
                return null;
            }
            int lo = values.first();
            int hi = values.last();
            return (hi - lo + 1 == values.size()) ? new int[] {lo, hi} : null;
        }

        TreeSet<Integer> values() {
            return values;
        }

        int max() {
            return max;
        }
    }
}
