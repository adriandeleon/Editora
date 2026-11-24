package com.editora.logviewer;

import org.jspecify.annotations.Nullable;

/**
 * The text half of a log filter: keep the records that match {@code include}, then hide those that match
 * {@code exclude}. Each is a regular expression, or plain text when it is not a valid one (see
 * {@link LogFilter#compileFilter}); several alternatives go in one pattern ({@code timeout|refused}).
 * Pure value, so the control bar, the palette commands and the view compare filters with {@code equals}.
 *
 * <p>An empty pattern is stored as {@code null}, so the field left empty and the field never touched are the
 * same filter. Patterns are not trimmed: a pattern may be about its spaces.
 *
 * @param include the records to keep, or {@code null} for all of them
 * @param exclude the records to hide, or {@code null} for none
 * @param matchCase match both patterns case-sensitively (by default case is ignored)
 * @param from keep only records written at or after this time (epoch milliseconds, see {@link LogTimes}), or
 *     {@code null} for no lower bound
 * @param to keep only records written at or before this time, or {@code null} for no upper bound
 */
public record LogQuery(
        @Nullable String include,
        @Nullable String exclude,
        boolean matchCase,
        @Nullable Long from,
        @Nullable Long to) {

    /** No text filter. */
    public static final LogQuery NONE = new LogQuery(null, null, false);

    public LogQuery {
        include = include == null || include.isEmpty() ? null : include;
        exclude = exclude == null || exclude.isEmpty() ? null : exclude;
    }

    /** A query by patterns alone, with no time range. */
    public LogQuery(@Nullable String include, @Nullable String exclude, boolean matchCase) {
        this(include, exclude, matchCase, null, null);
    }

    /** Whether a time range is set (either end). */
    public boolean hasTimeRange() {
        return from != null || to != null;
    }

    /** The same query with the time range replaced ({@code null} ends are open). */
    public LogQuery withTimeRange(@Nullable Long from, @Nullable Long to) {
        return new LogQuery(include, exclude, matchCase, from, to);
    }

    /** A query that only keeps what matches {@code include}, ignoring case. */
    public static LogQuery including(@Nullable String include) {
        return new LogQuery(include, null, false);
    }

    /** Whether neither pattern nor a time range is set, so the query filters nothing (whatever {@link #matchCase} says). */
    public boolean isEmpty() {
        return include == null && exclude == null && !hasTimeRange();
    }

    /** This query, or {@link #NONE} when it filters nothing: the form a view stores and compares. */
    public LogQuery normalized() {
        return isEmpty() ? NONE : this;
    }

    /** The same query with {@code include} replaced. */
    public LogQuery withInclude(@Nullable String text) {
        return new LogQuery(text, exclude, matchCase, from, to);
    }
}
