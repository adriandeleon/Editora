package com.editora.git;

/**
 * Pure bucketing of an elapsed duration into a coarse {@link Span} (unit + count) for human-readable
 * "N days ago"-style blame annotations. Locale-independent and unit-tested; the caller maps the
 * {@link Unit} to a localized string. Mirrors the granularity GitLens/IntelliJ blame use.
 */
public final class RelativeTime {

    public enum Unit {
        NOW,
        MINUTES,
        HOURS,
        DAYS,
        WEEKS,
        MONTHS,
        YEARS
    }

    /** A coarse age: e.g. {@code (DAYS, 3)} → "3 days ago". {@code NOW} carries {@code value = 0}. */
    public record Span(Unit unit, long value) {}

    private static final long MINUTE = 60;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final long WEEK = 7 * DAY;
    private static final long MONTH = 30 * DAY;
    private static final long YEAR = 365 * DAY;

    private RelativeTime() {}

    /**
     * How far ahead of the clock a time may be and still read "now": a commit made on a machine whose clock
     * runs a little fast, or a few seconds of difference between this machine and a remote.
     */
    public static final long SKEW_SECONDS = 5 * MINUTE;

    /**
     * Whether {@code epochSeconds} lies further in the future than {@link #SKEW_SECONDS}: "now" would be a
     * lie, so a caller shows the absolute date instead.
     */
    public static boolean isFuture(long epochSeconds, long nowSeconds) {
        return epochSeconds - nowSeconds > SKEW_SECONDS;
    }

    /**
     * Buckets {@code nowSeconds - epochSeconds} (clamped at 0: a future time reads {@code NOW}; see
     * {@link #isFuture}) into the largest fitting unit. Four weeks and more read as a month, and a year less
     * a few days as a year — never "4 weeks" next to "1 month", or "12 months".
     */
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
        if (delta < 4 * WEEK) {
            return new Span(Unit.WEEKS, delta / WEEK);
        }
        if (delta / MONTH < 12) {
            return new Span(Unit.MONTHS, Math.max(1, delta / MONTH));
        }
        return new Span(Unit.YEARS, Math.max(1, delta / YEAR));
    }
}
