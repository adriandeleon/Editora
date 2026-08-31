package com.editora.test;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What the Test Results tree shows: which status buckets are visible, plus a case-insensitive substring
 * query over a test's own name. Pure and toolkit-free — {@code TestRunnerPanel} only wires it to the tree
 * sync. Immutable; the panel swaps in a new instance whenever a chip or the filter field changes.
 *
 * <p>Three buckets, not five statuses, because {@code FAILED} and {@code ERROR} are one thing to the reader
 * (the header's "N failed" chip already tallies them together) — and {@link TestStatus#RUNNING} has no
 * bucket at all: a not-yet-settled test is <em>always</em> shown, or a filter applied mid-run would blank
 * the tree exactly while the results the user is waiting for stream in.
 *
 * <p>{@link #acceptsSuite} is what makes the filter useful on a big run: a suite whose tests are all
 * filtered out is itself hidden. Without that, hiding passed tests in a 4,000-test run still leaves every
 * class row on screen and the one failure is no easier to find than before.
 */
public record TestFilter(Set<Bucket> buckets, String query) {

    /** The status groups the panel offers as chips. */
    public enum Bucket {
        PASSED,
        FAILED,
        SKIPPED
    }

    /** Everything visible, no query — the default, and what "clear filter" restores. */
    public static final TestFilter ALL = new TestFilter(EnumSet.allOf(Bucket.class), "");

    public TestFilter {
        Set<Bucket> copy = EnumSet.noneOf(Bucket.class);
        if (buckets != null) {
            copy.addAll(buckets);
        }
        buckets = copy;
        query = query == null ? "" : query.strip();
    }

    /** Failures only, no query — the one-click "where is my failing test" filter. */
    public static TestFilter failedOnly() {
        return new TestFilter(EnumSet.of(Bucket.FAILED), "");
    }

    /** The bucket a status belongs to, or {@code null} for {@link TestStatus#RUNNING} (never filtered). */
    public static Bucket bucketOf(TestStatus status) {
        return switch (status) {
            case PASSED -> Bucket.PASSED;
            case FAILED, ERROR -> Bucket.FAILED;
            case SKIPPED -> Bucket.SKIPPED;
            case RUNNING -> null;
        };
    }

    public boolean shows(Bucket bucket) {
        return buckets.contains(bucket);
    }

    /** This filter with one bucket switched on or off. */
    public TestFilter with(Bucket bucket, boolean on) {
        Set<Bucket> next = EnumSet.noneOf(Bucket.class);
        next.addAll(buckets);
        if (on) {
            next.add(bucket);
        } else {
            next.remove(bucket);
        }
        return new TestFilter(next, query);
    }

    /** This filter with a different query. */
    public TestFilter withQuery(String query) {
        return new TestFilter(buckets, query);
    }

    /** Whether anything is being hidden — i.e. whether the panel should say so. */
    public boolean isActive() {
        return buckets.size() != Bucket.values().length || !query.isEmpty();
    }

    /** Whether a test leaf is shown: its bucket is on (or it is still running) and its name matches. */
    public boolean acceptsTest(TestNode test) {
        if (test == null) {
            return false;
        }
        Bucket bucket = bucketOf(test.status());
        if (bucket != null && !buckets.contains(bucket)) {
            return false;
        }
        return matchesQuery(test);
    }

    /** Whether a suite row is shown: only when at least one of its tests survives the filter. */
    public boolean acceptsSuite(TestNode suite) {
        if (suite == null) {
            return false;
        }
        for (TestNode child : suite.children()) {
            if (acceptsTest(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesQuery(TestNode test) {
        if (query.isEmpty()) {
            return true;
        }
        String q = query.toLowerCase(Locale.ROOT);
        // The suite name counts too: typing a class name should keep that class's tests, which is how the
        // user thinks about "filter to FooTest" even though the row they see is the method.
        return contains(test.displayName(), q)
                || contains(test.methodName(), q)
                || contains(test.className(), q)
                || (test.parent() != null && contains(test.parent().displayName(), q));
    }

    private static boolean contains(String haystack, String lowerNeedle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(lowerNeedle);
    }
}
