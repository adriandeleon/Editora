package com.editora.test;

/**
 * A parser's neutral result for one test case. {@code className} is the owning grouping (JUnit FQN class / Go
 * package / Cargo crate / TAP file) and doubles as the suite key; {@code methodName} is the case name.
 * {@code sourceFileHint}/{@code sourceLineHint} help jump-to-source ({@code null}/0 when unknown).
 * {@code retries} is how many times the runner re-ran the test before this result (Surefire's
 * {@code rerunFailingTestsCount}): a passed test with retries is <em>flaky</em>, a failed one failed every try;
 * {@code 0} for a single run (review D16). Pure data.
 */
public record ParsedTest(
        String className,
        String methodName,
        TestStatus status,
        long durationMs,
        String failureType,
        String failureMessage,
        String stackTrace,
        String stdout,
        String sourceFileHint,
        int sourceLineHint,
        int retries) {

    /** A result with no retries ({@code retries} is Surefire's rerun count — see {@link #retries()}). */
    public ParsedTest(
            String className,
            String methodName,
            TestStatus status,
            long durationMs,
            String failureType,
            String failureMessage,
            String stackTrace,
            String stdout,
            String sourceFileHint,
            int sourceLineHint) {
        this(
                className,
                methodName,
                status,
                durationMs,
                failureType,
                failureMessage,
                stackTrace,
                stdout,
                sourceFileHint,
                sourceLineHint,
                0);
    }

    /** A passing/simple result with no failure detail or source hints. */
    public static ParsedTest of(String className, String methodName, TestStatus status, long durationMs) {
        return new ParsedTest(className, methodName, status, durationMs, null, null, null, null, null, 0);
    }

    /** The stable tree id for this test: {@code <className>#<methodName>}. */
    public String id() {
        return className + "#" + methodName;
    }
}
