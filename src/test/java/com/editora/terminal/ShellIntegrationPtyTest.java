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
            await(
                    "a mark per prompt",
                    () -> events.stream().filter("mark A"::equals).count() >= 3);
            // bash before 4.4 (macOS's /bin/bash 3.2) has no PS0, so no output mark; zsh and newer bash do.
            if (!shell.endsWith("bash") || bashAtLeast44(shell)) {
                assertTrue(events.contains("mark C"), () -> "the output mark: " + events);
            }
        } finally {
            connector.close();
            reader.join(5000);
        }
    }

    @Test
    void bashKeepsTheUsersOwnStartupFilesAndBehavesAsALoginShell() throws Exception {
        assumeTrue(Files.isExecutable(Path.of("/bin/bash")), "no bash");
        assumeTrue(bashAtLeast44("/bin/bash"), "the command-number marks are bash 4.4+");
        // The user's own profile: an alias, a PROMPT_COMMAND of their own, Debian's default HISTCONTROL, a logout file.
        Files.writeString(
                home.resolve(".bash_profile"),
                "alias ll='echo ALIAS_OK'\npc_one() { echo PC_OK; }\nPROMPT_COMMAND=pc_one\nHISTCONTROL=ignoreboth\n");
        Files.writeString(home.resolve(".bash_logout"), "echo ran > \"$HOME/logout.txt\"\n");
        // An installer's .bash_profile that does not source .bashrc: its aliases still count.
        Files.writeString(home.resolve(".bashrc"), "alias rc='echo RC_OK'\n");
        Path odd = Files.createDirectories(home.resolve("d #1? [x]")).toRealPath();
        Path integration = ShellIntegration.installLocal(config);
        var env = new HashMap<>(System.getenv());
        env.put("HOME", home.toString());
        env.put("HISTFILE", "/dev/null");
        env.put("TERM", "xterm-256color");
        env.remove("PROMPT_COMMAND");
        List<String> argv = LocalShell.integrated(List.of("/bin/bash", "-l"), integration, env);
        var process = new PtyProcessBuilder(argv.toArray(String[]::new))
                .setEnvironment(env)
                .setDirectory(home.toString())
                .setInitialColumns(100)
                .setInitialRows(30)
                .setConsole(false)
                .start();
        StringBuffer out = new StringBuffer();
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
                int n;
                while ((n = connector.read(buf, 0, buf.length)) >= 0) {
                    out.append(buf, 0, n);
                }
            } catch (Exception ended) {
                // closed
            }
        });
        try {
            await("a first prompt", () -> events.contains("mark A"));
            assertTrue(
                    events.stream().noneMatch(e -> e.startsWith("mark D")), () -> "no D before a command: " + events);
            connector.write("rc\n".getBytes(StandardCharsets.UTF_8));
            await("~/.bashrc read although the profile does not source it", () -> out.indexOf("RC_OK") >= 0);
            connector.write("ll\n".getBytes(StandardCharsets.UTF_8));
            await(
                    "the alias and the user's PROMPT_COMMAND",
                    () -> out.indexOf("ALIAS_OK") >= 0 && out.indexOf("PC_OK") >= 0);
            connector.write(("cd '" + odd + "'\n").getBytes(StandardCharsets.UTF_8));
            await("a folder whose name has URL characters", () -> events.contains("cwd " + odd));
            long before = events.stream().filter(e -> e.startsWith("mark D")).count();
            connector.write("true\n".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(300);
            connector.write("true\n".getBytes(StandardCharsets.UTF_8));
            await(
                    "a D mark for a repeat that HISTCONTROL=ignoreboth keeps out of history",
                    () -> events.stream().filter(e -> e.startsWith("mark D")).count() >= before + 2);
            connector.write("logout\n".getBytes(StandardCharsets.UTF_8));
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), () -> "logout ends the shell: " + out);
            assertTrue(Files.exists(home.resolve("logout.txt")), "and ~/.bash_logout ran");
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
