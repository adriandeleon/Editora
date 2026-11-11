package com.editora.config;

import java.util.List;
import java.util.TreeSet;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A breakpoint on a single line of a file: a 0-based line index, an optional {@code condition} (a Java
 * boolean expression that must hold for the breakpoint to stop) and {@code logMessage} (a non-empty
 * value makes it a <em>logpoint</em> — it logs instead of stopping), an {@code enabled} flag, and a
 * captured snapshot of the line's text (so breakpoints can be re-anchored to their content when a file is
 * edited outside the editor). On a line of Java that starts lambdas, it can also stop inside some of them
 * ({@code lambdas}: their numbers on the line, from 1, left to right), or only there ({@code lambdasOnly}): the
 * Java debugger's lambda breakpoints. Persisted per file path, bucketed per project, in {@link BreakpointStore}
 * ({@code breakpoints.json}). A {@code recording} one does not stay stopped: Debug History keeps the stop's values and
 * the program goes on. A breakpoint with a {@code triggeredBy} waits in each debug session until the breakpoint with
 * that {@code id} is hit (a triggered breakpoint).
 *
 * <p>A Jackson-serialized record; the {@code com.editora.config} package is already opened to
 * jackson.databind in {@code module-info.java} (see {@link Bookmark}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Breakpoint(
        int line,
        String condition,
        String logMessage,
        boolean enabled,
        String lineText,
        String hitCondition,
        boolean temporary,
        List<Integer> lambdas,
        boolean lambdasOnly,
        boolean recording,
        String id,
        String triggeredBy) {

    /** Max stored length of the captured line snapshot. */
    public static final int MAX_LINE_TEXT = 200;

    public Breakpoint {
        condition = condition == null ? "" : condition;
        logMessage = logMessage == null ? "" : logMessage;
        lineText = lineText == null ? "" : lineText;
        hitCondition = hitCondition == null ? "" : hitCondition;
        id = id == null ? "" : id.strip();
        triggeredBy = triggeredBy == null || triggeredBy.strip().equals(id) ? "" : triggeredBy.strip();
        if (lineText.length() > MAX_LINE_TEXT) {
            lineText = lineText.substring(0, MAX_LINE_TEXT);
        }
        TreeSet<Integer> numbers = new TreeSet<>();
        if (lambdas != null) {
            for (Integer n : lambdas) {
                if (n != null && n >= 1 && n <= MAX_LAMBDA) {
                    numbers.add(n);
                }
            }
        }
        lambdas = List.copyOf(numbers);
        lambdasOnly = lambdasOnly && !lambdas.isEmpty(); // with no lambda it stops on the line
    }

    /** A breakpoint with no id, that waits for no other one. */
    public Breakpoint(
            int line,
            String condition,
            String logMessage,
            boolean enabled,
            String lineText,
            String hitCondition,
            boolean temporary,
            List<Integer> lambdas,
            boolean lambdasOnly,
            boolean recording) {
        this(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                "",
                "");
    }

    /** A breakpoint that stops on its line or in its lambdas, and is not a recording one. */
    public Breakpoint(
            int line,
            String condition,
            String logMessage,
            boolean enabled,
            String lineText,
            String hitCondition,
            boolean temporary,
            List<Integer> lambdas,
            boolean lambdasOnly) {
        this(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                false,
                "",
                "");
    }

    /** Highest lambda number kept: a line never starts more lambdas than this. */
    public static final int MAX_LAMBDA = 32;

    /** A breakpoint that stops on its line only. */
    public Breakpoint(
            int line,
            String condition,
            String logMessage,
            boolean enabled,
            String lineText,
            String hitCondition,
            boolean temporary) {
        this(line, condition, logMessage, enabled, lineText, hitCondition, temporary, List.of(), false, false, "", "");
    }

    /** A breakpoint that stays after it is hit. */
    public Breakpoint(
            int line, String condition, String logMessage, boolean enabled, String lineText, String hitCondition) {
        this(line, condition, logMessage, enabled, lineText, hitCondition, false);
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
        return new Breakpoint(
                newLine,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    /** This breakpoint with a fresh snapshot of its line's text (the line was edited in place). */
    public Breakpoint withLineText(String newLineText) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                newLineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    public Breakpoint withCondition(String newCondition) {
        return new Breakpoint(
                line,
                newCondition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    public Breakpoint withLogMessage(String newLog) {
        return new Breakpoint(
                line,
                condition,
                newLog,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    public Breakpoint withEnabled(boolean newEnabled) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                newEnabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    /**
     * This breakpoint with hit condition {@code newHitCondition} — when, counting hits, it stops: {@code 5} (the
     * fifth), and with debugpy also {@code >= 3} or {@code % 2}. Blank: every hit.
     */
    public Breakpoint withHitCondition(String newHitCondition) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                newHitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    /**
     * This breakpoint, removed once the program stops on it ({@code true}) or kept ({@code false}) — IntelliJ's
     * "Remove once hit", Visual Studio's temporary breakpoint.
     */
    public Breakpoint withTemporary(boolean newTemporary) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                newTemporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                triggeredBy);
    }

    /**
     * This breakpoint stopping inside the lambdas numbered {@code newLambdas} on its line (from 1, left to right) as
     * well as on the line, or ({@code only}) instead of on it. No lambda: a plain line breakpoint.
     */
    public Breakpoint withLambdas(List<Integer> newLambdas, boolean only) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                newLambdas,
                only,
                recording,
                id,
                triggeredBy);
    }

    /**
     * This breakpoint as a recording one ({@code true}: Debug History keeps the stop's values and the program goes
     * on, as a tracepoint records) or one that stops ({@code false}).
     */
    public Breakpoint withRecording(boolean newRecording) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                newRecording,
                id,
                triggeredBy);
    }

    /**
     * This breakpoint known by {@code newId} — what a breakpoint that waits for it names ({@link #triggeredBy}). A
     * breakpoint is given one when another is first made to wait for it.
     */
    public Breakpoint withId(String newId) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                newId,
                triggeredBy);
    }

    /**
     * This breakpoint waiting, in each debug session, until the breakpoint whose {@link #id} is {@code trigger} is hit
     * — VS Code's triggered breakpoint, IntelliJ's "Disable until hitting the following breakpoint", Visual Studio's
     * "Only enable when the following breakpoint is hit". Blank: it waits for none.
     */
    public Breakpoint withTriggeredBy(String trigger) {
        return new Breakpoint(
                line,
                condition,
                logMessage,
                enabled,
                lineText,
                hitCondition,
                temporary,
                lambdas,
                lambdasOnly,
                recording,
                id,
                trigger);
    }

    /** Whether it waits for another breakpoint to be hit (when that one still exists). */
    @JsonIgnore
    public boolean isTriggered() {
        return !triggeredBy.isEmpty();
    }

    /** A new breakpoint id: short, random, unlikely to repeat within one project's breakpoints. */
    public static String newId() {
        return java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** Whether a hit is recorded and the program goes on: a recording breakpoint that is not a logpoint. */
    @JsonIgnore
    public boolean records() {
        return recording && !isLogpoint();
    }

    /** Whether it stops inside any lambda of its line. */
    @JsonIgnore
    public boolean hasLambdas() {
        return !lambdas.isEmpty();
    }

    /** Whether it stops on the line itself (and not only inside its lambdas). */
    @JsonIgnore
    public boolean stopsOnLine() {
        return !lambdasOnly;
    }
}
