package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import com.pty4j.PtyProcessBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The integration scripts in a real bash and zsh on a pseudo-terminal, with a scratch HOME so the account's own
 * startup files stay out of it: the shell reports its directory as it changes, marks each prompt, and reports a
 * command's exit status.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "bash/zsh integration is for POSIX shells")
class ShellIntegrationPtyTest {

    @TempDir
    Path home;

    @TempDir
    Path config;

    private final List<String> events = new CopyOnWriteArrayList<>();

    @Test
    void bashReportsItsDirectoryPromptsAndExitStatus() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "no bash");
        run("/bin/bash");
    }

    @Test
    void zshReportsItsDirectoryPromptsAndExitStatus() throws Exception {
        Path zsh = Files.isExecutable(Path.of("/bin/zsh")) ? Path.of("/bin/zsh") : Path.of("/usr/bin/zsh");
        assumeTrue(Files.isExecutable(zsh), "no zsh on this machine");
        run(zsh.toString());
    }

    private void run(String shell) throws Exception {
        Path integration = ShellIntegration.installLocal(config);
        Path target = Files.createDirectories(home.resolve("my dir")).toRealPath();
        var env = new HashMap<>(System.getenv());
        env.put("HOME", home.toString());
        env.put("TERM", "xterm-256color");
        env.remove("ZDOTDIR");
        env.remove("PROMPT_COMMAND");
        List<String> argv = LocalShell.integrated(List.of(shell, "-l"), integration, env);
        var process = new PtyProcessBuilder(argv.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(home.toString())
                .setInitialColumns(100)
                .setInitialRows(30)
                .setConsole(false)
                .start();
        TappedConnector connector = new TappedConnector(new LocalPtyConnector(process, argv), new OscTap.Listener() {
            @Override
            public void cwd(String url) {
                events.add("cwd " + ShellIntegration.pathOfCwdUrl(url));
            }

            @Override
            public void promptMark(char kind, String params) {
                events.add("mark " + kind + (params.isEmpty() ? "" : " " + params));
            }
        });
        Thread reader = Thread.ofVirtual().start(() -> {
            char[] buf = new char[4096];
            try {
                while (connector.read(buf, 0, buf.length) >= 0) {
                    // drain
                }
            } catch (Exception ended) {
                // closed
            }
        });
        try {
            await("a first prompt", () -> events.contains("mark A"));
            connector.write(("cd '" + target + "'\n").getBytes(StandardCharsets.UTF_8));
            await("the new directory reported", () -> events.contains("cwd " + target));
            connector.write("false\n".getBytes(StandardCharsets.UTF_8));
            await("the failed command's status", () -> events.contains("mark D 1"));
            // The next prompt's mark comes a moment after the status, so wait for it rather than count at once.
            await(
                    "a mark per prompt",
                    () -> events.stream().filter("mark A"::equals).count() >= 3);
            // bash before 4.4 (macOS's /bin/bash 3.2) has no PS0, so no output mark; zsh and newer bash do.
            if (!shell.endsWith("bash") || bashAtLeast44(shell)) {
                await("the output mark", () -> events.contains("mark C"));
            }
        } finally {
            connector.close();
            reader.join(5000);
        }
    }

    private static boolean bashAtLeast44(String bash) throws Exception {
        Process p = new ProcessBuilder(bash, "-c", "echo ${BASH_VERSINFO[0]} ${BASH_VERSINFO[1]}").start();
        String[] v = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .strip()
                .split(" ");
        p.waitFor();
        int major = Integer.parseInt(v[0]);
        return major > 4 || (major == 4 && Integer.parseInt(v[1]) >= 4);
    }

    private void await(String what, java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !done.getAsBoolean()) {
            Thread.sleep(20);
        }
        assertTrue(done.getAsBoolean(), () -> "timed out waiting for " + what + ": " + events);
    }
}
