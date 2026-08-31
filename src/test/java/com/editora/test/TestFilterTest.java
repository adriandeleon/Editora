package com.editora.test;

import java.util.List;

import com.editora.test.TestFilter.Bucket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Test Results filter: status buckets, the name query, and the suite roll-up that hides empty classes. */
class TestFilterTest {

    private static TestNode tree() {
        TestNode root = TestNode.root();
        TestTreeBuilder.merge(
                root,
                new ParsedSuite(
                        "com.x.FooTest",
                        List.of(
                                ParsedTest.of("com.x.FooTest", "passes", TestStatus.PASSED, 3),
                                ParsedTest.of("com.x.FooTest", "skips", TestStatus.SKIPPED, 0))));
        TestTreeBuilder.merge(
                root,
                new ParsedSuite(
                        "com.x.BarTest",
                        List.of(
                                ParsedTest.of("com.x.BarTest", "breaks", TestStatus.FAILED, 7),
                                ParsedTest.of("com.x.BarTest", "explodes", TestStatus.ERROR, 7))));
        return root;
    }

    private static TestNode suite(TestNode root, String id) {
        return root.childById(id);
    }

    private static TestNode test(TestNode root, String suiteId, String testId) {
        return suite(root, suiteId).childById(testId);
    }

    @Test
    void allShowsEverything() {
        TestNode root = tree();
        assertFalse(TestFilter.ALL.isActive());
        for (TestNode suite : root.children()) {
            assertTrue(TestFilter.ALL.acceptsSuite(suite));
            for (TestNode t : suite.children()) {
                assertTrue(TestFilter.ALL.acceptsTest(t));
            }
        }
    }

    @Test
    void errorSharesTheFailedBucket() {
        assertEquals(Bucket.FAILED, TestFilter.bucketOf(TestStatus.FAILED));
        assertEquals(Bucket.FAILED, TestFilter.bucketOf(TestStatus.ERROR));
        assertEquals(Bucket.PASSED, TestFilter.bucketOf(TestStatus.PASSED));
        assertEquals(Bucket.SKIPPED, TestFilter.bucketOf(TestStatus.SKIPPED));
    }

    @Test
    void aRunningTestHasNoBucketAndSurvivesEveryStatusFilter() {
        assertNull(TestFilter.bucketOf(TestStatus.RUNNING), "RUNNING is never a filterable bucket");
        TestNode root = TestNode.root();
        TestTreeBuilder.merge(
                root, new ParsedSuite("pkg", List.of(ParsedTest.of("pkg", "TestA", TestStatus.RUNNING, 0))));
        TestNode running = test(root, "pkg", "pkg#TestA");
        // Nothing selected at all — a mid-run filter must still show what hasn't settled yet.
        TestFilter nothing = TestFilter.ALL
                .with(Bucket.PASSED, false)
                .with(Bucket.FAILED, false)
                .with(Bucket.SKIPPED, false);
        assertTrue(nothing.acceptsTest(running));
        assertTrue(nothing.acceptsSuite(suite(root, "pkg")));
    }

    @Test
    void failedOnlyHidesTheAllPassingSuiteEntirely() {
        TestNode root = tree();
        TestFilter f = TestFilter.failedOnly();
        assertTrue(f.isActive());
        // The point of the feature: a class with nothing left to show goes with its tests.
        assertFalse(f.acceptsSuite(suite(root, "com.x.FooTest")), "an all-passing class is hidden, not left empty");
        assertTrue(f.acceptsSuite(suite(root, "com.x.BarTest")));
        assertTrue(f.acceptsTest(test(root, "com.x.BarTest", "com.x.BarTest#breaks")));
        assertTrue(f.acceptsTest(test(root, "com.x.BarTest", "com.x.BarTest#explodes")), "ERROR counts as failed");
        assertFalse(f.acceptsTest(test(root, "com.x.FooTest", "com.x.FooTest#skips")));
    }

    @Test
    void hidingPassedKeepsSkipped() {
        TestNode root = tree();
        TestFilter f = TestFilter.ALL.with(Bucket.PASSED, false);
        assertFalse(f.acceptsTest(test(root, "com.x.FooTest", "com.x.FooTest#passes")));
        assertTrue(f.acceptsTest(test(root, "com.x.FooTest", "com.x.FooTest#skips")));
        assertTrue(f.acceptsSuite(suite(root, "com.x.FooTest")), "the class stays for its skipped test");
    }

    @Test
    void queryMatchesMethodClassAndOwningSuite() {
        TestNode root = tree();
        assertTrue(TestFilter.ALL.withQuery("BREAK").acceptsTest(test(root, "com.x.BarTest", "com.x.BarTest#breaks")));
        // Typing a class name keeps that class's tests, even though the row shows only the method.
        TestFilter byClass = TestFilter.ALL.withQuery("bartest");
        assertTrue(byClass.acceptsSuite(suite(root, "com.x.BarTest")));
        assertFalse(byClass.acceptsSuite(suite(root, "com.x.FooTest")));
        assertFalse(TestFilter.ALL.withQuery("nothing-matches").acceptsSuite(suite(root, "com.x.BarTest")));
    }

    @Test
    void blankQueryIsNotAFilter() {
        assertFalse(TestFilter.ALL.withQuery("   ").isActive(), "whitespace is not a query");
        assertEquals("x", TestFilter.ALL.withQuery("  x  ").query(), "a query is stripped");
        assertTrue(TestFilter.ALL.withQuery(" x ").isActive());
    }

    @Test
    void filtersAreImmutable() {
        TestFilter f = TestFilter.ALL;
        TestFilter narrowed = f.with(Bucket.PASSED, false).withQuery("foo");
        assertTrue(f.shows(Bucket.PASSED), "the original is untouched");
        assertEquals("", f.query());
        assertFalse(narrowed.shows(Bucket.PASSED));
        assertEquals("foo", narrowed.query());
    }
}
