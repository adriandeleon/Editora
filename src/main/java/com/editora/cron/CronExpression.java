package com.editora.cron;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * A parsed standard 5-field cron expression (minute hour day-of-month month day-of-week), plus the
 * {@code @macro} shorthands ({@code @reboot @yearly @annually @monthly @weekly @daily @midnight @hourly}).
 * Pure, java.base-only, unit-tested. Produces an English {@link #describe()} and the upcoming fire times
 * ({@link #nextRuns}); {@link #parse} returns a field-level error message rather than throwing, so a
 * preview can red-flag exactly which line (and field) is wrong.
 *
 * <p><b>Day-of-month / day-of-week OR-semantics:</b> when <em>both</em> the DOM and DOW fields are
 * restricted (neither is the literal {@code *}), a time matches if <em>either</em> field matches — the
 * classic Vixie-cron rule. When only one is restricted, it alone gates the day.
 */
public final class CronExpression {

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
    private static final Map<String, Integer> DOWS = Map.ofEntries(
            Map.entry("sun", 0),
            Map.entry("mon", 1),
            Map.entry("tue", 2),
            Map.entry("wed", 3),
            Map.entry("thu", 4),
            Map.entry("fri", 5),
            Map.entry("sat", 6));
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
    private static final String[] DOW_NAMES = {
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"
    };

    /** Guards {@link #nextRuns} against an impossible expression (e.g. Feb 31): scan at most ~4 years. */
    private static final int MAX_SCAN_MINUTES = 4 * 366 * 24 * 60;

    private final boolean reboot;
    private final CronField minute;
    private final CronField hour;
    private final CronField dom;
    private final CronField month;
    private final CronField dow;

    private CronExpression(
            boolean reboot, CronField minute, CronField hour, CronField dom, CronField month, CronField dow) {
        this.reboot = reboot;
        this.minute = minute;
        this.hour = hour;
        this.dom = dom;
        this.month = month;
        this.dow = dow;
    }

    /** Result of {@link #parse}: exactly one of {@link #expr} / {@link #error} is non-null. */
    public record Parsed(CronExpression expr, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Parses a schedule (5 fields or an {@code @macro}); returns a {@link Parsed} carrying the error text on failure. */
    public static Parsed parse(String schedule) {
        if (schedule == null) {
            return new Parsed(null, "empty schedule");
        }
        String s = schedule.trim();
        if (s.isEmpty()) {
            return new Parsed(null, "empty schedule");
        }
        if (s.startsWith("@")) {
            String macro = s.toLowerCase(Locale.ROOT);
            if (macro.equals("@reboot")) {
                return new Parsed(new CronExpression(true, null, null, null, null, null), null);
            }
            String expanded =
                    switch (macro) {
                        case "@yearly", "@annually" -> "0 0 1 1 *";
                        case "@monthly" -> "0 0 1 * *";
                        case "@weekly" -> "0 0 * * 0";
                        case "@daily", "@midnight" -> "0 0 * * *";
                        case "@hourly" -> "0 * * * *";
                        default -> null;
                    };
            if (expanded == null) {
                return new Parsed(null, "unknown macro \"" + s + "\"");
            }
            s = expanded;
        }
        String[] f = s.split("\\s+");
        if (f.length != 5) {
            return new Parsed(null, "expected 5 fields, found " + f.length);
        }
        try {
            CronField min = CronField.parse(f[0], 0, 59, null, false);
            CronField hr = CronField.parse(f[1], 0, 23, null, false);
            CronField dm = CronField.parse(f[2], 1, 31, null, false);
            CronField mo = CronField.parse(f[3], 1, 12, MONTHS, false);
            CronField dw = CronField.parse(f[4], 0, 6, DOWS, true);
            return new Parsed(new CronExpression(false, min, hr, dm, mo, dw), null);
        } catch (CronField.ParseException e) {
            return new Parsed(null, e.getMessage());
        }
    }

    public boolean isReboot() {
        return reboot;
    }

    /** Whether a wall-clock time (to the minute) is a fire time. Never true for {@code @reboot}. */
    public boolean matches(LocalDateTime t) {
        if (reboot) {
            return false;
        }
        if (!minute.matches(t.getMinute()) || !hour.matches(t.getHour()) || !month.matches(t.getMonthValue())) {
            return false;
        }
        boolean domOk = dom.matches(t.getDayOfMonth());
        boolean dowOk = dow.matches(dowIndex(t.getDayOfWeek()));
        if (!dom.isStar() && !dow.isStar()) {
            return domOk || dowOk; // Vixie OR-rule when both restricted
        }
        return domOk && dowOk;
    }

    /** The next {@code n} fire times strictly after {@code from} (empty for {@code @reboot} or an impossible spec). */
    public List<LocalDateTime> nextRuns(LocalDateTime from, int n) {
        List<LocalDateTime> out = new ArrayList<>();
        if (reboot || n <= 0) {
            return out;
        }
        LocalDateTime t = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        for (int scanned = 0; scanned < MAX_SCAN_MINUTES && out.size() < n; scanned++, t = t.plusMinutes(1)) {
            if (matches(t)) {
                out.add(t);
            }
        }
        return out;
    }

    private static int dowIndex(DayOfWeek d) {
        return d.getValue() % 7; // Mon=1..Sun=7 → Sun=0..Sat=6
    }

    // ---- English description -------------------------------------------------------------------

    /** A human-readable summary of the schedule (e.g. "At 02:30, Monday through Friday"). */
    public String describe() {
        if (reboot) {
            return "At system startup";
        }
        StringBuilder sb = new StringBuilder(timePhrase());
        String day = dayPhrase();
        if (!day.isEmpty()) {
            sb.append(", ").append(day);
        }
        if (!month.coversAll()) {
            sb.append(", in ").append(namesOf(month, MONTH_NAMES, 1));
        }
        return sb.toString();
    }

    private String timePhrase() {
        boolean mStar = minute.coversAll();
        boolean hStar = hour.coversAll();
        if (mStar && hStar) {
            return "Every minute";
        }
        if (minute.step() != null && minute.step() > 1 && hStar) {
            return "Every " + minute.step() + " minutes";
        }
        if (hour.step() != null && hour.step() > 1 && minute.single() != null) {
            return "At minute " + minute.single() + " past every " + ordinal(hour.step()) + " hour";
        }
        Integer m = minute.single();
        Integer h = hour.single();
        if (m != null && h != null) {
            return atClock(h, m);
        }
        if (m != null && hStar) {
            return "At minute " + m + " past every hour";
        }
        // Generic fallback for lists/ranges.
        String mp = mStar ? "every minute" : "minute " + fieldList(minute);
        if (hStar) {
            return capitalize(mp);
        }
        return capitalize(mp) + " past hour " + fieldList(hour);
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
        boolean domRestricted = !dom.coversAll();
        boolean dowRestricted = !dow.coversAll();
        String domPart = domRestricted ? domPhrase() : "";
        String dowPart = dowRestricted ? dowPhrase() : "";
        if (!domPart.isEmpty() && !dowPart.isEmpty()) {
            return domPart + " and " + dowPart;
        }
        return domPart.isEmpty() ? dowPart : domPart;
    }

    private String domPhrase() {
        Integer d = dom.single();
        if (d != null) {
            return "on day " + d + " of the month";
        }
        return "on days " + fieldList(dom) + " of the month";
    }

    private String dowPhrase() {
        int[] range = dow.contiguousRange();
        if (range != null) {
            return DOW_NAMES[range[0]] + " through " + DOW_NAMES[range[1]];
        }
        Integer d = dow.single();
        if (d != null) {
            return "on " + DOW_NAMES[d];
        }
        StringJoiner sj = new StringJoiner(", ");
        for (int v : dow.values()) {
            sj.add(DOW_NAMES[v]);
        }
        return "on " + sj;
    }

    /** Month names, honoring a contiguous range as "X through Y". {@code base} is the value→name offset. */
    private static String namesOf(CronField f, String[] names, int base) {
        int[] range = f.contiguousRange();
        if (range != null) {
            return names[range[0] - 1 + base] + " through " + names[range[1] - 1 + base];
        }
        StringJoiner sj = new StringJoiner(", ");
        for (int v : f.values()) {
            sj.add(names[v - 1 + base]);
        }
        return sj.toString();
    }

    private static String fieldList(CronField f) {
        int[] range = f.contiguousRange();
        if (range != null) {
            return range[0] + " through " + range[1];
        }
        StringJoiner sj = new StringJoiner(", ");
        for (int v : f.values()) {
            sj.add(Integer.toString(v));
        }
        return sj.toString();
    }

    private static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
