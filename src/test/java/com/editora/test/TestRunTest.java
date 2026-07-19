package com.editora.test;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rerun-failed argv per tool + elapsed timing. */
class TestRunTest {

    private static TestRun runWithFailures(BuildTool tool) {
        TestRun run = new TestRun(tool, Path.of("."), List.of("test"), List.of(), 1_000L);
        TestTreeBuilder.merge(
                run.root(),
                new ParsedSuite(
                        "com.x.FooTest",
                        List.of(
                                ParsedTest.of("com.x.FooTest", "TestBar", TestStatus.FAILED, 1),
                                ParsedTest.of("com.x.FooTest", "TestOk", TestStatus.PASSED, 1))));
        return run;
    }

    @Test
    void mavenFilter() {
        assertEquals(
                List.of("test", "-Dtest=FooTest#TestBar"),
                runWithFailures(BuildTool.MAVEN).failedTestFilters());
    }

    @Test
    void gradleFilter() {
        assertEquals(
                List.of("test", "--tests", "com.x.FooTest.TestBar"),
                runWithFailures(BuildTool.GRADLE).failedTestFilters());
    }

    @Test
    void goFilter() {
        assertEquals(
                List.of("test", "-run", "^(TestBar)$", "./..."),
                runWithFailures(BuildTool.GO).failedTestFilters());
    }

    @Test
    void cargoFilter() {
        assertEquals(
                List.of("test", "--", "TestBar"),
                runWithFailures(BuildTool.CARGO).failedTestFilters());
    }

    @Test
    void npmUnsupportedFallsBackToEmpty() {
        assertTrue(runWithFailures(BuildTool.NPM).failedTestFilters().isEmpty());
    }

    @Test
    void noFailuresYieldsEmpty() {
        TestRun run = new TestRun(BuildTool.MAVEN, Path.of("."), List.of("test"), List.of(), 0L);
        TestTreeBuilder.merge(run.root(), new ParsedSuite("S", List.of(ParsedTest.of("S", "a", TestStatus.PASSED, 1))));
        assertTrue(run.failedTestFilters().isEmpty());
    }

    @Test
    void elapsedUsesFinishTimeOnceFinished() {
        TestRun run = new TestRun(BuildTool.MAVEN, Path.of("."), List.of("test"), List.of(), 1_000L);
        assertEquals(500, run.elapsedMillis(1_500L));
        run.finish(0, 3_000L);
        assertEquals(2_000, run.elapsedMillis(9_999L));
        assertTrue(!run.isRunning());
    }
}
