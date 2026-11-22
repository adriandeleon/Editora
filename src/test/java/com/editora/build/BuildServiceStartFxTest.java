package com.editora.build;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
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
 * {@link BuildService} starts its build off the FX thread (PERF-01, Pauta 6.2.0): a second press while the first
 * build is being started starts nothing, a Stop then ends the build once it has started, and the start is
 * reported before the output.
 */
@Tag("fx")
class BuildServiceStartFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static final class Recorder implements BuildService.Listener {
        final List<String> events = new ArrayList<>();
        final CountDownLatch ended = new CountDownLatch(1);

        @Override
        public synchronized void onStart(String commandLine) {
            events.add("start");
        }

        @Override
        public synchronized void onOutput(String line, boolean stderr) {
            events.add("out:" + line.strip());
        }

        @Override
        public synchronized void onExit(int code) {
            events.add("exit:" + code);
            ended.countDown();
        }

        @Override
        public synchronized void onError(String message) {
            events.add("error");
            ended.countDown();
        }

        synchronized List<String> events() {
            return List.copyOf(events);
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private static List<String> echo(String text) {
        return com.editora.Os.isWindows() ? List.of("cmd", "/c", "echo " + text) : List.of("sh", "-c", "echo " + text);
    }

    @Test
    void aSecondPressWhileTheBuildIsStartingStartsNothing(@TempDir Path dir) throws Exception {
        BuildService service = new BuildService();
        BlockingQueue<Runnable> held = new LinkedBlockingQueue<>();
        service.starter = held::add;
        Recorder first = new Recorder();
        Recorder second = new Recorder();
        try {
            boolean[] runningAtOnce = {false};
            onFx(() -> {
                service.run(dir, echo("built"), first);
                runningAtOnce[0] = service.isRunning();
                service.run(dir, echo("again"), second);
            });
            assertTrue(runningAtOnce[0], "running from the press, before the build has started");
            assertEquals(1, held.size(), "one start asked for");
            Thread.ofVirtual().name("build-start").start(held.take());
            assertTrue(first.ended.await(30, TimeUnit.SECONDS));
            onFx(() -> {});
            assertEquals(List.of("start", "out:built", "exit:0"), first.events());
            assertEquals(List.of(), second.events());
            assertTrue(held.isEmpty());
            assertFalse(service.isRunning());
        } finally {
            onFx(service::shutdown);
        }
    }
}
