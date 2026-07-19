package com.editora.test;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure coverage of the tree node: roll-up severity, tally, and failed-leaf collection. */
class TestNodeTest {

    private static TestNode leaf(String id, TestStatus status) {
        TestNode n = new TestNode(TestNodeKind.TEST, id, id);
        n.setStatus(status);
        n.setMethodName(id);
        return n;
    }

    @Test
    void worstSurfacesFailureButKeepsRunningOverPass() {
        assertEquals(TestStatus.FAILED, TestStatus.worst(TestStatus.RUNNING, TestStatus.FAILED));
        assertEquals(TestStatus.ERROR, TestStatus.worst(TestStatus.FAILED, TestStatus.ERROR));
        assertEquals(TestStatus.RUNNING, TestStatus.worst(TestStatus.RUNNING, TestStatus.PASSED));
        assertEquals(TestStatus.PASSED, TestStatus.worst(TestStatus.PASSED, TestStatus.SKIPPED));
    }

    @Test
    void rollUpAndTally() {
        TestNode root = TestNode.root();
        TestNode suite = root.addChild(new TestNode(TestNodeKind.SUITE, "S", "S"));
        suite.addChild(leaf("a", TestStatus.PASSED));
        suite.addChild(leaf("b", TestStatus.FAILED));
        suite.addChild(leaf("c", TestStatus.SKIPPED));

        assertEquals(TestStatus.FAILED, root.rollUp());
        TestCounts counts = root.tally();
        assertEquals(3, counts.total());
        assertEquals(1, counts.passed());
        assertEquals(1, counts.failed());
        assertEquals(1, counts.skipped());
        assertTrue(counts.anyFailed());
        assertEquals(3, counts.finished()); // running=0 → all three finished
    }

    @Test
    void runningSuiteRollsUpRunning() {
        TestNode root = TestNode.root();
        TestNode suite = root.addChild(new TestNode(TestNodeKind.SUITE, "S", "S"));
        suite.addChild(leaf("a", TestStatus.PASSED));
        suite.addChild(leaf("b", TestStatus.RUNNING));
        assertEquals(TestStatus.RUNNING, root.rollUp());
    }

    @Test
    void failedLeaves() {
        TestNode root = TestNode.root();
        TestNode suite = root.addChild(new TestNode(TestNodeKind.SUITE, "S", "S"));
        suite.addChild(leaf("a", TestStatus.PASSED));
        TestNode b = leaf("b", TestStatus.ERROR);
        suite.addChild(b);
        assertEquals(1, root.failedLeaves().size());
        assertEquals("b", root.failedLeaves().get(0).id());
    }
}
