package com.editora.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A breakpoint on a single line of a file: a 0-based line index, an optional {@code condition} (a Java
 * boolean expression that must hold for the breakpoint to stop) and {@code logMessage} (a non-empty
 * value makes it a <em>logpoint</em> — it logs instead of stopping), an {@code enabled} flag, and a
 * captured snapshot of the line's text (so breakpoints can be re-anchored to their content when a file is
 * edited outside the editor). Persisted per file path, bucketed per project, in {@link BreakpointStore}
 * ({@code breakpoints.json}).
 *
 * <p>A Jackson-serialized record; the {@code com.editora.config} package is already opened to
 * jackson.databind in {@code module-info.java} (see {@link Bookmark}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Breakpoint(
        int line, String condition, String logMessage, boolean enabled, String lineText, String hitCondition) {

    /** Max stored length of the captured line snapshot. */
    public static final int MAX_LINE_TEXT = 200;

    public Breakpoint {
        condition = condition == null ? "" : condition;
        logMessage = logMessage == null ? "" : logMessage;
        lineText = lineText == null ? "" : lineText;
        hitCondition = hitCondition == null ? "" : hitCondition;
        if (lineText.length() > MAX_LINE_TEXT) {
            lineText = lineText.substring(0, MAX_LINE_TEXT);
        }
    }

    /** A breakpoint without a hit condition (stops on every hit that meets its condition). */
    public Breakpoint(int line, String condition, String logMessage, boolean enabled, String lineText) {
        this(line, condition, logMessage, enabled, lineText, "");
    }

    /** A plain enabled breakpoint on {@code line} with no condition/log, capturing {@code lineText}. */
    public static Breakpoint plain(int line, String lineText) {
        return new Breakpoint(line, "", "", true, lineText);
    }

    /** True if this is a logpoint (logs a message instead of suspending). */
    public boolean isLogpoint() {
        return !logMessage.isEmpty();
    }

    /** True if this carries a conditional expression or a hit condition (it does not stop on every hit). */
    public boolean isConditional() {
        return !condition.isEmpty() || !hitCondition.isEmpty();
    }

    public Breakpoint withLine(int newLine) {
        return new Breakpoint(newLine, condition, logMessage, enabled, lineText, hitCondition);
    }

    /** This breakpoint with a fresh snapshot of its line's text (the line was edited in place). */
    public Breakpoint withLineText(String newLineText) {
        return new Breakpoint(line, condition, logMessage, enabled, newLineText, hitCondition);
    }

    public Breakpoint withCondition(String newCondition) {
        return new Breakpoint(line, newCondition, logMessage, enabled, lineText, hitCondition);
    }

    public Breakpoint withLogMessage(String newLog) {
        return new Breakpoint(line, condition, newLog, enabled, lineText, hitCondition);
    }

    public Breakpoint withEnabled(boolean newEnabled) {
        return new Breakpoint(line, condition, logMessage, newEnabled, lineText, hitCondition);
    }

    /**
     * This breakpoint with hit condition {@code newHitCondition} — when, counting hits, it stops: {@code 5} (the
     * fifth), and with debugpy also {@code >= 3} or {@code % 2}. Blank: every hit.
     */
    public Breakpoint withHitCondition(String newHitCondition) {
        return new Breakpoint(line, condition, logMessage, enabled, lineText, newHitCondition);
    }
}
