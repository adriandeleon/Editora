package com.editora.test;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Merge idempotency + incremental streaming updates. */
class TestTreeBuilderTest {

    @Test
    void reMergeDoesNotDuplicate() {
        TestNode root = TestNode.root();
        ParsedSuite suite =
                new ParsedSuite("com.x.FooTest", List.of(ParsedTest.of("com.x.FooTest", "a", TestStatus.PASSED, 5)));
        TestTreeBuilder.merge(root, suite);
        TestTreeBuilder.merge(root, suite); // JVM final sweep re-reads the same file
        assertEquals(1, root.children().size());
        assertEquals(1, root.children().get(0).children().size());
    }

    @Test
    void streamingTransitionUpdatesLeafInPlace() {
        TestNode root = TestNode.root();
        TestTreeBuilder.merge(
                root, new ParsedSuite("pkg", List.of(ParsedTest.of("pkg", "TestA", TestStatus.RUNNING, 0))));
        TestTreeBuilder.merge(
                root, new ParsedSuite("pkg", List.of(ParsedTest.of("pkg", "TestA", TestStatus.PASSED, 12))));
        TestNode leaf = root.childById("pkg").childById("pkg#TestA");
        assertNotNull(leaf);
        assertEquals(TestStatus.PASSED, leaf.status());
        assertEquals(12, leaf.durationMs());
        assertEquals(1, root.childById("pkg").children().size());
    }

    @Test
    void laterDeltaDoesNotWipeCapturedFailureDetail() {
        TestNode root = TestNode.root();
        TestTreeBuilder.merge(
                root,
                new ParsedSuite(
                        "S",
                        List.of(new ParsedTest(
                                "S", "t", TestStatus.FAILED, 1, "AssertionError", "boom", "at X", null, null, 0))));
        // A subsequent delta with no detail (e.g. a re-observed pass in a flaky stream) must not null the message.
        TestTreeBuilder.merge(root, new ParsedSuite("S", List.of(ParsedTest.of("S", "t", TestStatus.PASSED, 1))));
        TestNode leaf = root.childById("S").childById("S#t");
        assertEquals(TestStatus.PASSED, leaf.status());
        assertEquals("boom", leaf.failureMessage());
    }

    @Test
    void seedAddsPendingRunningLeavesButNeverDowngradesAResult() {
        TestNode root = TestNode.root();
        // A fast class reported PASSED before the seed task ran.
        TestTreeBuilder.merge(root, new ParsedSuite("S", List.of(ParsedTest.of("S", "fast", TestStatus.PASSED, 3))));
        // Seed the class's expected tests (both "fast" and a not-yet-run "slow").
        TestTreeBuilder.seed(
                root,
                new ParsedSuite(
                        "S",
                        List.of(
                                ParsedTest.of("S", "fast", TestStatus.RUNNING, 0),
                                ParsedTest.of("S", "slow", TestStatus.RUNNING, 0))));
        TestNode suite = root.childById("S");
        assertEquals(2, suite.children().size());
        assertEquals(TestStatus.PASSED, suite.childById("S#fast").status(), "seed must not downgrade a result");
        assertEquals(TestStatus.RUNNING, suite.childById("S#slow").status(), "unseen test seeded pending");
        // Its later report flips the pending leaf.
        TestTreeBuilder.merge(root, new ParsedSuite("S", List.of(ParsedTest.of("S", "slow", TestStatus.FAILED, 9))));
        assertEquals(TestStatus.FAILED, suite.childById("S#slow").status());
    }

    @Test
    void pruneRunningDropsUnreportedSeedsAndEmptySuites() {
        TestNode root = TestNode.root();
        TestTreeBuilder.merge(root, new ParsedSuite("A", List.of(ParsedTest.of("A", "ran", TestStatus.PASSED, 1))));
        TestTreeBuilder.seed(root, new ParsedSuite("A", List.of(ParsedTest.of("A", "ghost", TestStatus.RUNNING, 0))));
        TestTreeBuilder.seed(root, new ParsedSuite("B", List.of(ParsedTest.of("B", "never", TestStatus.RUNNING, 0))));
        assertEquals(2, root.children().size());

        assertTrue(root.pruneRunning());
        assertEquals(1, root.children().size(), "suite B (all pending) removed");
        TestNode a = root.childById("A");
        assertEquals(1, a.children().size(), "ghost pending leaf removed, real result kept");
        assertNull(a.childById("A#ghost"));
        assertEquals(TestStatus.PASSED, a.childById("A#ran").status());
    }

    @Test
    void unknownChild() {
        TestNode root = TestNode.root();
        assertNull(root.childById("nope"));
    }
}
