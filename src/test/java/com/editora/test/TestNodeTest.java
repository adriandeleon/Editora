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

    @Test
    void theTextANodeHoldsIsBounded() {
        TestNode n = leaf("chatty", TestStatus.FAILED);
        String huge = "o".repeat(OutputCap.MAX_CHARS * 3);
        n.setStdout(huge);
        n.setStderr(huge);
        n.setFailureMessage(huge);
        n.setStackTrace(huge);
        for (String kept : new String[] {n.stdout(), n.stderr(), n.failureMessage(), n.stackTrace()}) {
            assertTrue(kept.length() < OutputCap.MAX_CHARS + 200, "kept " + kept.length());
            assertTrue(kept.contains(OutputCap.note(huge.length() - OutputCap.MAX_CHARS)));
        }
        n.setStdout("short");
        assertEquals("short", n.stdout());
    }

    @Test
    void childByIdFindsTheFirstChildWithAnIdAndForgetsPrunedOnes() {
        TestNode suite = new TestNode(TestNodeKind.SUITE, "s", "s");
        for (int i = 0; i < 5_000; i++) {
            suite.addChild(new TestNode(TestNodeKind.TEST, "s#t" + i, "t" + i)).setStatus(TestStatus.PASSED);
        }
        TestNode first = suite.childById("s#t42");
        TestNode duplicate = suite.addChild(new TestNode(TestNodeKind.TEST, "s#t42", "again"));
        assertTrue(first != null && first == suite.childById("s#t42"), "the first one with that id");
        assertEquals(null, suite.childById("missing"));

        TestNode pending = suite.addChild(new TestNode(TestNodeKind.TEST, "s#pending", "pending")); // RUNNING
        duplicate.setStatus(TestStatus.PASSED);
        assertTrue(suite.pruneRunning());
        assertEquals(null, suite.childById("s#pending"), "a pruned child is not found");
        assertTrue(pending.parent() == suite);
        assertEquals(5_001, suite.children().size());
    }

    @Test
    void childrenAreReadOnlyOutsideAddChild() {
        TestNode suite = new TestNode(TestNodeKind.SUITE, "s", "s");
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> suite.children().add(new TestNode(TestNodeKind.TEST, "x", "x")));
    }

    @Test
    void tallyCountsEveryStatus() {
        TestNode root = TestNode.root();
        TestNode suite = root.addChild(new TestNode(TestNodeKind.SUITE, "s", "s"));
        TestStatus[] statuses = {
            TestStatus.PASSED,
            TestStatus.PASSED,
            TestStatus.FAILED,
            TestStatus.ERROR,
            TestStatus.SKIPPED,
            TestStatus.RUNNING
        };
        for (int i = 0; i < statuses.length; i++) {
            suite.addChild(new TestNode(TestNodeKind.TEST, "s#" + i, "t")).setStatus(statuses[i]);
        }
        assertEquals(new TestCounts(6, 2, 1, 1, 1, 1), root.tally());
    }
}
