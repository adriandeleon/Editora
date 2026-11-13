package com.editora.config;

import org.jspecify.annotations.Nullable;

/**
 * A breakpoint's filters (IntelliJ's breakpoint filters, Eclipse's thread filter): where a hit counts. {@code thread}:
 * thread names it stops in; {@code caller}: the functions it must be called from; {@code instance}: the Mark Object
 * label {@code this} (or {@code self}) must carry. A list is comma-separated glob patterns ({@code worker-*}), and a
 * {@code !} before one excludes ({@code !main}). Blank: no filter of that kind.
 *
 * <p>No adapter knows these: the breakpoint stops, Editora checks the filters, and the program goes on when one fails
 * — so its hit count and log message are Editora's too.
 */
public record StopFilters(String thread, String caller, String instance) {

    /** No filter. */
    public static final StopFilters NONE = new StopFilters("", "", "");

    public StopFilters {
        thread = thread == null ? "" : thread.strip();
        caller = caller == null ? "" : caller.strip();
        instance = instance == null ? "" : instance.strip();
    }

    /** {@code filters}, or {@link #NONE} for null (a breakpoint stored before filters existed). */
    public static StopFilters of(@Nullable StopFilters filters) {
        return filters == null ? NONE : filters;
    }

    /** Whether it filters nothing. Not a bean getter: Jackson would store it. */
    public boolean none() {
        return thread.isEmpty() && caller.isEmpty() && instance.isEmpty();
    }
}
