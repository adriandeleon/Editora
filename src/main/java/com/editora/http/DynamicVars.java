package com.editora.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.editora.i18n.Messages.tr;

/**
 * Expands {@code {{$…}}} dynamic variables to mirror the IntelliJ HTTP Client family: UUIDs, timestamps,
 * the {@code $random.*} generators, formatted {@code $datetime}/{@code $localDatetime}, and the
 * {@code $processEnv NAME} / {@code $env.NAME} / {@code $dotenv NAME} lookups (the latter reads a {@code .env}
 * beside the request file). The VS Code REST Client forms ({@code {{$randomInt 1 10}}}, Day.js date formats
 * such as {@code "DD-MM-YYYY"}) are accepted too.
 *
 * <p>The clock is a {@link ZonedDateTime} — an instant plus the zone {@code $localDatetime} is shown in — so
 * {@code $timestamp}, {@code $isoTimestamp} and {@code $datetime} are the real UTC instant whatever the
 * user's time zone. Given a fixed clock the deterministic cases are unit-tested; the random cases are tested
 * by bound and charset, the uuid by shape.
 */
public final class DynamicVars {

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALNUM = LETTERS + "0123456789";
    private static final String HEX = "0123456789abcdef";

    /** The longest {@code $random.alphabetic(n)} (and friends) string built; a larger {@code n} is clamped. */
    static final int MAX_RANDOM_LENGTH = 10_000;

    private static final Pattern TOKENS = Pattern.compile("\"[^\"]*\"|'[^']*'|\\S+");

    private DynamicVars() {}

    /**
     * Whether {@code name} reads a secret from outside the {@code .http} file: an environment variable of the editor
     * process ({@code $processEnv X}, {@code $processEnv.X}, {@code $env.X}, {@code $env X}) or a key of the
     * {@code .env} file ({@code $dotenv X}). {@link #resolve} reads the process environment and {@code .env} only for
     * such a name, so the trust gate, which asks about exactly these, cannot miss a form the resolver accepts.
     */
    public static boolean isSecretSource(String name) {
        if (name == null) {
            return false;
        }
        String n = name.trim();
        return n.startsWith("$processEnv") || n.startsWith("$env.") || n.startsWith("$env ") || n.startsWith("$dotenv");
    }

    /** True for a dynamic-variable name (begins with {@code $}). */
    public static boolean isDynamic(String name) {
        return name != null && name.startsWith("$");
    }

    /** {@link #value(String, ZonedDateTime, Path)} for a wall-clock {@code now} in the system time zone. */
    public static String value(String name, LocalDateTime now, Path dir) {
        return value(name, now == null ? null : now.atZone(ZoneId.systemDefault()), dir);
    }

    /**
     * Expands a dynamic variable {@code name} (without the surrounding braces), using {@code now} as the
     * clock and {@code dir} (the request file's directory, may be {@code null}) for {@code $dotenv}. A name it
     * cannot resolve yields {@code ""} (see {@link #resolve} for the reason).
     */
    public static String value(String name, ZonedDateTime now, Path dir) {
        String v = resolve(name, now, dir, null);
        return v == null ? "" : v;
    }

    /**
     * Expands {@code name}, or returns {@code null} when it cannot be resolved — an unknown name, an unset
     * environment variable, a key missing from {@code .env}, an unusable date format — after putting a short
     * human-readable reason in {@code reason[0]} (when {@code reason} is non-null).
     */
    static String resolve(String name, ZonedDateTime now, Path dir, String[] reason) {
        if (name == null) {
            return miss(reason, "http.unresolved.reason.dynamic");
        }
        ZonedDateTime clock = now == null ? ZonedDateTime.now() : now;
        String n = name.trim();
        String head = n;
        String args = "";
        int paren = n.indexOf('(');
        if (paren >= 0 && n.endsWith(")")) {
            head = n.substring(0, paren).trim();
            args = n.substring(paren + 1, n.length() - 1).trim();
        }
        switch (head) {
            case "$uuid", "$random.uuid", "$guid":
                return UUID.randomUUID().toString();
            case "$isoTimestamp":
                return iso(clock.withZoneSameInstant(ZoneOffset.UTC));
            case "$randomInt", "$random.integer":
                return String.valueOf(randInt(args, 0, 1000));
            case "$random.float":
                return randFloat(args);
            case "$random.alphabetic":
                return randString(intArg(args, 16), LETTERS);
            case "$random.alphanumeric":
                return randString(intArg(args, 16), ALNUM);
            case "$random.hexadecimal":
                return randString(intArg(args, 16), HEX);
            case "$random.email":
                return randString(8, LETTERS).toLowerCase(Locale.ROOT) + "@example.com";
            default:
                break;
        }
        if (n.startsWith("$randomInt ")) {
            // VS Code REST Client form: {{$randomInt min max}} (max exclusive).
            String[] tok = tokens(n.substring("$randomInt".length()));
            return String.valueOf(randInt(tok.length >= 2 ? tok[0] + "," + tok[1] : String.join(",", tok), 0, 1000));
        }
        if (n.equals("$timestamp") || n.startsWith("$timestamp ")) {
            String[] tok = tokens(n.substring("$timestamp".length()));
            ZonedDateTime at = tok.length >= 2 ? applyOffset(clock, parseInt(tok[0], 0), tok[1]) : clock;
            return at == null ? miss(reason, "http.unresolved.reason.dateRange") : String.valueOf(at.toEpochSecond());
        }
        if (n.equals("$localDatetime") || n.startsWith("$localDatetime ")) {
            return datetime(n.substring("$localDatetime".length()), clock, true, reason);
        }
        if (n.equals("$datetime") || n.startsWith("$datetime ")) {
            return datetime(n.substring("$datetime".length()), clock, false, reason);
        }
        if (isSecretSource(n)) { // the only branch that reads the process environment or .env
            if (n.startsWith("$dotenv")) {
                String v = dotenv(key(n.substring("$dotenv".length())), dir);
                return v == null ? miss(reason, "http.unresolved.reason.dotenv") : v;
            }
            String key = key(n.substring(n.startsWith("$processEnv") ? "$processEnv".length() : "$env".length()));
            String v = key.isEmpty() ? null : System.getenv(key);
            return v == null ? miss(reason, "http.unresolved.reason.env") : v;
        }
        return miss(reason, "http.unresolved.reason.dynamic");
    }

    private static String miss(String[] reason, String key) {
        if (reason != null && reason.length > 0) {
            reason[0] = tr(key);
        }
        return null;
    }

    /** ISO-8601 with millisecond precision (as IntelliJ sends it: {@code 2026-06-10T09:30:00.123Z}). */
    private static String iso(ZonedDateTime utc) {
        return utc.truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_INSTANT);
    }

    private static String datetime(String suffix, ZonedDateTime now, boolean local, String[] reason) {
        // {{$datetime <format> [<amount> <unit>]}} — format is a quoted custom pattern or rfc1123/iso8601.
        String[] tok = tokens(suffix);
        String fmt = tok.length >= 1 ? tok[0] : "";
        ZonedDateTime at = tok.length >= 3 ? applyOffset(now, parseInt(tok[1], 0), tok[2]) : now;
        if (at == null) {
            return miss(reason, "http.unresolved.reason.dateRange");
        }
        ZonedDateTime shown = at.withZoneSameInstant(local ? ZoneId.systemDefault() : ZoneOffset.UTC);
        try {
            if (fmt.isEmpty() || fmt.equalsIgnoreCase("iso8601")) {
                return local
                        ? shown.truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        : iso(shown);
            }
            if (fmt.equalsIgnoreCase("rfc1123")) {
                return shown.format(DateTimeFormatter.RFC_1123_DATE_TIME);
            }
            if (fmt.equals("X")) {
                return String.valueOf(shown.toEpochSecond()); // Day.js unix seconds
            }
            if (fmt.equals("x")) {
                return String.valueOf(shown.toInstant().toEpochMilli()); // Day.js unix milliseconds
            }
            // English names, not the root locale's: Locale.ROOT has no full names ("MMMM" printed "Dec").
            return isDayjs(fmt) ? dayjs(fmt, shown) : shown.format(DateTimeFormatter.ofPattern(fmt, Locale.ENGLISH));
        } catch (RuntimeException e) {
            return miss(reason, "http.unresolved.reason.format");
        }
    }

    /**
     * Whether {@code fmt} is a Day.js/moment pattern (the VS Code REST Client's) rather than a Java
     * {@link DateTimeFormatter} pattern. A pattern with a lowercase {@code y} or a {@code 'quoted'} literal is
     * Java; otherwise one that uses {@code Y}, {@code D}, {@code A}, {@code ddd} or a {@code [literal]} is Day.js:
     * in Java those are the week-based year, the day of the year, the milli-of-day, a three-digit day of the month
     * and an optional section, which a date format meant for Java practically never wants (and
     * {@code "DD-MM-YYYY"} would silently print {@code 364-12-2027} for 30 December 2026).
     */
    static boolean isDayjs(String fmt) {
        String outside = fmt.replaceAll("\\[[^\\]]*]", ""); // a [literal] may hold anything
        if (outside.indexOf('y') >= 0 || outside.indexOf('\'') >= 0) {
            return false;
        }
        return fmt.indexOf('Y') >= 0
                || fmt.indexOf('D') >= 0
                || fmt.indexOf('[') >= 0
                || fmt.indexOf('A') >= 0
                || fmt.contains("ddd");
    }

    /** The Day.js format tokens, longest first (as Day.js matches them); any other character is literal. */
    private static final Pattern DAYJS_TOKEN = Pattern.compile(
            "\\[[^\\]]*]|YYYY|YY|MMMM|MMM|MM|M|DDDD|DDD|Do|DD|D|dddd|ddd|dd|d|HH|H|hh|h|mm|m|ss|s|SSS|SS|S"
                    + "|A|a|ZZ|Z|X|x|Q|WW|W");

    /** Formats {@code t} with the Day.js pattern {@code fmt} (English names), token by token. */
    static String dayjs(String fmt, ZonedDateTime t) {
        StringBuilder out = new StringBuilder();
        Matcher m = DAYJS_TOKEN.matcher(fmt);
        int at = 0;
        while (m.find()) {
            out.append(fmt, at, m.start());
            out.append(dayjsToken(m.group(), t));
            at = m.end();
        }
        return out.append(fmt.substring(at)).toString();
    }

    private static String dayjsToken(String token, ZonedDateTime t) {
        if (token.startsWith("[")) {
            return token.substring(1, token.length() - 1);
        }
        int hour12 = t.getHour() % 12 == 0 ? 12 : t.getHour() % 12;
        return switch (token) {
            case "YYYY" -> pad(t.getYear(), 4);
            case "YY" -> pad(Math.floorMod(t.getYear(), 100), 2);
            case "MMMM" -> name(t, "MMMM");
            case "MMM" -> name(t, "MMM");
            case "MM" -> pad(t.getMonthValue(), 2);
            case "M" -> String.valueOf(t.getMonthValue());
            case "DDDD" -> pad(t.getDayOfYear(), 3);
            case "DDD" -> String.valueOf(t.getDayOfYear());
            case "Do" -> t.getDayOfMonth() + ordinal(t.getDayOfMonth());
            case "DD" -> pad(t.getDayOfMonth(), 2);
            case "D" -> String.valueOf(t.getDayOfMonth());
            case "dddd" -> name(t, "EEEE");
            case "ddd" -> name(t, "EEE");
            case "dd" -> name(t, "EEE").substring(0, 2);
            case "d" -> String.valueOf(t.getDayOfWeek().getValue() % 7); // Sunday is 0
            case "HH" -> pad(t.getHour(), 2);
            case "H" -> String.valueOf(t.getHour());
            case "hh" -> pad(hour12, 2);
            case "h" -> String.valueOf(hour12);
            case "mm" -> pad(t.getMinute(), 2);
            case "m" -> String.valueOf(t.getMinute());
            case "ss" -> pad(t.getSecond(), 2);
            case "s" -> String.valueOf(t.getSecond());
            case "SSS" -> pad(t.getNano() / 1_000_000, 3);
            case "SS" -> pad(t.getNano() / 10_000_000, 2);
            case "S" -> String.valueOf(t.getNano() / 100_000_000);
            case "A" -> t.getHour() < 12 ? "AM" : "PM";
            case "a" -> t.getHour() < 12 ? "am" : "pm";
            case "ZZ" -> t.format(DateTimeFormatter.ofPattern("xx", Locale.ENGLISH));
            case "Z" -> t.format(DateTimeFormatter.ofPattern("xxx", Locale.ENGLISH));
            case "X" -> String.valueOf(t.toEpochSecond());
            case "x" -> String.valueOf(t.toInstant().toEpochMilli());
            case "Q" -> String.valueOf((t.getMonthValue() - 1) / 3 + 1);
            case "WW" -> pad(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), 2);
            case "W" -> String.valueOf(t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
            default -> token;
        };
    }

    private static String name(ZonedDateTime t, String pattern) {
        return t.format(DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
    }

    private static String pad(int value, int width) {
        String s = String.valueOf(Math.abs(value));
        return (value < 0 ? "-" : "") + "0".repeat(Math.max(0, width - s.length())) + s;
    }

    private static String ordinal(int day) {
        if (day % 100 >= 11 && day % 100 <= 13) {
            return "th";
        }
        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    /**
     * Adds a signed {@code amount} of {@code unit} (y/M/w/d/h/m/s/ms) to {@code t} for date-math vars, or
     * {@code null} when the result is beyond the dates Java can hold ({@code 999999999 y}).
     */
    private static ZonedDateTime applyOffset(ZonedDateTime t, int amount, String unit) {
        try {
            return plus(t, amount, unit);
        } catch (java.time.DateTimeException | ArithmeticException outOfRange) {
            return null;
        }
    }

    private static ZonedDateTime plus(ZonedDateTime t, int amount, String unit) {
        return switch (unit) {
            case "y" -> t.plusYears(amount);
            case "M" -> t.plusMonths(amount);
            case "w" -> t.plusWeeks(amount);
            case "d" -> t.plusDays(amount);
            case "h" -> t.plusHours(amount);
            case "m" -> t.plusMinutes(amount);
            case "s" -> t.plusSeconds(amount);
            case "ms" -> t.plus(amount, ChronoUnit.MILLIS);
            default -> t;
        };
    }

    /** Whitespace-splits {@code s} into tokens, keeping a quoted segment together (quotes stripped). */
    private static String[] tokens(String s) {
        if (s == null || s.isBlank()) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        Matcher m = TOKENS.matcher(s.trim());
        while (m.find()) {
            out.add(stripQuotes(m.group()));
        }
        return out.toArray(new String[0]);
    }

    /** The value of {@code key} in the {@code .env} beside the request file, or {@code null}. */
    private static String dotenv(String key, Path dir) {
        if (key.isEmpty() || dir == null) {
            return null;
        }
        try {
            Path env = dir.resolve(".env");
            return Files.exists(env) ? DotEnv.parse(Files.readString(env)).get(key) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int randInt(String args, int defLo, int defHi) {
        int lo = defLo;
        int hi = defHi;
        String[] parts = args.isEmpty() ? new String[0] : args.split(",");
        if (parts.length >= 1 && !parts[0].isBlank()) {
            lo = parseInt(parts[0].trim(), defLo);
        }
        if (parts.length >= 2 && !parts[1].isBlank()) {
            hi = parseInt(parts[1].trim(), defHi);
        }
        return hi > lo ? ThreadLocalRandom.current().nextInt(lo, hi) : lo;
    }

    /** {@code $random.float(from, to)}: {@code from} inclusive, {@code to} exclusive, 0–1000 by default (IntelliJ). */
    private static String randFloat(String args) {
        double lo = 0;
        double hi = 1000;
        String[] parts = args.isEmpty() ? new String[0] : args.split(",");
        if (parts.length >= 1 && !parts[0].isBlank()) {
            lo = parseDouble(parts[0].trim(), 0);
        }
        if (parts.length >= 2 && !parts[1].isBlank()) {
            hi = parseDouble(parts[1].trim(), 1000);
        }
        double v = hi > lo ? ThreadLocalRandom.current().nextDouble(lo, hi) : lo;
        return String.valueOf(v);
    }

    private static String randString(int len, String charset) {
        int n = Math.clamp(len, 0, MAX_RANDOM_LENGTH);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(charset.charAt(ThreadLocalRandom.current().nextInt(charset.length())));
        }
        return sb.toString();
    }

    private static int intArg(String args, int def) {
        return args.isBlank() ? def : parseInt(args.trim(), def);
    }

    private static String key(String suffix) {
        String s = suffix.trim();
        if (s.startsWith(".")) {
            s = s.substring(1);
        }
        return s.trim();
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double parseDouble(String s, double def) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
