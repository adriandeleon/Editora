package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.build.BuildService;
import com.editora.process.OutputPump;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BuildService} on the shared {@link OutputPump} — the build-side twin of
 * {@link RunServiceBackpressureFxTest}.
 *
 * <p>It used to post one {@code Platform.runLater} per output line with no bound and no line cap, and to
 * report the exit the moment {@code waitFor()} returned, while its reader threads still held the tail of the
 * pipes. A stream-parsed test run (Go, Cargo, npm TAP) therefore finished before its last results arrived.
 * A build's stream is parsed, so unlike the Run console nothing may be dropped: a full queue makes the
 * reader wait instead.
 */
@Tag("fx")
class BuildServiceBackpressureFxTest {

    private static final int FLOOD_LINES = 20_000;

    public static final class FloodMain {
        public static void main(String[] args) {
            if (args.length > 0 && "long-line".equals(args[0])) {
                System.out.print("x".repeat(2_000_000));
                return;
            }
            if (args.length > 0 && "tail".equals(args[0])) {
                // What a test tool does: results, then a trailing summary, then exit at once.
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < 2_000; i++) {
                    sb.append("test result ").append(i).append('\n');
                }
                sb.append("failures:\n    tests::the_last_one\n");
                System.out.print(sb);
                System.err.print("warning on stderr\nEND-ERR\n");
                return;
            }
            for (int i = 0; i < FLOOD_LINES; i++) {
                System.out.println(i + " " + "x".repeat(100));
            }
        }
    }

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static List<String> javaCommand(String... args) {
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        List<String> command =
                new ArrayList<>(List.of(java, "-cp", System.getProperty("java.class.path"), FloodMain.class.getName()));
        command.addAll(List.of(args));
        return command;
    }

    /** Records everything the service reports, in order. */
    private static final class Recorder implements BuildService.Listener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);

        @Override
        public void onStart(String commandLine) {
            started.countDown();
        }

        @Override
        public void onOutput(String line, boolean stderr) {
            events.add((stderr ? "E:" : "O:") + line);
        }

        @Override
        public void onExit(int code) {
            events.add("exit:" + code);
            exited.countDown();
        }

        @Override
        public void onError(String message) {
            events.add("error:" + message);
            exited.countDown();
        }
    }

    /**
     * The trailing block a tool prints just before exiting must be delivered before the exit: this is Cargo's
     * {@code failures:} list, and the last test results of a Go/TAP run. The FX thread is held busy while
     * the process runs and exits, which is exactly when the old waiter queued the exit ahead of the lines
     * its readers had not posted yet.
     */
    @Test
    void theTrailingOutputIsDeliveredBeforeTheExitIsReported() throws Exception {
        BuildService service = new BuildService();
        Recorder recorder = new Recorder();
        CountDownLatch releaseFx = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                service.run(Path.of("."), javaCommand("tail"), recorder);
                awaitQuietly(releaseFx);
            });
            assertTrue(recorder.started.await(10, TimeUnit.SECONDS));
            Process process = awaitProcess(service);
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "the tool has exited while the UI was busy");
            releaseFx.countDown();
            assertTrue(recorder.exited.await(30, TimeUnit.SECONDS));

            List<String> events = List.copyOf(recorder.events);
            assertEquals("exit:0", events.get(events.size() - 1), "nothing is delivered after the exit");
            List<String> stdout =
                    events.stream().filter(e -> e.startsWith("O:")).toList();
            assertEquals(2_002, stdout.size(), "every result line and the trailing block arrived");
            assertEquals("O:    tests::the_last_one", stdout.get(stdout.size() - 1));
            assertEquals("O:failures:", stdout.get(stdout.size() - 2));
            assertTrue(events.contains("E:END-ERR"), "stderr's tail is joined too");
        } finally {
            releaseFx.countDown();
            service.shutdown();
        }
    }

    /**
     * While the FX thread cannot drain, the queue is bounded and the child is held back by the pipe rather
     * than buffered without limit; once the UI catches up every line arrives, in order, and the exit is last.
     */
    @Test
    void aFloodIsBackpressuredNotDroppedAndTheExitStaysOrdered() throws Exception {
        BuildService service = new BuildService();
        Recorder recorder = new Recorder();
        CountDownLatch releaseFx = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                service.run(Path.of("."), javaCommand(), recorder);
                awaitQuietly(releaseFx);
            });
            assertTrue(recorder.started.await(10, TimeUnit.SECONDS));
            Process process = awaitProcess(service);
            // 2 MB of output against a 256 KB queue and a 64 KB pipe: with backpressure the child cannot
            // finish while the UI is blocked. Unbounded, it finished at once and the lines piled up.
            assertFalse(
                    process.waitFor(2, TimeUnit.SECONDS),
                    "the child must be held back by the full pipe while the UI is busy");
            assertTrue(recorder.events.isEmpty(), "and nothing was delivered behind the busy FX thread's back");

            releaseFx.countDown();
            assertTrue(recorder.exited.await(60, TimeUnit.SECONDS));

            List<String> events = List.copyOf(recorder.events);
            assertEquals(FLOOD_LINES + 1, events.size(), "no line was dropped");
            assertFalse(events.contains("O:" + OutputPump.OUTPUT_DROPPED), "a parsed stream is never truncated");
            for (int i = 0; i < FLOOD_LINES; i += 997) {
                assertTrue(events.get(i).startsWith("O:" + i + " "), "line " + i + " is in order: " + events.get(i));
            }
            assertEquals("exit:0", events.get(events.size() - 1));
        } finally {
            releaseFx.countDown();
            service.shutdown();
        }
    }

    @Test
    void aSingleUnterminatedLineIsCappedBeforeDelivery() throws Exception {
        BuildService service = new BuildService();
        Recorder recorder = new Recorder();
        try {
            FxTestSupport.runOnFx(() -> service.run(Path.of("."), javaCommand("long-line"), recorder));
            assertTrue(recorder.exited.await(30, TimeUnit.SECONDS));

            List<String> events = List.copyOf(recorder.events);
            assertEquals(2, events.size(), "one capped line, then the exit: " + events.size());
            assertTrue(events.get(0).length() < 70_000, "the reader must not retain the complete giant line");
            assertTrue(events.get(0).endsWith(OutputPump.LINE_TRUNCATED));
            assertEquals("exit:0", events.get(1));
        } finally {
            service.shutdown();
        }
    }

    /** Window close: queued callbacks are discarded and a reader parked on the full queue is released. */
    @Test
    void shutdownDiscardsQueuedOutputAndReleasesABlockedReader() throws Exception {
        BuildService service = new BuildService();
        Recorder recorder = new Recorder();
        CountDownLatch releaseFx = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                service.run(Path.of("."), javaCommand(), recorder);
                awaitQuietly(releaseFx);
            });
            assertTrue(recorder.started.await(10, TimeUnit.SECONDS));
            Process process = awaitProcess(service);

            service.shutdown(); // off the FX thread, as a window close racing a flood would be
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "shutdown kills the build");
            releaseFx.countDown();
            FxTestSupport.runOnFx(() -> {});

            assertTrue(recorder.events.isEmpty(), "nothing is delivered to a listener whose window has closed");
            assertFalse(service.isRunning());
        } finally {
            releaseFx.countDown();
            service.shutdown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** {@code current} is published on the FX thread before {@code onStart}, so it is set once started fired. */
    private static Process awaitProcess(BuildService service) {
        Process process = FxTestSupport.field(service, "current");
        if (process == null) {
            throw new AssertionError("build process never started");
        }
        return process;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
    }
}
