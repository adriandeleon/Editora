package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import com.editora.run.StackTraceLinks;
import com.editora.test.TestNode;
import com.editora.test.TestRun;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A Gradle {@code test} task that is up to date exits 0 without rewriting a single report. Every report on
 * disk then equals its pre-run baseline, the run treated them all as leftovers, and the Test Results tree
 * came up empty for a build that had just "passed".
 */
@Tag("fx")
class TestRunCoordinatorUpToDateFxTest {

    private static final String REPORT = """
            <?xml version="1.0" encoding="UTF-8"?>
            <testsuite name="com.x.FooTest" tests="2" failures="0" errors="0" skipped="0" time="0.02">
              <testcase name="works" classname="com.x.FooTest" time="0.01"/>
              <testcase name="alsoWorks" classname="com.x.FooTest" time="0.01"/>
            </testsuite>
            """;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class RecordingHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();

        RecordingHost() {
            settings.setTestRunner(true);
        }

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private static final class Ops implements TestRunCoordinator.Ops {
        @Override
        public void openTestResults() {}

        @Override
        public void setTestResultsAvailable(boolean available) {}

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public void jumpToTest(TestNode node, BuildTool tool) {}

        @Override
        public void runTest(BuildTool tool, Path root, List<String> taskArgs, List<String> toggleArgs) {}

        @Override
        public void stopTest(BuildTool tool) {}

        @Override
        public boolean debugAvailable() {
            return false;
        }

        @Override
        public void attachDebugger(String className, String host, int port) {}
    }

    /** Runs a whole (instant) test run that writes no report, and returns the finished run's leaf names. */
    private static List<String> runThatRewritesNothing(RecordingHost host, BuildTool tool, Path dir, int exitCode)
            throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.callOnFx(() -> new TestRunCoordinator(host, new Ops()));
        try {
            // "--tests" / "-Dtest" so the run is filtered and no pending tree is pre-seeded from sources.
            List<String> task = tool == BuildTool.GRADLE
                    ? List.of("test", "--tests", "com.x.FooTest")
                    : List.of("test", "-Dtest=FooTest");
            FxTestSupport.runOnFx(() -> {
                assertTrue(coordinator.onTestRunStart(tool, dir, task, List.of(), List.of("tool", "test")));
                coordinator.onTestExit(exitCode);
            });
            // The baseline, the final sweep and the completion are queued on the poller in that order: one
            // task behind them, then an FX barrier, and the run has finished — no polling, no sleeping.
            ScheduledExecutorService poller = FxTestSupport.field(coordinator, "poller");
            poller.submit(() -> {}).get(30, TimeUnit.SECONDS);
            FxTestSupport.runOnFx(() -> {});
            FxTestSupport.runOnFx(() -> {});

            TestRun run = FxTestSupport.callOnFx(() -> FxTestSupport.field(coordinator, "currentRun"));
            assertNotNull(run);
            assertFalse(FxTestSupport.callOnFx(run::isRunning), "the run completed");
            List<String> leaves = new ArrayList<>();
            FxTestSupport.runOnFx(() -> {
                TestNode suite = run.root().childById("com.x.FooTest");
                if (suite != null) {
                    suite.children().forEach(leaf -> leaves.add(leaf.displayName()));
                }
            });
            return leaves;
        } finally {
            FxTestSupport.runOnFx(coordinator::shutdown);
        }
    }

    @Test
    void anUpToDateGradleRunShowsTheReportsItWouldHaveRewritten(@TempDir Path dir) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("build/test-results/test"));
        Files.writeString(reports.resolve("TEST-com.x.FooTest.xml"), REPORT);
        RecordingHost host = new RecordingHost();

        List<String> leaves = runThatRewritesNothing(host, BuildTool.GRADLE, dir, 0);

        assertEquals(2, leaves.size(), "the existing results are shown instead of an empty tree: " + leaves);
        assertTrue(leaves.stream().anyMatch(n -> n.contains("works")));
        assertTrue(
                host.statuses.contains(tr("status.testrunner.upToDate")), "and the status says where they came from");
    }

    @Test
    void aFailedGradleBuildDoesNotPassOffOldReportsAsItsResult(@TempDir Path dir) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("build/test-results/test"));
        Files.writeString(reports.resolve("TEST-com.x.FooTest.xml"), REPORT);
        RecordingHost host = new RecordingHost();

        assertTrue(runThatRewritesNothing(host, BuildTool.GRADLE, dir, 1).isEmpty(), "a compile error ran no tests");
        assertFalse(host.statuses.contains(tr("status.testrunner.upToDate")));
    }

    /** Surefire always re-runs: an untouched Maven report is another run's leftover, as it always was. */
    @Test
    void aMavenRunStillIgnoresLeftoverReports(@TempDir Path dir) throws Exception {
        Path reports = Files.createDirectories(dir.resolve("target/surefire-reports"));
        Files.writeString(reports.resolve("TEST-com.x.FooTest.xml"), REPORT);
        RecordingHost host = new RecordingHost();

        assertTrue(runThatRewritesNothing(host, BuildTool.MAVEN, dir, 0).isEmpty());
        assertFalse(host.statuses.contains(tr("status.testrunner.upToDate")));
    }
}
