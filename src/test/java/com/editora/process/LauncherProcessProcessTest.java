package com.editora.process;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A command started through a launcher ({@link LauncherProcess}): here the launcher is nothing at all, so the
 * {@link PosixShell} script a container or WSL distribution runs runs on this machine, and Stop signals with
 * this machine's {@code kill}, as a second {@code docker exec} would there.
 */
class LauncherProcessProcessTest {

    private static InputStream err(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void thePidLineIsReadPastWhatTheLoginPrintedFirst() throws Exception {
        assertEquals(4242, LauncherProcess.readPidLine(err("motd\n" + PosixShell.PID_MARK + "4242\nmore")));
    }

    @Test
    void aCommandThatNeverStartedSaysWhatTheLauncherSaid() {
        IOException said = assertThrows(
                IOException.class, () -> LauncherProcess.readPidLine(err("Error: No such container: web\n")));
        assertEquals("Error: No such container: web", said.getMessage());

        IOException silent = assertThrows(IOException.class, () -> LauncherProcess.readPidLine(err("")));
        assertEquals("the command did not start", silent.getMessage());

        IOException cut =
                assertThrows(IOException.class, () -> LauncherProcess.readPidLine(err(PosixShell.PID_MARK + "12")));
        assertEquals("the command did not start", cut.getMessage(), "the line never ended");

        IOException garbled = assertThrows(
                IOException.class, () -> LauncherProcess.readPidLine(err(PosixShell.PID_MARK + "twelve\n")));
        assertEquals("the command did not report a process id", garbled.getMessage());
    }

    private static Process launch(List<String> argv, Path cwd) throws IOException {
        ExecSpec spec = ExecSpec.of(argv, cwd, Map.of(), ExecSpec.Locale.PARSE_STABLE);
        return new ProcessBuilder(
                        PosixShell.argv(spec, cwd == null ? null : cwd.toString(), System.getenv("PATH"), null, null))
                .start();
    }

    /** Signals the group as a launcher's second command does, here. */
    private static boolean kill(long pid, boolean force) {
        try {
            return new ProcessBuilder(PosixShell.killArgv(pid, force)).start().waitFor(10, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void theProgramsStreamsAndExitAreTheLaunchers(@TempDir Path dir) throws Exception {
        LauncherProcess p = LauncherProcess.start(
                "test:here",
                launch(List.of("sh", "-c", "pwd -P; read line; echo \"got $line\"; echo oops >&2; exit 3"), dir),
                Duration.ofSeconds(10),
                LauncherProcessProcessTest::kill);

        assertEquals("test:here", p.targetId());
        assertTrue(p.remotePid() > 1);
        assertFalse(p.connectionLost());
        assertTrue(p.isAlive());
        p.getOutputStream().write("hola\n".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String errText = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        assertEquals(3, p.waitFor());
        assertEquals(3, p.exitValue());
        assertFalse(p.isAlive());
        assertSame(p, p.onExit().get(10, TimeUnit.SECONDS));
        assertEquals(dir.toRealPath() + "\ngot hola\n", out);
        assertEquals("oops\n", errText, "the pid line is not the program's");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void stoppingSignalsTheProgramsGroupWhereItRuns() throws Exception {
        AtomicReference<Long> signalled = new AtomicReference<>();
        LauncherProcess p = LauncherProcess.start(
                "test:here",
                launch(List.of("sh", "-c", "sleep 300 & sleep 301; wait"), null),
                Duration.ofSeconds(10),
                (pid, force) -> {
                    signalled.set(pid);
                    return kill(pid, force);
                });

        p.signalTree(false);

        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "TERM to the group ended it");
        assertEquals(p.remotePid(), signalled.get());
        p.signalTree(true); // already over: nothing is signalled again
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aProgramThatCannotBeReachedIsAtLeastNoLongerWaitedOn() throws Exception {
        LauncherProcess p = LauncherProcess.start(
                "test:here", launch(List.of("sleep", "300"), null), Duration.ofSeconds(10), (pid, force) -> false);

        p.signalTree(false);
        assertFalse(p.waitFor(300, TimeUnit.MILLISECONDS), "a polite stop that failed leaves it be");
        p.signalTree(true);

        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "a forced one ends the launcher (here, the program itself)");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLauncherThatNeverStartsTheCommandIsGivenUpOn() throws Exception {
        Process silent = new ProcessBuilder("sleep", "300").start();

        IOException e = assertThrows(
                IOException.class,
                () -> LauncherProcess.start("test:here", silent, Duration.ofMillis(300), (pid, force) -> true));

        assertEquals("the command did not start", e.getMessage());
        assertTrue(silent.waitFor(10, TimeUnit.SECONDS), "the launcher was stopped");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLauncherThatFailsSaysWhy() throws Exception {
        Process failing = new ProcessBuilder("sh", "-c", "echo 'Error: No such container: web' >&2; exit 1").start();

        IOException e = assertThrows(
                IOException.class,
                () -> LauncherProcess.start("test:here", failing, Duration.ofSeconds(10), (pid, force) -> true));

        assertEquals("Error: No such container: web", e.getMessage());
    }
}
