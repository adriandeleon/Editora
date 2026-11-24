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
 */
public record LogQuery(@Nullable String include, @Nullable String exclude, boolean matchCase) {

    /** No text filter. */
    public static final LogQuery NONE = new LogQuery(null, null, false);

    public LogQuery {
        include = include == null || include.isEmpty() ? null : include;
        exclude = exclude == null || exclude.isEmpty() ? null : exclude;
    }

    /** A query that only keeps what matches {@code include}, ignoring case. */
    public static LogQuery including(@Nullable String include) {
        return new LogQuery(include, null, false);
    }

    /** Whether neither pattern is set, so the query filters nothing (whatever {@link #matchCase} says). */
    public boolean isEmpty() {
        return include == null && exclude == null;
    }

    /** This query, or {@link #NONE} when it filters nothing: the form a view stores and compares. */
    public LogQuery normalized() {
        return isEmpty() ? NONE : this;
    }

    /** The same query with {@code include} replaced. */
    public LogQuery withInclude(@Nullable String text) {
        return new LogQuery(text, exclude, matchCase);
    }
}
