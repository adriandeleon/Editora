package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.editora.config.RunConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The before-launch gate: a configuration's build step must succeed before anything is launched.
 *
 * <p>Exercises {@link RunCoordinator#withBeforeLaunch} directly, which is the form both the run and the debug
 * path now share. It used to be private to {@link RunCoordinator}, so the debug path had no gate at all — a
 * configuration whose before-launch was {@code mvn -q compile} compiled on Run and silently debugged the
 * previous class files on Debug, putting every breakpoint on a stale line number.
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
        FxTestSupport.runOnFx(() -> RunCoordinator.withBeforeLaunch(host, cfg, Path.of("."), () -> {
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
}
