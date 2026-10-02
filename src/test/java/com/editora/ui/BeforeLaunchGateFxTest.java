package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.editora.config.RunConfiguration;
import com.editora.run.RunService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The before-launch gate: a configuration's build step must succeed before anything is launched.
 *
 * <p>Exercises {@link BeforeLaunchStep} directly, which is the form both the run and the debug path share. It
 * used to be private to {@link RunCoordinator}, so the debug path had no gate at all — a configuration whose
 * before-launch was {@code mvn -q compile} compiled on Run and silently debugged the previous class files on
 * Debug, putting every breakpoint on a stale line number.
 *
 * <p>The step is a real, streamed process: its output reaches a console while it runs, the owner is busy
 * until it exits, and Stop kills it. It used to be a capture-all {@code ProcessRunner.run} on an ad-hoc
 * thread — invisible, unstoppable, untracked (it outlived the window), and started again by every Run press.
 *
 * <p>Covers the gate's semantics, not the wiring into a live debug session: reaching
 * {@code DebugCoordinator.debugConfig}'s launch needs a real jdtls plus the java-debug bundle, so that half
 * is a device test.
 */
@Tag("fx")
class BeforeLaunchGateFxTest {

    @BeforeAll
    static void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /**
     * Records what the gate reported, so a refusal can be told apart from a silent no-op.
     *
     * <p>Also releases the latch on the failure path. A gate that refuses never calls {@code then}, so waiting
     * only on that would burn the full timeout on every negative case — the second status <em>is</em> the
     * verdict there (the first announces the step starting).
     */
    private static final class RecordingHost extends CoordinatorHostStub {
        private final List<String> statuses = new java.util.ArrayList<>();
        private volatile CountDownLatch settled;

        @Override
        public void setStatus(String message) {
            statuses.add(message);
            CountDownLatch latch = settled;
            if (latch != null && statuses.size() >= 2) {
                latch.countDown();
            }
        }
    }

    private static RunConfiguration withCommand(String beforeLaunch) {
        return new RunConfiguration("Demo", "java", "", "com.example.App", "", "", "", "", "", beforeLaunch);
    }

    /** The JVM running the tests — present on every platform, unlike {@code true}/{@code false}. */
    private static String selfJavaCommand(String args) {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        // Quoted: java.home routinely contains spaces on Windows, and ProgramArgs.tokenize is quote-aware.
        return "\"" + java + "\" " + args;
    }

    /**
     * Runs the gate on the FX thread and waits for its verdict.
     *
     * @return whether the launch went ahead
     */
    private static boolean gate(RecordingHost host, RunConfiguration cfg) throws Exception {
        AtomicBoolean launched = new AtomicBoolean();
        CountDownLatch settled = new CountDownLatch(1);
        host.settled = settled;
        BeforeLaunchStep step = new BeforeLaunchStep(new RunService());
        FxTestSupport.runOnFx(() -> step.run(host, cfg, Path.of("."), Map.of(), new RecordingConsole(), () -> {
            launched.set(true);
            settled.countDown();
        }));
        // Either outcome trips the latch (see RecordingHost). Generous, because the step forks a real JVM.
        assertTrue(settled.await(60, TimeUnit.SECONDS), "the gate reached a verdict");
        // Drain the FX queue so a runLater posted by the worker has landed before the statuses are read.
        FxTestSupport.runOnFx(() -> {});
        return launched.get();
    }

    @Test
    void aConfigurationWithNoBeforeLaunchStepLaunchesStraightAway() throws Exception {
        RecordingHost host = new RecordingHost();

        assertTrue(gate(host, withCommand("")), "no step means nothing to wait for");
        assertEquals(List.of(), host.statuses, "and nothing to report");
    }

    @Test
    void aSucceedingBeforeLaunchStepLetsTheLaunchProceed() throws Exception {
        RecordingHost host = new RecordingHost();

        assertTrue(gate(host, withCommand(selfJavaCommand("-version"))), "exit 0 means go ahead");
    }

    /**
     * The case the fix is about. A step that fails must stop the launch — running anyway is precisely the
     * stale-binary failure the step exists to prevent.
     */
    @Test
    void aFailingBeforeLaunchStepAbortsTheLaunch() throws Exception {
        RecordingHost host = new RecordingHost();

        // A class that does not exist: the JVM starts, fails, and exits non-zero on every platform.
        assertFalse(gate(host, withCommand(selfJavaCommand("com.editora.NoSuchClassWhatsoever"))), "must not launch");
        String last = host.statuses.get(host.statuses.size() - 1);
        assertTrue(last.contains("Demo"), "names the configuration, got: " + last);
    }

    /** A command that cannot be resolved at all fails the same way, rather than being treated as absent. */
    @Test
    void anUnresolvableBeforeLaunchCommandAlsoAbortsTheLaunch() throws Exception {
        RecordingHost host = new RecordingHost();

        assertFalse(gate(host, withCommand("editora-no-such-command-xyzzy")), "must not launch");
    }

    // --- the step as a visible, stoppable, tracked process ----------------------------------------------

    /** Prints a line, then waits for input that never comes: a build that is still running. */
    public static final class Blocker {
        public static void main(String[] args) throws Exception {
            System.out.println("building");
            System.out.flush();
            System.in.read();
        }
    }

    private static String blockerCommand() {
        return selfJavaCommand("-cp \"" + System.getProperty("java.class.path") + "\" " + Blocker.class.getName());
    }

    /** What the step showed, with latches for "its first line arrived" and "it is over". */
    private static final class RecordingConsole implements BeforeLaunchStep.Console {
        private final List<String> lines = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CountDownLatch firstLine = new CountDownLatch(1);
        private final CountDownLatch ended = new CountDownLatch(1);
        private volatile String header;
        private volatile int exitCode = Integer.MIN_VALUE;

        @Override
        public void started(String commandLine) {
            header = commandLine;
        }

        @Override
        public void output(String line, boolean stderr) {
            lines.add(line);
            firstLine.countDown();
        }

        @Override
        public void ended(int code, String launchError) {
            exitCode = code;
            ended.countDown();
        }
    }

    @Test
    void theStepStreamsItsOutputAndIsBusyUntilStopKillsIt() throws Exception {
        RecordingHost host = new RecordingHost();
        RecordingConsole console = new RecordingConsole();
        RunService service = new RunService();
        BeforeLaunchStep step = new BeforeLaunchStep(service);
        AtomicBoolean launched = new AtomicBoolean();
        try {
            FxTestSupport.runOnFx(() -> step.run(
                    host, withCommand(blockerCommand()), Path.of("."), Map.of(), console, () -> launched.set(true)));

            // Its output arrives while it is still running — nothing was visible until the end before.
            assertTrue(console.firstLine.await(60, TimeUnit.SECONDS), "the step's output streams as it runs");
            assertEquals(List.of("building"), console.lines);
            assertTrue(console.header.contains(Blocker.class.getName()), "the console names the command");
            assertTrue(FxTestSupport.callOnFx(step::isActive), "the owner is busy from request to exit");
            assertTrue(service.isRunning(), "on the tracked streaming service, so the window's shutdown kills it");

            // A second request while it runs is refused, not started alongside it.
            AtomicBoolean second = new AtomicBoolean();
            FxTestSupport.runOnFx(() -> step.run(
                    host,
                    withCommand(blockerCommand()),
                    Path.of("."),
                    Map.of(),
                    new RecordingConsole(),
                    () -> second.set(true)));
            assertEquals(com.editora.i18n.Messages.tr("status.run.busy"), host.statuses.get(host.statuses.size() - 1));

            FxTestSupport.runOnFx(step::stop);
            assertTrue(console.ended.await(30, TimeUnit.SECONDS), "Stop kills the step");
            FxTestSupport.runOnFx(() -> {});

            assertFalse(FxTestSupport.callOnFx(step::isActive));
            assertFalse(launched.get(), "a stopped build launches nothing");
            assertFalse(second.get());
            String last = host.statuses.get(host.statuses.size() - 1);
            assertEquals(com.editora.i18n.Messages.tr("status.run.beforeLaunchStopped", "Demo"), last);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aFailingStepReportsTheToolsLastLineAndEndsTheConsole() throws Exception {
        RecordingHost host = new RecordingHost();
        RecordingConsole console = new RecordingConsole();
        BeforeLaunchStep step = new BeforeLaunchStep(new RunService());
        AtomicBoolean launched = new AtomicBoolean();

        FxTestSupport.runOnFx(() -> step.run(
                host,
                withCommand(selfJavaCommand("com.editora.NoSuchClassWhatsoever")),
                Path.of("."),
                Map.of(),
                console,
                () -> launched.set(true)));
        assertTrue(console.ended.await(60, TimeUnit.SECONDS));
        FxTestSupport.runOnFx(() -> {});

        assertFalse(launched.get());
        assertTrue(console.exitCode != 0, "the console is told the exit code");
        assertFalse(console.lines.isEmpty(), "the JVM's error was streamed, not swallowed");
        String last = host.statuses.get(host.statuses.size() - 1);
        assertTrue(last.contains("NoSuchClassWhatsoever"), "the status carries the tool's own words, got: " + last);
    }

    // --- wired into the Run coordinator: busy from request to exit, and Stop reaches the step -----------

    /** A window with a project and no Java launch support; counts run-state notifications. */
    private static final class RunOps implements RunCoordinator.Ops {
        final Path root;
        final CountDownLatch ended = new CountDownLatch(2); // one notification at start, one at the end
        final AtomicBoolean launchAttempted = new AtomicBoolean();

        RunOps(Path root) {
            this.root = root;
        }

        @Override
        public void openToolWindow() {}

        @Override
        public void onRunStateChanged() {
            ended.countDown();
        }

        @Override
        public void editConfiguration(String name) {}

        @Override
        public boolean saveBuffer(com.editora.editor.EditorBuffer buffer) {
            return true;
        }

        @Override
        public String programArgs(Path path) {
            return "";
        }

        @Override
        public void setProgramArgs(Path path, String args) {}

        @Override
        public void openLink(com.editora.run.StackTraceLinks.Link link) {}

        @Override
        public Path javaProjectRoot(Path file) {
            return root;
        }

        @Override
        public Path projectRoot() {
            return root;
        }

        @Override
        public boolean javaLaunchAvailable() {
            return false;
        }

        @Override
        public List<RunConfiguration> runConfigurations() {
            return List.of();
        }

        @Override
        public String selectedRunConfigName() {
            return "";
        }

        @Override
        public void resolveJavaMainClasses(
                Path routingFile, java.util.function.Consumer<List<com.editora.run.JavaMainClass>> cb) {
            launchAttempted.set(true);
        }

        @Override
        public void resolveJavaLaunch(
                Path routingFile,
                com.editora.run.JavaMainClass mainClass,
                java.util.function.Consumer<com.editora.run.JavaLaunchInfo> cb) {
            launchAttempted.set(true);
        }

        @Override
        public boolean mavenProjectAt(Path r) {
            return false;
        }

        @Override
        public boolean gradleProjectAt(Path r) {
            return false;
        }

        @Override
        public void resolveMavenClasspath(Path r, java.util.function.Consumer<List<String>> cb) {
            launchAttempted.set(true);
        }

        @Override
        public void runGradleRunTask(Path r) {}
    }

    @Test
    void theRunCoordinatorIsBusyDuringTheStepAndStopKillsIt(@org.junit.jupiter.api.io.TempDir Path project)
            throws Exception {
        RecordingHost host = new RecordingHost();
        RunOps ops = new RunOps(project);
        RunCoordinator coordinator = FxTestSupport.callOnFx(() -> new RunCoordinator(host, ops));
        RunConfiguration cfg = withCommand(blockerCommand());
        try {
            FxTestSupport.runOnFx(() -> coordinator.runConfig(cfg));

            assertTrue(FxTestSupport.callOnFx(coordinator::isRunning), "Stop is enabled: the build is running");

            // Pressing Run again must not start a second, concurrent build.
            FxTestSupport.runOnFx(() -> coordinator.runConfig(cfg));
            assertEquals(com.editora.i18n.Messages.tr("status.run.busy"), host.statuses.get(host.statuses.size() - 1));
            assertEquals(1, ops.ended.getCount(), "no second step was started");

            FxTestSupport.runOnFx(coordinator::stopRun);
            assertTrue(ops.ended.await(30, TimeUnit.SECONDS), "Stop kills the before-launch step");
            FxTestSupport.runOnFx(() -> {});

            assertFalse(FxTestSupport.callOnFx(coordinator::isRunning));
            assertFalse(ops.launchAttempted.get(), "a stopped build launches nothing");
            assertEquals(
                    com.editora.i18n.Messages.tr("status.run.beforeLaunchStopped", "Demo"),
                    host.statuses.get(host.statuses.size() - 1));
        } finally {
            FxTestSupport.runOnFx(coordinator::shutdown);
        }
    }
}
