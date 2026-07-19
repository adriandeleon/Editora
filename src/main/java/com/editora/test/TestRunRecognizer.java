package com.editora.test;

import java.util.ArrayList;
import java.util.List;

import com.editora.build.BuildTool;

/**
 * Decides whether a build invocation is a test run (so the coordinator routes it to the Test Results window)
 * and augments the argv where a structured stream needs a flag. Matches the exact task args the
 * {@code *ActionsProvider}s emit: Maven {@code [test]}/{@code [verify]}, Gradle {@code [test]}/{@code [check]},
 * npm {@code [run, test…]}/{@code [test]}, Cargo {@code [test]}, Go {@code [test, ./...]}. Pure.
 */
public final class TestRunRecognizer {

    private TestRunRecognizer() {}

    public static boolean isTestRun(BuildTool tool, List<String> taskArgs) {
        if (taskArgs == null || taskArgs.isEmpty()) {
            return false;
        }
        return switch (tool) {
            case MAVEN ->
                taskArgs.contains("test") || taskArgs.contains("verify") || taskArgs.contains("integration-test");
            case GRADLE -> taskArgs.stream().anyMatch(TestRunRecognizer::isGradleTestTask);
            case NPM -> isNpmTest(taskArgs);
            case CARGO -> "test".equals(firstNonFlag(taskArgs));
            case GO -> "test".equals(taskArgs.get(0));
        };
    }

    /**
     * For Go, insert {@code -json} right after the {@code test} token so the run emits structured events
     * ({@link GoTestJsonParser}); other tools are unchanged. Idempotent.
     */
    public static List<String> augmentArgv(BuildTool tool, List<String> argv) {
        if (tool != BuildTool.GO || argv.contains("-json")) {
            return argv;
        }
        int idx = argv.indexOf("test");
        if (idx < 0) {
            return argv;
        }
        List<String> out = new ArrayList<>(argv);
        out.add(idx + 1, "-json");
        return out;
    }

    /**
     * The task args that run one test class ({@code methodName == null}) or one method — the gutter ▶ / run-at-
     * caret dispatch. Only the JVM tools have a usable per-test filter; Go/Cargo in-file runs are out of scope
     * (returns an empty list). Maven's {@code -Dtest} matches the <b>simple</b> class name (like
     * {@link TestRun#failedTestFilters}); Gradle's {@code --tests} takes the FQN. {@code -DfailIfNoTests=false}
     * keeps a reactor build from failing the modules that don't hold the target.
     */
    public static List<String> singleTestTask(BuildTool tool, String className, String methodName) {
        return switch (tool) {
            case MAVEN -> {
                String cls = TestSourceLocator.simpleName(className);
                String sel = methodName == null ? cls : cls + "#" + methodName;
                yield List.of("test", "-Dtest=" + sel, "-DfailIfNoTests=false");
            }
            case GRADLE -> List.of("test", "--tests", methodName == null ? className : className + "." + methodName);
            default -> List.of();
        };
    }

    /** The canonical {@code test} task args for a tool (what the {@code test.run} command launches). */
    public static List<String> defaultTestTask(BuildTool tool) {
        return switch (tool) {
            case MAVEN, GRADLE, CARGO -> List.of("test");
            case NPM -> List.of("run", "test");
            case GO -> List.of("test", "./...");
        };
    }

    private static boolean isGradleTestTask(String t) {
        if (t.startsWith("-")) {
            return false;
        }
        return t.equals("test") || t.equals("check") || t.endsWith("Test") || t.endsWith("test");
    }

    private static boolean isNpmTest(List<String> a) {
        if (a.size() >= 2 && a.get(0).equals("run") && a.get(1).startsWith("test")) {
            return true;
        }
        return a.size() == 1 && a.get(0).equals("test");
    }

    private static String firstNonFlag(List<String> a) {
        for (String t : a) {
            if (!t.startsWith("-")) {
                return t;
            }
        }
        return "";
    }
}
