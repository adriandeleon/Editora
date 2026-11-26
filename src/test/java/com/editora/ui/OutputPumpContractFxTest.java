package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javafx.application.Platform;

import com.editora.build.BuildService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shared {@code process.OutputPump} must guarantee to the services on it, driven through
 * {@link BuildService}: a bare carriage return ends a line, a run stays "running" until its exit has been
 * delivered, and a listener that throws does not end delivery.
 */
@Tag("fx")
@Tag("windows-fx")
class OutputPumpContractFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Recorder implements BuildService.Listener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);
        final AtomicBoolean throwOnFirstLine = new AtomicBoolean();

        @Override
        public void onStart(String commandLine) {
            started.countDown();
        }

        @Override
        public void onOutput(String line, boolean stderr) {
            events.add(line);
            if (throwOnFirstLine.compareAndSet(true, false)) {
                throw new IllegalStateException("a sink failed");
            }
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
     * A command that writes {@code what} to stdout: the lines given, each ended by LF, or one of the canned
     * outputs ({@code "progress"}, {@code "count"}). A JVM rather than {@code sh}, so it runs on Windows too.
     */
    private static List<String> emit(String... what) {
        String java = ProcessHandle.current().info().command().orElse("java");
        String classes;
        try {
            classes = Path.of(Emit.class
                            .getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        List<String> argv = new ArrayList<>(List.of(java, "-cp", classes, Emit.class.getName()));
        argv.addAll(List.of(what));
        return argv;
    }

    /** The program {@link #emit} runs. It uses nothing but {@code java.base}. */
    static final class Emit {
        public static void main(String[] args) throws java.io.IOException {
            java.io.OutputStream out = System.out;
            if (args.length == 1 && args[0].equals("progress")) {
                out.write("Progress 1/3\rProgress 2/3\rDownloaded\r\n[ERROR] after crlf\nma\u00f1ana\n"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else if (args.length == 1 && args[0].equals("count")) {
                StringBuilder lines = new StringBuilder();
                for (int i = 0; i < 1000; i++) {
                    lines.append("line ").append(i).append('\n');
                }
                out.write(lines.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                for (String line : args) {
                    out.write((line + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            out.flush();
        }
    }

    /**
     * Maven's download progress is CR-terminated. {@code BufferedReader.readLine()} — what the build service
     * read with before it moved onto the pump — ends a line at a bare CR; held until the next LF, a long
     * download showed nothing and then arrived as a single line.
     */
    @Test
    void aBareCarriageReturnEndsABuildLine() throws Exception {
        BuildService service = new BuildService();
        Recorder recorder = new Recorder();
        try {
            FxTestSupport.runOnFx(() -> service.run(Path.of("."), emit("progress"), recorder));
            assertTrue(recorder.exited.await(30, TimeUnit.SECONDS));
            assertEquals(
                    List.of("Progress 1/3", "Progress 2/3", "Downloaded", "[ERROR] after crlf", "mañana", "exit:0"),
                    List.copyOf(recorder.events));
        } finally {
            service.shutdown();
        }
    }

    /**
     * The process is dead but its exit has not reached the listener yet (the UI is busy). A second run must
     * be refused: starting it discarded the first run's remaining output and its exit, so whoever was waiting
     * for that exit — a test run, a before-launch step — stayed "running" for good.
     */
    @Test
    void aRunCountsAsRunningUntilItsExitIsDelivered() throws Exception {
        BuildService service = new BuildService();
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        CountDownLatch releaseFx = new CountDownLatch(1);
        try {
            Platform.runLater(() -> {
                service.run(Path.of("."), emit("one", "two"), first);
                awaitQuietly(releaseFx);
            });
            Process process = awaitCurrent(service); // started off the FX thread, which is busy
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            awaitWaiters(); // the waiter has joined the readers and queued the exit behind the busy UI

            assertTrue(service.isRunning(), "not over until the exit has been reported");
            service.run(Path.of("."), emit("other"), second);
            assertEquals(1, second.started.getCount(), "the second run is refused, not started over the first");

            releaseFx.countDown();
            assertTrue(first.exited.await(30, TimeUnit.SECONDS), "the first run's exit is delivered");
            assertEquals(0, first.started.getCount(), "its start was reported too");
            assertEquals(List.of("one", "two", "exit:0"), List.copyOf(first.events));
            FxTestSupport.runOnFx(() -> assertFalse(service.isRunning()));
        } finally {
            releaseFx.countDown();
            service.shutdown();
        }
    }

    /**
     * One exception from a listener used to leave the pump's "a drain is scheduled" flag set with no drain
     * scheduled: nothing more was delivered, for that run or any later one.
     */
    @Test
    void aThrowingListenerDoesNotEndDelivery() throws Exception {
        BuildService service = new BuildService();
        Recorder recorder = new Recorder();
        recorder.throwOnFirstLine.set(true);
        CountDownLatch releaseFx = new CountDownLatch(1);
        try {
            // Hold the UI until everything is queued, so the throwing callback has later batches behind it.
            Platform.runLater(() -> {
                service.run(Path.of("."), emit("count"), recorder);
                awaitQuietly(releaseFx);
            });
            Process process = awaitCurrent(service); // started off the FX thread, which is busy
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            awaitWaiters(); // every batch and the exit are queued behind the busy UI
            releaseFx.countDown();

            assertTrue(recorder.exited.await(30, TimeUnit.SECONDS), "the exit still arrives");
            assertEquals(1_001, recorder.events.size(), "and so does every line");

            Recorder next = new Recorder();
            FxTestSupport.runOnFx(() -> service.run(Path.of("."), emit("again"), next));
            assertTrue(next.exited.await(30, TimeUnit.SECONDS), "a later run on the same service works");
            assertEquals(List.of("again", "exit:0"), List.copyOf(next.events));
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

    /**
     * The {@code build-wait} threads have ended: each has joined its readers and handed the exit to the FX thread,
     * the last thing it does.
     */
    private static void awaitWaiters() throws InterruptedException {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("build-wait".equals(thread.getName())) {
                thread.join(TimeUnit.SECONDS.toMillis(30));
                assertFalse(thread.isAlive(), "the build's waiter ended");
            }
        }
    }

    /** The build's process once the {@code build-start} thread has published it (PERF-01: not on the FX thread). */
    private static Process awaitCurrent(BuildService service) throws InterruptedException {
        return com.editora.testkit.Await.value(
                () -> FxTestSupport.<Process>field(service, "current"), "the build to start");
    }
}
