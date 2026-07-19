package com.editora.test;

/**
 * Merges a {@link ParsedSuite} into the results tree. Idempotent: re-merging the same suite (the JVM final
 * sweep re-reads files already seen live) upserts by stable id rather than duplicating, and a streaming tool's
 * per-event delta (one test transitioning {@code RUNNING → PASSED}) updates that leaf in place. Pure.
 */
public final class TestTreeBuilder {

    private TestTreeBuilder() {}

    /** Upserts {@code suite} (a {@code SUITE} node keyed by name) and each of its tests under {@code root}. */
    public static void merge(TestNode root, ParsedSuite suite) {
        TestNode suiteNode = root.childById(suite.suiteName());
        if (suiteNode == null) {
            suiteNode = root.addChild(new TestNode(TestNodeKind.SUITE, suite.suiteName(), suite.suiteName()));
        }
        if (suite.suiteStdout() != null) {
            suiteNode.setStdout(suite.suiteStdout());
        }
        if (suite.suiteStderr() != null) {
            suiteNode.setStderr(suite.suiteStderr());
        }
        for (ParsedTest test : suite.tests()) {
            upsertTest(suiteNode, test);
        }
    }

    /**
     * Seeds pending placeholders: upserts the suite, then adds any test that isn't already present as a
     * {@code RUNNING} leaf — but <b>never touches an existing node</b>, so a result that arrived before the
     * seed (a fast class) is not downgraded back to RUNNING. Used to pre-populate the "grey" pending list.
     */
    public static void seed(TestNode root, ParsedSuite suite) {
        TestNode suiteNode = root.childById(suite.suiteName());
        if (suiteNode == null) {
            suiteNode = root.addChild(new TestNode(TestNodeKind.SUITE, suite.suiteName(), suite.suiteName()));
        }
        for (ParsedTest test : suite.tests()) {
            if (suiteNode.childById(test.id()) == null) {
                TestNode node = suiteNode.addChild(new TestNode(TestNodeKind.TEST, test.id(), test.methodName()));
                node.setStatus(TestStatus.RUNNING);
                node.setClassName(test.className());
                node.setMethodName(test.methodName());
            }
        }
    }

    private static void upsertTest(TestNode suiteNode, ParsedTest test) {
        TestNode node = suiteNode.childById(test.id());
        if (node == null) {
            node = suiteNode.addChild(new TestNode(TestNodeKind.TEST, test.id(), test.methodName()));
        }
        node.setDisplayName(test.methodName());
        node.setStatus(test.status());
        node.setDurationMs(test.durationMs());
        node.setClassName(test.className());
        node.setMethodName(test.methodName());
        // Only overwrite optional detail when the delta carries it, so a later RUNNING→PASSED delta that omits
        // the failure text does not wipe an earlier failure captured for the same id.
        if (test.failureType() != null) {
            node.setFailureType(test.failureType());
        }
        if (test.failureMessage() != null) {
            node.setFailureMessage(test.failureMessage());
        }
        if (test.stackTrace() != null) {
            node.setStackTrace(test.stackTrace());
        }
        if (test.stdout() != null) {
            node.setStdout(test.stdout());
        }
        if (test.sourceFileHint() != null) {
            node.setSourceFileHint(test.sourceFileHint());
        }
        if (test.sourceLineHint() > 0) {
            node.setSourceLineHint(test.sourceLineHint());
        }
    }
}
