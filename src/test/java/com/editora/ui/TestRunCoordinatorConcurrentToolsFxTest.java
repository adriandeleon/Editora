package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import com.editora.run.StackTraceLinks;
import com.editora.test.TestNode;
import com.editora.test.TestRun;
import com.editora.test.TestStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One {@link TestRunCoordinator} is the test hook of every build tool, and tools run concurrently. A second
 * tool's test run started mid-run used to take over the single run state: the first tool's remaining results
 * went to the second's parser and its exit finished the second run.
 */
@Tag("fx")
class TestRunCoordinatorConcurrentToolsFxTest {

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

    @Test
    void aSecondToolsTestRunDoesNotTakeOverARunInFlight() throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.callOnFx(() -> new TestRunCoordinator(new Host(), new Ops()));
        try {
            FxTestSupport.runOnFx(() -> {
                Path dir = Path.of(".");
                assertTrue(coordinator.onTestRunStart(
                        BuildTool.CARGO, dir, List.of("test"), List.of(), List.of("cargo", "test")));
                TestRun cargo = FxTestSupport.field(coordinator, "currentRun");

                assertFalse(
                        coordinator.onTestRunStart(
                                BuildTool.GO, dir, List.of("test", "./..."), List.of(), List.of("go", "test")),
                        "declined: it runs in its own console");
                assertSame(cargo, FxTestSupport.field(coordinator, "currentRun"));

                // The first run's stream and exit still reach the first run.
                coordinator.onTestOutput("test tests::late ... FAILED", false);
                coordinator.onTestExit(101);
                assertFalse(cargo.isRunning());
                assertEquals(101, cargo.exitCode());
                assertEquals(
                        TestStatus.FAILED,
                        cargo.root().children().get(0).children().get(0).status());

                assertTrue(
                        coordinator.onTestRunStart(
                                BuildTool.GO, dir, List.of("test", "./..."), List.of(), List.of("go", "test")),
                        "once it is over, the other tool's run is claimed as usual");
                coordinator.onTestExit(0);
            });
        } finally {
            FxTestSupport.runOnFx(coordinator::shutdown);
        }
    }
}
