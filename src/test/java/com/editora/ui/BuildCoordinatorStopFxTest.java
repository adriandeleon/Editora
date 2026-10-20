package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.Label;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Maven review B5 and B13 on {@link BuildCoordinator}: a build the user stops ends as "Stopped" in the console
 * header, the status bar and the Test Results hook (not as SIGTERM's 143), and a run asked for while another is
 * in progress offers "Stop and run" — accepted, the running build is stopped and the new one starts from its
 * exit; declined, the old busy status stands. The build is a real {@code sh} script that sleeps, so a Stop is a
 * genuine kill whose exit lands asynchronously.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildCoordinatorStopFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        Assumptions.assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "needs a POSIX sh");
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new ArrayList<>();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        String lastStatus() {
            return statuses.isEmpty() ? null : statuses.get(statuses.size() - 1);
        }
    }

    private static final class FakeOps implements BuildCoordinator.Ops {
        Path projectRoot;

        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public void openTasks() {}

        @Override
        public void openConsole() {}

        @Override
        public void setToolWindowsAvailable(boolean available) {}

        @Override
        public boolean isTrusted(Path root) {
            return true;
        }

        @Override
        public boolean confirmTrust(Path root, com.editora.build.BuildTrust.Summary summary) {
            return true;
        }

        @Override
        public void trust(Path root) {}
    }

    /** Records what the Test Results seam was told. */
    private static final class RecordingHook implements TestRunHook {
        final List<String> events = new ArrayList<>();

        @Override
        public boolean onTestRunStart(
                BuildTool tool, Path workingDir, List<String> taskArgs, List<String> toggleArgs, List<String> argv) {
            events.add("start");
            return true;
        }

        @Override
        public void onTestOutput(String line, boolean stderr) {}

        @Override
        public void onTestExit(int code) {
            events.add("exit " + code);
        }

        @Override
        public void onTestStopped(int code) {
            events.add("stopped " + code);
        }

        @Override
        public void onTestError(String message) {
            events.add("error");
        }
    }

    /** A Maven coordinator whose "mvn" is a script that sleeps until killed, in a detected pom.xml folder. */
    private static BuildCoordinator sleepingMaven(FakeHost host, BuildOutputPanel console, Path dir) throws Exception {
        Path script = dir.resolve("fake-mvn.sh");
        Files.writeString(script, "#!/bin/sh\necho started\nsleep 30\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(dir.resolve("pom.xml"), "<project><artifactId>demo</artifactId></project>");
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand(script.toString());
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        BuildCoordinator c = new BuildCoordinator(BuildTool.MAVEN, host, ops, console);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isDetected, "pom.xml detection");
        return c;
    }

    private static String consoleHeader(BuildOutputPanel console) {
        BuildToolPanel panel = (BuildToolPanel) console.getTabs().get(0).getContent();
        Label status = FxTestSupport.field(panel, "status");
        return status.getText();
    }

    @Test
    void stopEndsAsStoppedNotAsTheKilledProcessesExitCode(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        BuildOutputPanel console = new BuildOutputPanel();
        BuildCoordinator c = sleepingMaven(host, console, dir);

        FxTestSupport.runOnFx(() -> c.runTask(List.of("clean", "verify"), List.of()));
        waitUntil(c::isRunning, "the build started");
        FxTestSupport.runOnFx(c::stop);
        waitUntil(() -> !c.isRunning(), "the build's exit was delivered");
        FxTestSupport.runOnFx(() -> {});

        String stopped = tr("status.build.stopped", BuildTool.MAVEN.displayName());
        assertEquals(stopped, host.lastStatus(), "the status bar keeps 'stopped' after the exit: " + host.statuses);
        assertFalse(
                host.statuses.contains(tr("status.build.exit", BuildTool.MAVEN.displayName(), 143)),
                "no 'exited with code 143' anywhere: " + host.statuses);
        assertEquals(tr("run.stopped"), FxTestSupport.callOnFx(() -> consoleHeader(console)));
    }

    @Test
    void aStoppedTestRunTellsTheTestResultsHookItWasStopped(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        BuildCoordinator c = sleepingMaven(host, new BuildOutputPanel(), dir);
        RecordingHook hook = new RecordingHook();
        c.setTestRunHook(hook);

        FxTestSupport.runOnFx(() -> c.runTask(List.of("test"), List.of()));
        waitUntil(c::isRunning, "the test run started");
        FxTestSupport.runOnFx(c::stop);
        waitUntil(() -> !c.isRunning(), "the test run's exit was delivered");

        assertEquals("start", hook.events.get(0));
        assertTrue(hook.events.get(1).startsWith("stopped "), "the hook hears 'stopped', not 'exit': " + hook.events);
        assertEquals(2, hook.events.size(), hook.events.toString());
    }

    @Test
    void aSecondRunWhileBusyOffersStopAndRunAndStartsFromTheStoppedBuildsExit(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        BuildCoordinator c = sleepingMaven(host, new BuildOutputPanel(), dir);
        List<String> asked = new ArrayList<>();
        c.setStopAndRunPrompt((toolName, task) -> {
            asked.add(toolName + ": " + task);
            return true;
        });

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));
        waitUntil(c::isRunning, "the first build started");
        FxTestSupport.runOnFx(() -> c.runTask(List.of("verify", "-DskipTests"), List.of()));
        assertEquals(List.of(BuildTool.MAVEN.displayName() + ": verify -DskipTests"), asked);

        waitUntil(
                () -> c.isRunning() && List.of("verify", "-DskipTests").equals(lastTaskArgs(c)),
                "the second build started once the first one's exit was delivered");
        assertTrue(
                host.lastStatus()
                        .startsWith(tr("status.build.started", BuildTool.MAVEN.displayName(), "verify -DskipTests")),
                host.statuses.toString()); // "… in <folder>": the status names the root (review B21)
        assertTrue(
                host.statuses.contains(tr("status.build.stopped", BuildTool.MAVEN.displayName())),
                "the first build was stopped on the way: " + host.statuses);

        FxTestSupport.runOnFx(c::stop);
        waitUntil(() -> !c.isRunning(), "cleanup");
    }

    @Test
    void decliningStopAndRunLeavesTheRunningBuildAlone(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        BuildCoordinator c = sleepingMaven(host, new BuildOutputPanel(), dir);
        c.setStopAndRunPrompt((toolName, task) -> false);

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));
        waitUntil(c::isRunning, "the first build started");
        FxTestSupport.runOnFx(() -> c.rerunLast());
        FxTestSupport.runOnFx(() -> {});

        assertEquals(tr("status.build.busy", BuildTool.MAVEN.displayName()), host.lastStatus());
        assertTrue(c.isRunning(), "still the first build");
        FxTestSupport.runOnFx(c::stop);
        waitUntil(() -> !c.isRunning(), "cleanup");
        assertEquals(tr("status.build.stopped", BuildTool.MAVEN.displayName()), host.lastStatus());
    }

    @Test
    void theTasksTreeGreysRunWhileABuildRuns() throws Exception {
        FxTestSupport.runOnFx(() -> {
            BuildActionsTree tree = new BuildActionsTree();
            // Run is also greyed until a task row is selected (review B30): give it one task and select it.
            tree.setProvider(active -> List.of(new com.editora.build.BuildAction.Section(
                    "Lifecycle", List.of(new com.editora.build.BuildAction.Task("compile", List.of("compile"))))));
            FxTestSupport.<javafx.scene.control.TreeView<?>>field(tree, "tree")
                    .getSelectionModel()
                    .select(1);
            javafx.scene.control.Button run = FxTestSupport.field(tree, "runButton");
            javafx.scene.control.Button stop = FxTestSupport.field(tree, "stopButton");
            assertFalse(run.isDisable());
            assertTrue(stop.isDisable());
            tree.setRunning(true);
            assertTrue(run.isDisable(), "Run is greyed while a build runs");
            assertFalse(stop.isDisable());
            tree.setRunning(false);
            assertFalse(run.isDisable());
        });
    }

    private static List<String> lastTaskArgs(BuildCoordinator c) {
        return FxTestSupport.field(c, "lastTaskArgs");
    }

    private static void waitUntil(FxCheck condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (FxTestSupport.callOnFx(condition::check)) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Timed out waiting for: " + what);
    }

    @FunctionalInterface
    private interface FxCheck {
        boolean check();
    }
}
