package com.editora.cron;

import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * One parsed field of a cron expression (minute, hour, day-of-month, month, or day-of-week). Holds the
 * expanded set of matching values plus enough structure ({@link #star}, {@link #step}) for
 * {@link CronExpression#describe()} to phrase it in English. Pure; unit-tested via {@link CronExpression}.
 *
 * <p>Supports the Vixie-cron element forms within a comma list: {@code *}, {@code &#42;/n}, a single value,
 * {@code a-b} ranges, {@code a-b/n} and {@code a/n} stepped ranges, and 3-letter names (JAN–DEC, SUN–SAT).
 * A {@link ParseException} is thrown for an out-of-range or malformed value.
 */
final class CronField {

    /** Thrown for a malformed or out-of-range field element; message is user-facing. */
    static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    private final int min;
    private final int max;
    private final TreeSet<Integer> values;
    /** True only when the whole field was the literal {@code *} (drives the DOM/DOW OR-rule + "every"). */
    private final boolean star;
    /** The step of a pure {@code &#42;/n} / {@code a-b/n} field, else {@code null} (for "every N …"). */
    private final Integer step;

    private CronField(int min, int max, TreeSet<Integer> values, boolean star, Integer step) {
        this.min = min;
        this.max = max;
        this.values = values;
        this.star = star;
        this.step = step;
    }

    /**
     * Parses one field {@code token} against its inclusive {@code [min,max]} range. {@code names} maps a
     * lowercased 3-letter name to its number (e.g. {@code jan}→1), or {@code null} for numeric-only fields.
     * {@code sundayWrap} handles the day-of-week quirk where {@code 7} is an alias for Sunday ({@code 0}).
     */
    static CronField parse(String token, int min, int max, Map<String, Integer> names, boolean sundayWrap) {
        String t = token.trim();
        if (t.isEmpty()) {
            throw new ParseException("empty field");
        }
        boolean star = t.equals("*");
        TreeSet<Integer> out = new TreeSet<>();
        Integer fieldStep = null;
        for (String element : t.split(",")) {
            fieldStep = parseElement(element.trim(), min, max, names, sundayWrap, out, t.contains(","), fieldStep);
        }
        if (out.isEmpty()) {
            throw new ParseException("no matching values in \"" + token + "\"");
        }
        return new CronField(min, max, out, star, star ? null : fieldStep);
    }

    /** Expands one comma element into {@code out}; returns the field-level step if this element sets one. */
    private static Integer parseElement(
            String el,
            int min,
            int max,
            Map<String, Integer> names,
            boolean sundayWrap,
            TreeSet<Integer> out,
            boolean isList,
            Integer priorStep) {
        int slash = el.indexOf('/');
        int step = 1;
        String rangePart = el;
        if (slash >= 0) {
            rangePart = el.substring(0, slash);
            step = parseInt(el.substring(slash + 1), "step");
            if (step <= 0) {
                throw new ParseException("step must be positive in \"" + el + "\"");
            }
        }
        int lo;
        int hi;
        if (rangePart.equals("*")) {
            lo = min;
            hi = max;
        } else {
            int dash = rangePart.indexOf('-');
            if (dash > 0) {
                lo = value(rangePart.substring(0, dash), min, max, names, sundayWrap);
                hi = value(rangePart.substring(dash + 1), min, max, names, sundayWrap);
            } else {
                lo = value(rangePart, min, max, names, sundayWrap);
                // A bare "a/n" means a..max step n; a bare "a" is just a.
                hi = slash >= 0 ? max : lo;
            }
        }
        if (lo > hi) {
            throw new ParseException("range start after end in \"" + el + "\"");
        }
        for (int v = lo; v <= hi; v += step) {
            out.add(v);
        }
        // Report a field-wide step only for a single "*/n" (or "a-b/n") element, not a list.
        return (!isList && slash >= 0) ? Integer.valueOf(step) : priorStep;
    }

    private static int value(String s, int min, int max, Map<String, Integer> names, boolean sundayWrap) {
        String v = s.trim();
        int n;
        if (names != null && !v.isEmpty() && !Character.isDigit(v.charAt(0)) && v.charAt(0) != '-') {
            Integer named = names.get(v.toLowerCase(Locale.ROOT));
            if (named == null) {
                throw new ParseException("unknown name \"" + s + "\"");
            }
            n = named;
        } else {
            n = parseInt(v, "value");
        }
        if (sundayWrap && n == 7) {
            n = 0;
        }
        if (n < min || n > max) {
            throw new ParseException(n + " is out of range (" + min + "–" + max + ")");
        }
        return n;
    }

    private static int parseInt(String s, String what) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            throw new ParseException("invalid " + what + " \"" + s + "\"");
        }
    }

    boolean matches(int v) {
        return values.contains(v);
    }

    boolean isStar() {
        return star;
    }

    Integer step() {
        return step;
    }

    TreeSet<Integer> values() {
        return values;
    }

    int min() {
        return min;
    }

    int max() {
        return max;
    }

    /** The sole value when the field matches exactly one number, else {@code null}. */
    Integer single() {
        return values.size() == 1 ? values.first() : null;
    }

    /** {@code {lo,hi}} when the values form a contiguous run of size &gt; 1 (for "X through Y"), else {@code null}. */
    int[] contiguousRange() {
        if (values.size() < 2) {
            return null;
        }
        int lo = values.first();
        int hi = values.last();
        if (hi - lo + 1 != values.size()) {
            return null;
        }
        return new int[] {lo, hi};
    }

    /** True when the field constrains nothing (either {@code *} or a step covering every value in range). */
    boolean coversAll() {
        return values.size() == (max - min + 1);
    }
}
