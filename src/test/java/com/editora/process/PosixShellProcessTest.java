package com.editora.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hands {@link PosixShell}'s command line to a real {@code sh}, the way an SSH server hands it to a login shell. */
@DisabledOnOs(OS.WINDOWS)
class PosixShellProcessTest {

    private record Ran(int exit, String out, String err) {}

    private static Ran run(String commandLine) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("/bin/sh", "-c", commandLine).start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(10, TimeUnit.SECONDS));
        return new Ran(p.exitValue(), out, err);
    }

    @Test
    void everyArgumentArrivesExactlyAsGiven() throws Exception {
        List<String> awkward =
                List.of("plain", "", "two words", "it's", "$HOME `id` $(id)", "back\\slash", "a\nb", "*", "ñ");
        List<String> argv = new java.util.ArrayList<>(List.of("printf", "[%s]\\n"));
        argv.addAll(awkward);
        ExecSpec spec = ExecSpec.of(argv, null, Map.of(), ExecSpec.Locale.USER);

        Ran ran = run(PosixShell.commandLine(spec, null, null, null));

        StringBuilder expected = new StringBuilder();
        awkward.forEach(a -> expected.append('[').append(a).append("]\n"));
        assertEquals(0, ran.exit(), ran.err());
        assertEquals(expected.toString(), ran.out());
    }

    @Test
    void theChildRunsInTheDirectoryWithTheEnvironmentAsked(@TempDir Path dir) throws Exception {
        Path cwd = Files.createDirectory(dir.resolve("my 'app'"));
        Map<String, String> env = new LinkedHashMap<>();
        env.put("GREETING", "hola $USER");
        env.put("HOME", null);
        ExecSpec spec = ExecSpec.of(
                List.of("sh", "-c", "pwd -P; echo \"$GREETING|${HOME-unset}|$LC_ALL\""),
                null,
                env,
                ExecSpec.Locale.PARSE_STABLE);

        Ran ran = run(PosixShell.commandLine(spec, cwd.toString(), System.getenv("PATH"), null));

        assertEquals(0, ran.exit(), ran.err());
        assertEquals(cwd.toRealPath() + "\nhola $USER|unset|C\n", ran.out());
    }

    @Test
    void thePidLineIsTheChildsOwnPid() throws Exception {
        ExecSpec spec = ExecSpec.of(List.of("sh", "-c", "echo $$"), null, Map.of(), ExecSpec.Locale.USER);

        Ran ran = run(PosixShell.commandLine(spec, null, null, null));

        // `exec "$@"` replaces the wrapper, so the pid it announced is the pid of what is now running.
        assertEquals(PosixShell.PID_MARK + ran.out().strip() + "\n", ran.err());
    }

    @Test
    void aMissingDirectoryFailsBeforeTheCommandRuns(@TempDir Path dir) throws Exception {
        ExecSpec spec = ExecSpec.of(List.of("echo", "ran"), null, Map.of(), ExecSpec.Locale.USER);

        Ran ran = run(PosixShell.commandLine(spec, dir.resolve("gone").toString(), null, null));

        assertEquals(126, ran.exit());
        assertEquals("", ran.out());
    }

    @Test
    void discardedOutputNeverReachesThePipes() throws Exception {
        ExecSpec spec = ExecSpec.of(List.of("sh", "-c", "echo out; echo err >&2"), null, Map.of(), ExecSpec.Locale.USER)
                .withDiscardedOutput(true, true);

        Ran ran = run(PosixShell.commandLine(spec, null, null, null));

        assertEquals("", ran.out());
        assertTrue(
                ran.err().startsWith(PosixShell.PID_MARK) && ran.err().lines().count() == 1, ran.err());
    }

    /**
     * Starts {@code spec} the way an SSH server does, as the child of a process (standing in for the
     * per-connection server process) that the test can kill: in its own session and group as OpenSSH does,
     * or, as dropbear does, in the group of that parent. Returns the parent; the child's pid is read from its
     * pid line.
     */
    private static Process startUnderAParent(ExecSpec spec, boolean ownGroup) throws IOException {
        String line = PosixShell.commandLine(spec, null, null, null);
        String start = (ownGroup ? "setsid " : "") + "/bin/sh -c " + PosixShell.quote(line);
        return new ProcessBuilder("/bin/sh", "-c", start + " & wait").start();
    }

    private static long groupOf(long pid) throws IOException, InterruptedException {
        Process ps = new ProcessBuilder("ps", "-o", "pgid=", "-p", String.valueOf(pid)).start();
        String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        ps.waitFor(5, TimeUnit.SECONDS);
        return Long.parseLong(out);
    }

    private static long pidLine(Process parent) throws IOException {
        var reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(parent.getErrorStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertTrue(line != null && line.startsWith(PosixShell.PID_MARK), "no pid line: " + line);
        return Long.parseLong(line.substring(PosixShell.PID_MARK.length()).strip());
    }

    private static boolean goneWithin(long pid, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            if (ProcessHandle.of(pid).map(h -> !h.isAlive()).orElse(true)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private static final List<String> TREE = List.of("sh", "-c", "sleep 600 & exec sleep 600");

    /** dropbear leaves the command in its own group; the command line leaves it for one of its own, same pid. */
    @Test
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX) // setsid(1)
    void theCommandLeadsAGroupEvenWhenTheServerKeepsItInItsOwn() throws Exception {
        Process parent =
                startUnderAParent(ExecSpec.of(List.of("sleep", "600"), null, Map.of(), ExecSpec.Locale.USER), false);
        long pid = pidLine(parent);
        try {
            assertEquals(pid, groupOf(pid), "the pid it announced leads its group");
            assertTrue(groupOf(parent.pid()) != pid);
            assertEquals(
                    "sleep",
                    ProcessHandle.of(pid)
                            .orElseThrow()
                            .info()
                            .command()
                            .map(c -> Path.of(c).getFileName().toString())
                            .orElse("?"));
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            parent.destroyForcibly();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "server gives it a group: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX) // setsid(1)
    void aLongLivedTreeDiesWhenTheProcessThatStartedItDoes(boolean ownGroup) throws Exception {
        Process parent = startUnderAParent(
                ExecSpec.of(TREE, null, Map.of(), ExecSpec.Locale.USER).withLongLived(true), ownGroup);
        long pid = pidLine(parent);
        List<ProcessHandle> group = new java.util.ArrayList<>(
                ProcessHandle.of(pid).orElseThrow().children().toList());

        parent.destroyForcibly(); // the connection drops

        assertTrue(goneWithin(pid, 8000), "the command outlived its connection");
        for (ProcessHandle member : group) {
            assertTrue(goneWithin(member.pid(), 8000), "a child of the command outlived the connection");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "server gives it a group: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @org.junit.jupiter.api.condition.EnabledOnOs(OS.LINUX) // setsid(1)
    void aOneShotCommandIsLeftToFinishAndTheKillCommandStopsItsGroup(boolean ownGroup) throws Exception {
        Process parent = startUnderAParent(ExecSpec.of(TREE, null, Map.of(), ExecSpec.Locale.USER), ownGroup);
        long pid = pidLine(parent);
        List<ProcessHandle> children =
                ProcessHandle.of(pid).orElseThrow().children().toList();
        try {
            parent.destroyForcibly();
            assertFalse(goneWithin(pid, 3000), "no watchdog: a one-shot command may finish after a drop");

            assertEquals(0, run(PosixShell.killCommandLine(pid, false)).exit());

            assertTrue(goneWithin(pid, 3000));
            for (ProcessHandle child : children) {
                assertTrue(goneWithin(child.pid(), 3000), "the kill reached only the group leader");
            }
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            children.forEach(ProcessHandle::destroyForcibly);
        }
    }
}
