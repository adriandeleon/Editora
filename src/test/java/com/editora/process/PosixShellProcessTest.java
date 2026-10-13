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
}
