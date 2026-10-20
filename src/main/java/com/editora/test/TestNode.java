package com.editora.test;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A node in the test-results tree: the {@code ROOT}, a {@code SUITE} grouping (class/package/crate), or a
 * {@code TEST} leaf. Deliberately a mutable class (not a record) because a live run updates a node's
 * {@code status}/{@code durationMs} in place as results stream in; all mutation happens on the FX thread.
 * Pure — no toolkit imports. The text a node holds (output, failure message, stack trace) is bounded by
 * {@link OutputCap}: a test that prints by the megabyte keeps the head and the tail of it.
 */
public final class TestNode {

    private final TestNodeKind kind;
    private final @Nullable String id; // stable key: suite = its name; test = "<className>#<methodName>"
    private @Nullable String displayName;
    private TestStatus status = TestStatus.RUNNING;
    private long durationMs;
    private @Nullable String failureType;
    private @Nullable String failureMessage;
    private @Nullable String stackTrace;
    private @Nullable String stdout;
    private @Nullable String stderr;
    private @Nullable String sourceFileHint; // bare/relative name, e.g. "FooTest.java" / "foo_test.go"; may be null
    private int sourceLineHint; // 1-based; 0 = unknown
    private @Nullable String className; // for a TEST leaf: the owning class/package (for source navigation)
    private @Nullable String methodName; // for a TEST leaf: the method/case name (for rerun-failed + navigation)

    private final List<TestNode> children = new ArrayList<>();
    private @Nullable TestNode parent;

    public TestNode(TestNodeKind kind, @Nullable String id, @Nullable String displayName) {
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

    public @Nullable String id() {
        return id;
    }

    public @Nullable String displayName() {
        return displayName;
    }

    public void setDisplayName(@Nullable String displayName) {
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

    public @Nullable String failureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = failureType;
    }

    public @Nullable String failureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = OutputCap.cap(failureMessage);
    }

    public @Nullable String stackTrace() {
        return stackTrace;
    }

    public void setStackTrace(String stackTrace) {
        this.stackTrace = OutputCap.cap(stackTrace);
    }

    public @Nullable String stdout() {
        return stdout;
    }

    public void setStdout(String stdout) {
        this.stdout = OutputCap.cap(stdout);
    }

    public @Nullable String stderr() {
        return stderr;
    }

    public void setStderr(String stderr) {
        this.stderr = OutputCap.cap(stderr);
    }

    public @Nullable String sourceFileHint() {
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

    public @Nullable String className() {
        return className;
    }

    public void setClassName(@Nullable String className) {
        this.className = className;
    }

    public @Nullable String methodName() {
        return methodName;
    }

    public void setMethodName(@Nullable String methodName) {
        this.methodName = methodName;
    }

    public List<TestNode> children() {
        return children;
    }

    public @Nullable TestNode parent() {
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
    public @Nullable TestNode childById(@Nullable String childId) {
        for (TestNode c : children) {
            if (java.util.Objects.equals(c.id, childId)) {
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

    /**
     * Removes {@code TEST} leaves still {@code RUNNING} (pre-seeded placeholders that never got a result — a
     * parameterized method's plain-name seed, a filtered-out class), then drops any suite left with no
     * children. Called once when a run finishes so the tree shows only tests that actually ran. Returns whether
     * anything was removed.
     */
    public boolean pruneRunning() {
        boolean removed = false;
        for (var it = children.iterator(); it.hasNext(); ) {
            TestNode child = it.next();
            if (child.kind == TestNodeKind.TEST) {
                if (child.status == TestStatus.RUNNING) {
                    it.remove();
                    removed = true;
                }
            } else {
                removed |= child.pruneRunning();
                if (child.children.isEmpty()) {
                    it.remove();
                    removed = true;
                }
            }
        }
        return removed;
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
