package com.editora.test;

/**
 * The state of a single test (or, via roll-up, of a suite/class node). Pure — no toolkit.
 *
 * <p>{@link #worst(TestStatus, TestStatus)} folds a parent's status from its children by severity so a suite
 * shows the most attention-worthy state: a failure surfaces immediately (dominating a still-running sibling),
 * but a suite with only passing + still-running children stays {@code RUNNING} until everything finishes.
 */
public enum TestStatus {
    RUNNING,
    PASSED,
    FAILED,
    ERROR,
    SKIPPED;

    /** Whether this is a settled result (anything but {@link #RUNNING}). */
    public boolean isTerminal() {
        return this != RUNNING;
    }

    /** Whether this counts as a failure ({@link #FAILED} assertion or unexpected {@link #ERROR}). */
    public boolean isFailure() {
        return this == FAILED || this == ERROR;
    }

    // Severity for the pairwise roll-up: a failure wins over an in-progress sibling (surface it early); an
    // in-progress sibling wins over a settled pass/skip (the suite isn't done yet).
    private int severity() {
        return switch (this) {
            case ERROR -> 4;
            case FAILED -> 3;
            case RUNNING -> 2;
            case PASSED -> 1;
            case SKIPPED -> 0;
        };
    }

    /** The more attention-worthy of two statuses (see the class doc for the ordering rationale). */
    public static TestStatus worst(TestStatus a, TestStatus b) {
        return a.severity() >= b.severity() ? a : b;
    }
}
