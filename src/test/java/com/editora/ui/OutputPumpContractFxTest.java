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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * What the shared {@code process.OutputPump} must guarantee to the services on it, driven through
 * {@link BuildService}: a bare carriage return ends a line, a run stays "running" until its exit has been
 * delivered, and a listener that throws does not end delivery.
 */
@Tag("fx")
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

    private static List<String> sh(String script) {
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "needs a POSIX sh");
        return List.of("sh", "-c", script);
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
            FxTestSupport.runOnFx(() -> service.run(
                    Path.of("."),
                    sh(
                            "printf 'Progress 1/3\\rProgress 2/3\\rDownloaded\\r\\n[ERROR] after crlf\\nma\\303\\261ana\\n'"),
                    recorder));
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
                service.run(Path.of("."), sh("echo one; echo two"), first);
                awaitQuietly(releaseFx);
            });
            assertTrue(first.started.await(10, TimeUnit.SECONDS));
            Process process = FxTestSupport.field(service, "current");
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            Thread.sleep(300); // the waiter has joined the readers and queued the exit behind the busy UI

            assertTrue(service.isRunning(), "not over until the exit has been reported");
            service.run(Path.of("."), sh("echo other"), second);
            assertEquals(1, second.started.getCount(), "the second run is refused, not started over the first");

            releaseFx.countDown();
            assertTrue(first.exited.await(30, TimeUnit.SECONDS), "the first run's exit is delivered");
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
                service.run(
                        Path.of("."), sh("i=0; while [ $i -lt 1000 ]; do echo line $i; i=$((i+1)); done"), recorder);
                awaitQuietly(releaseFx);
            });
            assertTrue(recorder.started.await(10, TimeUnit.SECONDS));
            Process process = FxTestSupport.field(service, "current");
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            Thread.sleep(300);
            releaseFx.countDown();

            assertTrue(recorder.exited.await(30, TimeUnit.SECONDS), "the exit still arrives");
            assertEquals(1_001, recorder.events.size(), "and so does every line");

            Recorder next = new Recorder();
            FxTestSupport.runOnFx(() -> service.run(Path.of("."), sh("echo again"), next));
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
}
