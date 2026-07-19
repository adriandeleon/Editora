package com.editora.test;

/**
 * A parser's neutral result for one test case. {@code className} is the owning grouping (JUnit FQN class / Go
 * package / Cargo crate / TAP file) and doubles as the suite key; {@code methodName} is the case name.
 * {@code sourceFileHint}/{@code sourceLineHint} help jump-to-source ({@code null}/0 when unknown). Pure data.
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
        int sourceLineHint) {

    /** A passing/simple result with no failure detail or source hints. */
    public static ParsedTest of(String className, String methodName, TestStatus status, long durationMs) {
        return new ParsedTest(className, methodName, status, durationMs, null, null, null, null, null, 0);
    }

    /** The stable tree id for this test: {@code <className>#<methodName>}. */
    public String id() {
        return className + "#" + methodName;
    }
}
