package com.editora.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunService} starts its program off the FX thread (PERF-01, Pauta 6.0.1): what a press, a Stop, typed
 * input and a closing window do while the start is under way. The starts are held by the test and released on a
 * thread of its own, so each case is the window between the press and the program's start.
 */
@Tag("fx")
class RunServiceStartFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    /** Records every callback of a run. */
    private static final class Recorder implements RunService.Listener {
        final List<String> events = new ArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public synchronized void onStart(String commandLine) {
            events.add("start");
        }

        @Override
        public synchronized void onOutput(String line, boolean stderr) {
            events.add((stderr ? "err:" : "out:") + line.strip());
        }

        @Override
        public synchronized void onExit(int code) {
            events.add("exit:" + code);
            ended.countDown();
        }

        @Override
        public synchronized void onError(String message) {
            events.add("error:" + message);
            ended.countDown();
        }

        synchronized List<String> events() {
            return List.copyOf(events);
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Throwable[] failure = {null};
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                failure[0] = t;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "the FX thread ran the action");
        if (failure[0] != null) {
            throw new AssertionError(failure[0]);
        }
    }

    private static void drainFx() throws Exception {
        onFx(() -> {});
        onFx(() -> {});
    }

    /** Starts held by the test: each is a task the test runs when it says, on its own thread. */
    private static final class HeldStarts implements java.util.concurrent.Executor {
        final java.util.concurrent.BlockingQueue<Runnable> held = new java.util.concurrent.LinkedBlockingQueue<>();

        @Override
        public void execute(Runnable start) {
            held.add(start);
        }

        /** Runs the next held start on a thread of its own, as the real {@code run-start} does. */
        void release() throws InterruptedException {
            Runnable start = held.poll(10, TimeUnit.SECONDS);
            assertTrue(start != null, "a start was asked for");
            Thread.ofVirtual().name("run-start").start(start);
        }
    }

    private static List<String> echo(String text) {
        return isWindows() ? List.of("cmd", "/c", "echo " + text) : List.of("sh", "-c", "echo " + text);
    }

    private static List<String> cat() {
        return List.of("cat");
    }

    private static boolean isWindows() {
        return com.editora.Os.isWindows();
    }

    /**
     * PERF-01: the PATH lookup, the start and the ledger write happen on the {@code run-start} thread. While they
     * do, the service counts as running, so a second press starts nothing; the run then reports its start before
     * its output, on the FX thread.
     */
    @Test
    void aSecondPressWhileTheProgramIsStartingStartsNothing(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        HeldStarts starts = new HeldStarts();
        service.starter = starts;
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        try {
            boolean[] runningAtOnce = {false};
            onFx(() -> {
                service.runInDir(dir, echo("first"), first);
                runningAtOnce[0] = service.isRunning();
                service.runInDir(dir, echo("second"), second); // the second press
            });
            assertTrue(runningAtOnce[0], "running from the press, before the program has started");
            assertEquals(1, starts.held.size(), "one start asked for");
            assertEquals(List.of(), first.events(), "nothing reported before the start");
            starts.release();
            assertTrue(first.ended.await(30, TimeUnit.SECONDS));
            drainFx();
            assertEquals(List.of("start", "out:first", "exit:0"), first.events());
            assertEquals(List.of(), second.events(), "the second press did nothing");
            assertTrue(starts.held.isEmpty());
        } finally {
            onFx(service::shutdown);
        }
    }

    /** Stop pressed while the program is still being started ends it as soon as it has started. */
    @Test
    void aStopWhileStartingEndsTheProgramOnceStarted(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        HeldStarts starts = new HeldStarts();
        service.starter = starts;
        Recorder listener = new Recorder();
        List<String> sleep = isWindows()
                ? List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 30")
                : List.of("sh", "-c", "sleep 30");
        try {
            onFx(() -> {
                service.runInDir(dir, sleep, listener);
                service.stop();
            });
            long began = System.nanoTime();
            starts.release();
            assertTrue(listener.ended.await(20, TimeUnit.SECONDS), "the program was ended");
            assertTrue(System.nanoTime() - began < TimeUnit.SECONDS.toNanos(20), "not by its own end");
            drainFx();
            List<String> events = listener.events();
            assertEquals("start", events.get(0), events.toString());
            assertTrue(events.get(events.size() - 1).startsWith("exit:"), events.toString());
            assertFalse(service.isRunning());
        } finally {
            onFx(service::shutdown);
        }
    }

    /** Lines typed, and the end of input, while the program is being started reach it once it has, in order. */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(
            value = org.junit.jupiter.api.condition.OS.WINDOWS,
            disabledReason = "cat stands in for a program that reads its input")
    void inputSentWhileStartingReachesTheProgramInOrder(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        HeldStarts starts = new HeldStarts();
        service.starter = starts;
        Recorder listener = new Recorder();
        try {
            onFx(() -> {
                service.runInDir(dir, cat(), listener);
                service.sendInput("one");
                service.sendInput("two");
                service.closeInput();
            });
            starts.release();
            assertTrue(listener.ended.await(30, TimeUnit.SECONDS), "the program saw the end of its input");
            drainFx();
            assertEquals(List.of("start", "out:one", "out:two", "exit:0"), listener.events());
        } finally {
            onFx(service::shutdown);
        }
    }

    /** A window closed while its program is being started: the program is ended, and nothing is reported. */
    @Test
    void aShutdownWhileStartingEndsTheProgramAndReportsNothing(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        HeldStarts starts = new HeldStarts();
        service.starter = starts;
        Recorder listener = new Recorder();
        onFx(() -> {
            service.runInDir(dir, echo("late"), listener);
            service.shutdown();
        });
        starts.release();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (service.isRunning() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertFalse(service.isRunning(), "the start settled");
        drainFx();
        Thread.sleep(200);
        drainFx();
        assertEquals(List.of(), listener.events(), "nothing reaches a closed window");
    }

    /** A program that cannot start is reported on the FX thread, and leaves the service free for the next. */
    @Test
    void aStartThatFailsIsReportedOnTheFxThread(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        Recorder listener = new Recorder();
        boolean[] onFxThread = {false};
        RunService.Listener checking = new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                listener.onStart(commandLine);
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                listener.onOutput(line, stderr);
            }

            @Override
            public void onExit(int code) {
                listener.onExit(code);
            }

            @Override
            public void onError(String message) {
                onFxThread[0] = Platform.isFxApplicationThread();
                listener.onError(message);
            }
        };
        try {
            onFx(() ->
                    service.runInDir(dir, List.of(dir.resolve("no-such-program").toString()), checking));
            assertTrue(listener.ended.await(20, TimeUnit.SECONDS));
            assertTrue(onFxThread[0], "the failure is said on the FX thread");
            List<String> events = listener.events();
            assertEquals(1, events.size(), events.toString());
            assertTrue(events.get(0).startsWith("error:"), events.toString());
            assertFalse(service.isRunning());
        } finally {
            onFx(service::shutdown);
        }
    }
}
