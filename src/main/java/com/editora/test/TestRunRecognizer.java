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
     * {@link TestRun#failedTestFilters}); Gradle's {@code --tests} takes the FQN. The method is reduced to a
     * name a filter accepts ({@link TestSourceLocator#filterMethodName}); see {@link #mavenTestFilter} for
     * the reactor flags.
     */
    public static List<String> singleTestTask(BuildTool tool, String className, String methodName) {
        String method = TestSourceLocator.filterMethodName(methodName);
        boolean wholeClass = method == null || method.isEmpty();
        return switch (tool) {
            case MAVEN -> {
                String cls = TestSourceLocator.simpleName(className);
                yield mavenTestFilter(wholeClass ? cls : cls + "#" + method);
            }
            case GRADLE -> List.of("test", "--tests", wholeClass ? className : className + "." + method);
            default -> List.of();
        };
    }

    /**
     * The Maven task args that run only {@code selector} (a {@code -Dtest} value).
     *
     * <p>Both flags are needed in a reactor. {@code -DfailIfNoTests=false} covers a module with no tests at
     * all; {@code -Dsurefire.failIfNoSpecifiedTests=false} covers a module that has tests, none of which
     * match — without it Surefire fails the first such module with "No tests matching pattern" and the
     * module that does hold the target never runs.
     */
    public static List<String> mavenTestFilter(String selector) {
        return List.of(
                "test", "-Dtest=" + selector, "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false");
    }

    /**
     * Whether a finished JVM test run should show the reports already on disk. Gradle skips an up-to-date
     * {@code test} task: it exits 0 and rewrites nothing, so every report is "a leftover from before the
     * run" and the tree came up empty — while the previous results are, by Gradle's own up-to-date check,
     * exactly what running again would have produced. Maven is excluded: Surefire always re-runs, so an
     * untouched report there really is a leftover (e.g. another class's, under {@code -Dtest=Foo}).
     */
    public static boolean showsExistingReports(BuildTool tool, int exitCode, boolean anyReportWritten) {
        return tool == BuildTool.GRADLE && exitCode == 0 && !anyReportWritten;
    }

    /**
     * Whether the invocation targets a specific subset of tests (Maven {@code -Dtest=…}, Gradle
     * {@code --tests …}) rather than the whole suite. An unfiltered run is the one worth pre-seeding with the
     * full project test list; a filtered run only touches its target(s), so seeding everything would show a
     * misleading grey tree.
     */
    public static boolean isFilteredRun(BuildTool tool, List<String> taskArgs) {
        if (taskArgs == null) {
            return false;
        }
        return switch (tool) {
            case MAVEN -> taskArgs.stream().anyMatch(a -> a.startsWith("-Dtest="));
            case GRADLE -> taskArgs.contains("--tests");
            default -> false;
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
