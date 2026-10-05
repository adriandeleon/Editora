package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TreeView;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import com.editora.run.StackTraceLinks;
import com.editora.test.TestNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A test run whose process exits non-zero is a failed run even when no test case failed. The Test Results
 * header used to be computed from the parsed leaves alone, so a build failure read "N of N tests passed"
 * (or "0 of 0") in the very window that is fronted instead of the Output console.
 */
@Tag("fx")
class TestRunCoordinatorExitCodeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();

        Host() {
            settings.setTestRunner(true);
        }

        @Override
        public Settings settings() {
            return settings;
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

    /** Runs one whole run through the hook calls and returns the coordinator with its panel settled. */
    private static TestRunCoordinator run(BuildTool tool, Path dir, List<String> task, List<String> lines, int exit)
            throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.callOnFx(() -> new TestRunCoordinator(new Host(), new Ops()));
        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(tool, dir, task, List.of(), List.of("tool", "test")));
            for (String line : lines) {
                coordinator.onTestOutput(line, false);
            }
            coordinator.onTestExit(exit);
        });
        ScheduledExecutorService poller = FxTestSupport.field(coordinator, "poller");
        poller.submit(() -> {}).get(30, TimeUnit.SECONDS);
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {});
        return coordinator;
    }

    private static String header(TestRunCoordinator coordinator) throws Exception {
        Label status = FxTestSupport.field(coordinator.panel(), "status");
        return FxTestSupport.callOnFx(status::getText);
    }

    private static boolean failedStyle(TestRunCoordinator coordinator) throws Exception {
        ProgressBar progress = FxTestSupport.field(coordinator.panel(), "progress");
        return FxTestSupport.callOnFx(() -> progress.getStyleClass().contains("test-progress-failed"));
    }

    /** {@code mvn test} stopping at a compile error: no report is written and the build exits 1. */
    @Test
    void aMavenBuildFailureIsNotReportedAsZeroOfZeroPassed(@TempDir Path dir) throws Exception {
        TestRunCoordinator c = run(BuildTool.MAVEN, dir, List.of("test", "-Dtest=FooTest"), List.of(), 1);
        try {
            assertEquals(tr("testrunner.finishedAborted", 1), header(c));
            assertTrue(failedStyle(c));
        } finally {
            FxTestSupport.runOnFx(c::shutdown);
        }
    }

    /** Go: ex/p passes, ex/q does not compile (Go 1.24+ event shapes). It used to read "1 of 1 tests passed". */
    @Test
    @SuppressWarnings("unchecked")
    void aGoPackageThatDoesNotCompileFailsTheRunAndShowsTheCompilerError(@TempDir Path dir) throws Exception {
        List<String> stream = List.of(
                "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-output\",\"Output\":\"# ex/q [ex/q.test]\\n\"}",
                "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-output\","
                        + "\"Output\":\"./q_test.go:5:2: undefined: nope\\n\"}",
                "{\"ImportPath\":\"ex/q [ex/q.test]\",\"Action\":\"build-fail\"}",
                "{\"Action\":\"start\",\"Package\":\"ex/p\"}",
                "{\"Action\":\"run\",\"Package\":\"ex/p\",\"Test\":\"TestParse\"}",
                "{\"Action\":\"pass\",\"Package\":\"ex/p\",\"Test\":\"TestParse\",\"Elapsed\":0}",
                "{\"Action\":\"pass\",\"Package\":\"ex/p\",\"Elapsed\":0.002}",
                "{\"Action\":\"start\",\"Package\":\"ex/q\"}",
                "{\"Action\":\"output\",\"Package\":\"ex/q\",\"Output\":\"FAIL\\tex/q [build failed]\\n\"}",
                "{\"Action\":\"fail\",\"Package\":\"ex/q\",\"Elapsed\":0,\"FailedBuild\":\"ex/q [ex/q.test]\"}");
        TestRunCoordinator c = run(BuildTool.GO, dir, List.of("test", "./..."), stream, 1);
        try {
            assertEquals(tr("testrunner.finishedFailed", 1, 2), header(c));
            assertTrue(failedStyle(c));
            TreeView<TestNode> tree = FxTestSupport.field(c.panel(), "tree");
            TestNode failed = FxTestSupport.callOnFx(() ->
                    tree.getRoot().getChildren().get(1).getChildren().get(0).getValue());
            assertTrue(failed.failureMessage().contains("undefined: nope"), failed.failureMessage());
            // And the Output console is no longer missing the compiler text.
            assertEquals("./q_test.go:5:2: undefined: nope", c.consoleLine(stream.get(1), false));
        } finally {
            FxTestSupport.runOnFx(c::shutdown);
        }
    }

    /** npm with a non-TAP reporter: the tree was empty and the header read "0 of 0 tests passed". */
    @Test
    @SuppressWarnings("unchecked")
    void anNpmRunWithoutStructuredOutputShowsTheBannerAndTheFailure(@TempDir Path dir) throws Exception {
        TestRunCoordinator c = run(
                BuildTool.NPM,
                dir,
                List.of("run", "test"),
                List.of("FAIL src/a.test.js", "Tests: 1 failed, 1 total"),
                1);
        try {
            assertEquals(tr("testrunner.finishedAborted", 1), header(c));
            assertTrue(failedStyle(c));
            TreeView<TestNode> tree = FxTestSupport.field(c.panel(), "tree");
            List<String> rows = FxTestSupport.callOnFx(() -> tree.getRoot().getChildren().stream()
                    .map(i -> i.getValue().displayName())
                    .toList());
            assertEquals(List.of(tr("testrunner.tap.unavailable")), rows, "the fallback banner is visible");
        } finally {
            FxTestSupport.runOnFx(c::shutdown);
        }
    }
}
