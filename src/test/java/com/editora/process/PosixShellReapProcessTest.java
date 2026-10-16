package com.editora.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The commands the next Editora runs on a container or a WSL distribution to stop a program a crashed one
 * left there ({@link PosixShell#startTimeArgv}, {@link PosixShell#reapArgv}), run here against real
 * processes. They read {@code /proc}, as Linux has it: containers and WSL distributions are Linux.
 */
@EnabledOnOs(OS.LINUX)
class PosixShellReapProcessTest {

    private record Ran(int exit, String out) {}

    private static Ran run(List<String> argv) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(argv).redirectErrorStream(true).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        return new Ran(p.exitValue(), out);
    }

    /** Field 22 of {@code /proc/<pid>/stat}, read here. */
    private static String startTimeOf(long pid) throws IOException {
        String stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
        return stat.substring(stat.lastIndexOf(") ") + 2).split(" ")[19];
    }

    /** A program started as the launcher starts one: the leader of its own group, with a child in it. */
    private static LauncherProcess program() throws IOException {
        ExecSpec spec = ExecSpec.of(
                List.of("sh", "-c", "sleep 300 & sleep 301; wait"), null, Map.of(), ExecSpec.Locale.PARSE_STABLE);
        Process client = new ProcessBuilder(PosixShell.argv(spec, null, System.getenv("PATH"), null, null)).start();
        return LauncherProcess.start("test:here", client, Duration.ofSeconds(10), (pid, force) -> false);
    }

    @Test
    void theStartTimeIsTheKernelsForThatPid(@TempDir Path dir) throws Exception {
        // A command name may hold spaces and parentheses: only the last ") " ends it.
        Path odd = Files.copy(Path.of("/bin/sleep"), dir.resolve("odd) n (x"));
        odd.toFile().setExecutable(true);
        Process p = new ProcessBuilder(odd.toString(), "60").start();
        try {
            Ran ran = run(PosixShell.startTimeArgv(p.pid()));

            assertEquals(0, ran.exit(), ran.out());
            assertEquals(startTimeOf(p.pid()), ran.out().strip());
        } finally {
            p.destroyForcibly();
        }
    }

    @Test
    void aProgramIsStoppedWithItsGroupOnlyWhileItIsTheOneRecorded() throws Exception {
        LauncherProcess p = program();
        long pid = p.remotePid();
        List<ProcessHandle> group =
                ProcessHandle.of(pid).orElseThrow().descendants().toList();
        assertFalse(group.isEmpty(), "the program has a child in its group");
        String start = run(PosixShell.startTimeArgv(pid)).out().strip();

        Ran other = run(PosixShell.reapArgv(pid, start + "1"));
        assertEquals(3, other.exit(), "another start time: a reused pid, left alone");
        assertTrue(p.isAlive());

        Ran reaped = run(PosixShell.reapArgv(pid, start));
        assertEquals(0, reaped.exit(), reaped.out());
        assertTrue(p.waitFor(10, TimeUnit.SECONDS), "the program ended");
        for (ProcessHandle child : group) {
            for (int i = 0; i < 100 && child.isAlive(); i++) {
                Thread.sleep(50);
            }
            assertFalse(child.isAlive(), "and the rest of its group with it");
        }

        assertEquals(3, run(PosixShell.reapArgv(pid, start)).exit(), "gone: nothing to stop");
    }

    @Test
    void aRecordedProgramCanBeAskedForItsRecord() throws Exception {
        ExecSpec spec = ExecSpec.of(List.of("sleep", "300"), null, Map.of(), ExecSpec.Locale.PARSE_STABLE);
        Process client = new ProcessBuilder(PosixShell.argv(spec, null, System.getenv("PATH"), null, null)).start();
        LauncherProcess p = LauncherProcess.start(
                "test:here",
                client,
                Duration.ofSeconds(10),
                (pid, force) -> false,
                pid -> List.of("test-kind", Long.toString(pid), startTimeOf(pid)));
        try {
            List<String> record = p.orphanRecord();

            assertEquals(List.of("test-kind", Long.toString(p.remotePid()), startTimeOf(p.remotePid())), record);
        } finally {
            run(PosixShell.reapArgv(p.remotePid(), startTimeOf(p.remotePid())));
            assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        }
        assertEquals(null, p.orphanRecord(), "a program that ended has nothing to record");
    }
}
