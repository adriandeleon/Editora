package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.editora.build.BuildTool;

/**
 * One test-run session: the results {@link TestNode} tree plus the invocation that produced it (tool, working
 * dir, task/toggle args) and its timing. Owned + mutated on the FX thread by {@code ui.TestRunCoordinator}.
 */
public final class TestRun {

    private final TestNode root = TestNode.root();
    private final BuildTool tool;
    private final Path workingDir;
    private final List<String> taskArgs;
    private final List<String> toggleArgs;
    private final long startedAtMillis;
    private Long finishedAtMillis;
    private boolean running = true;
    private int exitCode;

    public TestRun(
            BuildTool tool, Path workingDir, List<String> taskArgs, List<String> toggleArgs, long startedAtMillis) {
        this.tool = tool;
        this.workingDir = workingDir;
        this.taskArgs = List.copyOf(taskArgs);
        this.toggleArgs = List.copyOf(toggleArgs);
        this.startedAtMillis = startedAtMillis;
    }

    public TestNode root() {
        return root;
    }

    public BuildTool tool() {
        return tool;
    }

    public Path workingDir() {
        return workingDir;
    }

    public List<String> taskArgs() {
        return taskArgs;
    }

    public List<String> toggleArgs() {
        return toggleArgs;
    }

    public boolean isRunning() {
        return running;
    }

    public int exitCode() {
        return exitCode;
    }

    public void finish(int exitCode, long finishedAtMillis) {
        this.running = false;
        this.exitCode = exitCode;
        this.finishedAtMillis = finishedAtMillis;
    }

    public long elapsedMillis(long nowMillis) {
        long end = finishedAtMillis != null ? finishedAtMillis : nowMillis;
        return Math.max(0, end - startedAtMillis);
    }

    /** Rolls up node statuses then tallies the leaves. */
    public TestCounts counts() {
        root.rollUp();
        return root.tally();
    }

    /**
     * The task args for a rerun limited to the failed tests, or an empty list when the tool can't target
     * individual tests (npm) — the caller then falls back to a full rerun. Pure; reads the current tree.
     */
    public List<String> failedTestFilters() {
        List<TestNode> failed = root.failedLeaves();
        if (failed.isEmpty()) {
            return List.of();
        }
        return switch (tool) {
            case MAVEN -> {
                Set<String> filters = new LinkedHashSet<>();
                for (TestNode t : failed) {
                    filters.add(TestSourceLocator.simpleName(t.className()) + "#" + t.methodName());
                }
                yield List.of("test", "-Dtest=" + String.join(",", filters));
            }
            case GRADLE -> {
                List<String> args = new ArrayList<>(List.of("test"));
                Set<String> seen = new LinkedHashSet<>();
                for (TestNode t : failed) {
                    if (seen.add(t.className() + "." + t.methodName())) {
                        args.add("--tests");
                        args.add(t.className() + "." + t.methodName());
                    }
                }
                yield List.copyOf(args);
            }
            case GO -> {
                Set<String> names = new LinkedHashSet<>();
                for (TestNode t : failed) {
                    names.add(t.methodName());
                }
                yield List.of("test", "-run", "^(" + String.join("|", names) + ")$", "./...");
            }
            case CARGO -> {
                List<String> args = new ArrayList<>(List.of("test", "--"));
                Set<String> seen = new LinkedHashSet<>();
                for (TestNode t : failed) {
                    if (seen.add(t.methodName())) {
                        args.add(t.methodName());
                    }
                }
                yield List.copyOf(args);
            }
            case NPM -> List.of(); // no universal per-test filter — caller does a full rerun
        };
    }
}
