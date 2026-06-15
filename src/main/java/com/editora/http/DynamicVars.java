package com.editora.http;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Expands {@code {{$…}}} dynamic variables to mirror the IntelliJ HTTP Client family: UUIDs, timestamps,
 * the {@code $random.*} generators, formatted {@code $datetime}/{@code $localDatetime}, and the
 * {@code $processEnv.NAME}/{@code $dotenv.NAME} lookups (the latter reads a {@code .env} beside the request
 * file). Given a fixed clock the deterministic cases are unit-tested; the random cases are tested by bound
 * and charset, the uuid by shape.
 */
public final class DynamicVars {

    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String ALNUM = LETTERS + "0123456789";
    private static final String HEX = "0123456789abcdef";

    private DynamicVars() {}

    /** True for a dynamic-variable name (begins with {@code $}). */
    public static boolean isDynamic(String name) {
        return name != null && name.startsWith("$");
    }

    /**
     * Expands a dynamic variable {@code name} (without the surrounding braces), using {@code now} as the
     * clock and {@code dir} (the request file's directory, may be {@code null}) for {@code $dotenv}. Unknown
     * names yield {@code ""}.
     */
    public static String value(String name, LocalDateTime now, Path dir) {
        if (name == null) {
            return "";
        }
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
                return now.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            case "$randomInt":
                return String.valueOf(randInt(args, 0, 1000));
            case "$random.integer":
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
        if (n.equals("$timestamp") || n.startsWith("$timestamp ")) {
            String[] tok = tokens(n.substring("$timestamp".length()));
            LocalDateTime at = tok.length >= 2 ? applyOffset(now, parseInt(tok[0], 0), tok[1]) : now;
            return String.valueOf(at.toEpochSecond(ZoneOffset.UTC));
        }
        if (n.startsWith("$localDatetime")) {
            return datetime(n.substring("$localDatetime".length()), now, true);
        }
        if (n.startsWith("$datetime")) {
            return datetime(n.substring("$datetime".length()), now, false);
        }
        if (n.startsWith("$processEnv")) {
            String v = System.getenv(key(n.substring("$processEnv".length())));
            return v == null ? "" : v;
        }
        if (n.startsWith("$dotenv")) {
            return dotenv(key(n.substring("$dotenv".length())), dir);
        }
        return "";
    }

    private static String datetime(String suffix, LocalDateTime now, boolean local) {
        // {{$datetime <format> [<amount> <unit>]}} — format is a quoted custom pattern or rfc1123/iso8601.
        String[] tok = tokens(suffix);
        String fmt = tok.length >= 1 ? tok[0] : "";
        LocalDateTime at = tok.length >= 3 ? applyOffset(now, parseInt(tok[1], 0), tok[2]) : now;
        try {
            if (fmt.isEmpty() || fmt.equalsIgnoreCase("iso8601")) {
                return local
                        ? at.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                        : at.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
            }
            DateTimeFormatter f = fmt.equalsIgnoreCase("rfc1123")
                    ? DateTimeFormatter.RFC_1123_DATE_TIME
                    : DateTimeFormatter.ofPattern(fmt, Locale.ROOT);
            return local
                    ? at.atZone(ZoneId.systemDefault()).format(f)
                    : at.atOffset(ZoneOffset.UTC).format(f);
        } catch (Exception e) {
            return "";
        }
    }

    /** Adds a signed {@code amount} of {@code unit} (y/M/w/d/h/m/s/ms) to {@code t} for date-math vars. */
    private static LocalDateTime applyOffset(LocalDateTime t, int amount, String unit) {
        return switch (unit) {
            case "y" -> t.plusYears(amount);
            case "M" -> t.plusMonths(amount);
            case "w" -> t.plusWeeks(amount);
            case "d" -> t.plusDays(amount);
            case "h" -> t.plusHours(amount);
            case "m" -> t.plusMinutes(amount);
            case "s" -> t.plusSeconds(amount);
            case "ms" -> t.plus(amount, java.time.temporal.ChronoUnit.MILLIS);
            default -> t;
        };
    }

    /** Whitespace-splits {@code s} into tokens, keeping a quoted segment together (quotes stripped). */
    private static String[] tokens(String s) {
        if (s == null || s.isBlank()) {
            return new String[0];
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("\"[^\"]*\"|'[^']*'|\\S+").matcher(s.trim());
        while (m.find()) {
            out.add(stripQuotes(m.group()));
        }
        return out.toArray(new String[0]);
    }

    private static String dotenv(String key, Path dir) {
        if (key.isEmpty() || dir == null) {
            return "";
        }
        try {
            Path env = dir.resolve(".env");
            return Files.exists(env) ? DotEnv.parse(Files.readString(env)).getOrDefault(key, "") : "";
        } catch (Exception e) {
            return "";
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

    private static String randFloat(String args) {
        double lo = 0;
        double hi = 1;
        String[] parts = args.isEmpty() ? new String[0] : args.split(",");
        if (parts.length >= 1 && !parts[0].isBlank()) {
            lo = parseDouble(parts[0].trim(), 0);
        }
        if (parts.length >= 2 && !parts[1].isBlank()) {
            hi = parseDouble(parts[1].trim(), 1);
        }
        double v = hi > lo ? ThreadLocalRandom.current().nextDouble(lo, hi) : lo;
        return String.valueOf(v);
    }

    private static String randString(int len, String charset) {
        int n = Math.max(0, len);
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
