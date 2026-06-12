package com.editora.git;

/**
 * Pure bucketing of an elapsed duration into a coarse {@link Span} (unit + count) for human-readable
 * "N days ago"-style blame annotations. Locale-independent and unit-tested; the caller maps the
 * {@link Unit} to a localized string. Mirrors the granularity GitLens/IntelliJ blame use.
 */
public final class RelativeTime {

    public enum Unit { NOW, MINUTES, HOURS, DAYS, WEEKS, MONTHS, YEARS }

    /** A coarse age: e.g. {@code (DAYS, 3)} → "3 days ago". {@code NOW} carries {@code value = 0}. */
    public record Span(Unit unit, long value) { }

    private static final long MINUTE = 60;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    private RelativeTime() {
    }

    /** Buckets {@code nowSeconds - epochSeconds} (clamped at 0) into the largest fitting unit. */
    public static Span of(long epochSeconds, long nowSeconds) {
        long delta = Math.max(0, nowSeconds - epochSeconds);
        if (delta < MINUTE) {
            return new Span(Unit.NOW, 0);
        }
        if (delta < HOUR) {
            return new Span(Unit.MINUTES, delta / MINUTE);
        }
        if (delta < DAY) {
            return new Span(Unit.HOURS, delta / HOUR);
        }
        if (delta < WEEK) {
            return new Span(Unit.DAYS, delta / DAY);
        }
        if (delta < MONTH) {
            return new Span(Unit.WEEKS, delta / WEEK);
        }
        if (delta < YEAR) {
            return new Span(Unit.MONTHS, delta / MONTH);
        }
        return new Span(Unit.YEARS, delta / YEAR);
    }
}
