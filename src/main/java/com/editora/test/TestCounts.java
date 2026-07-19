package com.editora.test;

/**
 * A tally of test leaves by status, produced by {@link TestNode#tally()} and shown in the panel header. Pure.
 */
public record TestCounts(int total, int passed, int failed, int errored, int skipped, int running) {

    public static final TestCounts ZERO = new TestCounts(0, 0, 0, 0, 0, 0);

    /** Folds one test's status into these counts (also incrementing {@code total}). */
    public TestCounts plus(TestStatus status) {
        return new TestCounts(
                total + 1,
                passed + (status == TestStatus.PASSED ? 1 : 0),
                failed + (status == TestStatus.FAILED ? 1 : 0),
                errored + (status == TestStatus.ERROR ? 1 : 0),
                skipped + (status == TestStatus.SKIPPED ? 1 : 0),
                running + (status == TestStatus.RUNNING ? 1 : 0));
    }

    /** Failures plus errors — the count that turns the header red. */
    public int failedOrErrored() {
        return failed + errored;
    }

    public boolean anyFailed() {
        return failedOrErrored() > 0;
    }

    /** Settled tests (everything but still-running) — the numerator for the progress bar. */
    public int finished() {
        return total - running;
    }
}
