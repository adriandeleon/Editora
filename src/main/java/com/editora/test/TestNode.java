package com.editora.test;

import java.util.ArrayList;
import java.util.List;

/**
 * A node in the test-results tree: the {@code ROOT}, a {@code SUITE} grouping (class/package/crate), or a
 * {@code TEST} leaf. Deliberately a mutable class (not a record) because a live run updates a node's
 * {@code status}/{@code durationMs} in place as results stream in; all mutation happens on the FX thread.
 * Pure — no toolkit imports.
 */
public final class TestNode {

    private final TestNodeKind kind;
    private final String id; // stable key: suite = its name; test = "<className>#<methodName>"
    private String displayName;
    private TestStatus status = TestStatus.RUNNING;
    private long durationMs;
    private String failureType;
    private String failureMessage;
    private String stackTrace;
    private String stdout;
    private String stderr;
    private String sourceFileHint; // bare/relative name, e.g. "FooTest.java" / "foo_test.go"; may be null
    private int sourceLineHint; // 1-based; 0 = unknown
    private String className; // for a TEST leaf: the owning class/package (for source navigation)
    private String methodName; // for a TEST leaf: the method/case name (for rerun-failed + navigation)

    private final List<TestNode> children = new ArrayList<>();
    private TestNode parent;

    public TestNode(TestNodeKind kind, String id, String displayName) {
        this.kind = kind;
        this.id = id;
        this.displayName = displayName;
    }

    public static TestNode root() {
        return new TestNode(TestNodeKind.ROOT, "", "");
    }

    public TestNodeKind kind() {
        return kind;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public TestStatus status() {
        return status;
    }

    public void setStatus(TestStatus status) {
        this.status = status;
    }

    public long durationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String failureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public String failureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
    }

    public String stackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = stackTrace;
    }

    public String stdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = stdout;
    }

    public String stderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = stderr;
    }

    public String sourceFileHint() {
        return sourceFileHint;
    }

    public void setSourceFileHint(String sourceFileHint) {
        this.sourceFileHint = sourceFileHint;
    }

    public int sourceLineHint() {
        return sourceLineHint;
    }

    public void setSourceLineHint(int sourceLineHint) {
        this.sourceLineHint = sourceLineHint;
    }

    public String className() {
        return className;
    }

    public void setClassName(String className) {
        this.className = className;
    }

    public String methodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public List<TestNode> children() {
        return children;
    }

    public TestNode parent() {
        return parent;
    }

    public boolean isLeaf() {
        return children.isEmpty();
    }

    /** Appends {@code child} (setting its parent) and returns it. */
    public TestNode addChild(TestNode child) {
        child.parent = this;
        children.add(child);
        return child;
    }

    /** The direct child with the given id, or {@code null}. */
    public TestNode childById(String childId) {
        for (TestNode c : children) {
            if (c.id.equals(childId)) {
                return c;
            }
        }
        return null;
    }

    /** Recursively counts the {@code TEST} leaves under this node by status. */
    public TestCounts tally() {
        return tallyInto(TestCounts.ZERO);
    }

    private TestCounts tallyInto(TestCounts acc) {
        if (kind == TestNodeKind.TEST) {
            return acc.plus(status);
        }
        TestCounts result = acc;
        for (TestNode c : children) {
            result = c.tallyInto(result);
        }
        return result;
    }

    /**
     * Recomputes this node's status from its leaves (via {@link TestStatus#worst}) and returns it. A leaf keeps
     * its own status. Call after merging parsed results so suite/root statuses reflect their children.
     */
    public TestStatus rollUp() {
        if (kind == TestNodeKind.TEST || children.isEmpty()) {
            return status;
        }
        TestStatus rolled = null;
        for (TestNode c : children) {
            TestStatus childStatus = c.rollUp();
            rolled = rolled == null ? childStatus : TestStatus.worst(rolled, childStatus);
        }
        status = rolled == null ? TestStatus.SKIPPED : rolled;
        return status;
    }

    /** Depth-first collection of all {@code TEST} leaves whose status {@link TestStatus#isFailure()}. */
    public List<TestNode> failedLeaves() {
        List<TestNode> out = new ArrayList<>();
        collectFailed(out);
        return out;
    }

    private void collectFailed(List<TestNode> out) {
        if (kind == TestNodeKind.TEST) {
            if (status.isFailure()) {
                out.add(this);
            }
            return;
        }
        for (TestNode c : children) {
            c.collectFailed(out);
        }
    }
}
