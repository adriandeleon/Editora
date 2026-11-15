package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An exception breakpoint (IntelliJ's Java Exception Breakpoints, Visual Studio's Exception Settings): the debugger
 * stops when an exception of {@code type} is thrown in a {@code language} session, and {@code condition} — evaluated
 * in the frame that threw — holds. {@code type} is one or more patterns separated by commas, {@code *} and {@code ?}
 * wildcards, {@code !} before one excluding; it matches the exception's type as the debugger names it, whole
 * ({@code java.lang.NullPointerException}) or by its last part ({@code NullPointerException}). Blank is any type.
 *
 * <p>Editora checks them itself at each exception stop, so they work with every debugger that stops on exceptions;
 * subclasses are not matched (a pattern such as {@code *IOException} reaches them by name). Persisted per project in
 * {@link BreakpointStore} ({@code breakpoints.json}, schema 11).
 *
 * @param language the debug language it applies to ({@code java}, {@code python}, {@code javascript}…)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExceptionBreakpoint(String language, String type, String condition, boolean enabled) {

    public ExceptionBreakpoint {
        language = language == null ? "" : language.strip();
        type = type == null ? "" : type.strip();
        condition = condition == null ? "" : condition.strip();
    }

    /** This breakpoint turned on or off. */
    public ExceptionBreakpoint withEnabled(boolean on) {
        return new ExceptionBreakpoint(language, type, condition, on);
    }
}
