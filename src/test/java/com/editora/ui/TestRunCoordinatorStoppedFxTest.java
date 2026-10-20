package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maven review B5 in Test Results: a test run the user stopped reads "Stopped" with what was counted so far,
 * not "Run failed (exit code 143) — see Output" with the failed bar, which is what SIGTERM's code used to give.
 */
@Tag("fx")
class TestRunCoordinatorStoppedFxTest {

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

    private static TestRunCoordinator stoppedRun(Path dir, int code) throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.callOnFx(() -> new TestRunCoordinator(new Host(), new Ops()));
        FxTestSupport.runOnFx(() -> {
            assertTrue(coordinator.onTestRunStart(
                    BuildTool.MAVEN, dir, List.of("test"), List.of(), List.of("mvn", "test")));
            coordinator.onTestOutput("[INFO] Scanning for projects...", false);
            coordinator.onTestStopped(code);
        });
        ScheduledExecutorService poller = FxTestSupport.field(coordinator, "poller");
        poller.submit(() -> {}).get(30, TimeUnit.SECONDS);
        FxTestSupport.runOnFx(() -> {});
        FxTestSupport.runOnFx(() -> {});
        return coordinator;
    }

    @Test
    void aStoppedMavenTestRunReadsStoppedWithoutTheFailedBar(@TempDir Path dir) throws Exception {
        TestRunCoordinator c = stoppedRun(dir, 143);
        try {
            Label status = FxTestSupport.field(c.panel(), "status");
            assertEquals(tr("testrunner.finishedStopped", 0, 0), FxTestSupport.callOnFx(status::getText));
            ProgressBar progress = FxTestSupport.field(c.panel(), "progress");
            assertFalse(FxTestSupport.callOnFx(() -> progress.getStyleClass().contains("test-progress-failed")));
        } finally {
            FxTestSupport.runOnFx(c::shutdown);
        }
    }

    @Test
    void theNextRunIsNotStoppedByTheLastOnesFlag(@TempDir Path dir) throws Exception {
        TestRunCoordinator c = stoppedRun(dir, 143);
        try {
            FxTestSupport.runOnFx(() -> {
                assertTrue(c.onTestRunStart(BuildTool.MAVEN, dir, List.of("test"), List.of(), List.of("mvn", "test")));
                c.onTestExit(1);
            });
            ScheduledExecutorService poller = FxTestSupport.field(c, "poller");
            poller.submit(() -> {}).get(30, TimeUnit.SECONDS);
            FxTestSupport.runOnFx(() -> {});
            FxTestSupport.runOnFx(() -> {});
            Label status = FxTestSupport.field(c.panel(), "status");
            assertEquals(tr("testrunner.finishedAborted", 1), FxTestSupport.callOnFx(status::getText));
        } finally {
            FxTestSupport.runOnFx(c::shutdown);
        }
    }
}
