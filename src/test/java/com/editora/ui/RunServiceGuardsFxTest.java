package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.run.RunService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RunService} when there is nothing to run or nothing running: requests it must ignore, a launch that
 * cannot start, and the Java version probe answering from what it already learned.
 */
@Tag("fx")
class RunServiceGuardsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
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
            events.add((stderr ? "err:" : "out:") + line);
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

    @Test
    void withNothingToRunOrNobodyListeningNothingIsStarted(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        Recorder listener = new Recorder();
        try {
            FxTestSupport.runOnFx(() -> {
                service.run(null, List.of("anything"), listener);
                service.runInDir(dir, List.of(), listener);
                service.runInDir(dir, null, listener);
                service.runInDir(dir, List.of("anything"), null);
                service.sendInput("typed with nothing running");
                service.closeInput();
                service.stop();
            });

            assertFalse(service.isRunning());
            assertEquals(-1, service.pid());
            assertEquals(List.of(), listener.events());
        } finally {
            FxTestSupport.runOnFx(service::shutdown);
        }
    }

    @Test
    void aProgramThatCannotBeStartedIsReportedAndLeavesTheServiceFree(@TempDir Path dir) throws Exception {
        RunService service = new RunService();
        Recorder listener = new Recorder();
        String missing = dir.resolve("no-such-program").toString();
        try {
            FxTestSupport.runOnFx(() -> service.run(dir.resolve("file.txt"), List.of(missing, "arg"), listener));

            assertTrue(listener.ended.await(20, TimeUnit.SECONDS));
            List<String> events = listener.events();
            assertEquals(1, events.size(), events.toString());
            assertTrue(events.get(0).startsWith("error:") && events.get(0).contains("no-such-program"), events.get(0));
            assertFalse(service.isRunning(), "a failed launch does not block the next one");
        } finally {
            FxTestSupport.runOnFx(service::shutdown);
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the stand-in java is a /bin/sh script
    void theJavaVersionIsProbedOncePerLauncherAndAnsweredFromMemoryAfterThat(@TempDir Path dir) throws Exception {
        Path counter = dir.resolve("probes.txt");
        Path java = dir.resolve("java");
        Files.writeString(java, "#!/bin/sh\necho probed >> '" + counter + "'\necho 'java version \"1.8.0_392\"' >&2\n");
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"));
        RunService service = new RunService();
        try {
            CountDownLatch first = new CountDownLatch(1);
            int[] majors = {0, 0};
            FxTestSupport.runOnFx(() -> service.detectJavaMajor(java.toString(), major -> {
                majors[0] = major;
                first.countDown();
            }));
            assertTrue(first.await(30, TimeUnit.SECONDS));
            assertEquals(8, majors[0], "the legacy 1.x scheme names its major second");

            boolean[] answeredAtOnce = {false};
            FxTestSupport.runOnFx(() -> {
                service.detectJavaMajor(java.toString(), major -> majors[1] = major);
                answeredAtOnce[0] = majors[1] == 8;
            });
            assertTrue(answeredAtOnce[0], "the second question needs no process");
            assertEquals(List.of("probed"), Files.readAllLines(counter));
        } finally {
            FxTestSupport.runOnFx(service::shutdown);
        }
    }

    @Test
    void aJavaVersionIsReadFromWhateverTheLauncherPrints() {
        assertEquals(25, RunService.javaMajorOf("openjdk version \"25.0.3\" 2026-04-21"));
        assertEquals(21, RunService.javaMajorOf("java version \"21\" 2023-09-19 LTS"));
        assertEquals(8, RunService.javaMajorOf("java version \"1.8.0_392\""));
        assertEquals(1, RunService.javaMajorOf("java version \"1\""), "a lone 1 is taken as it stands");
        assertEquals(-1, RunService.javaMajorOf("command not found: java"));
        assertEquals(-1, RunService.javaMajorOf(null));
    }
}
