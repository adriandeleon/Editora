package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.process.PosixShell;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The launcher channel a container or WSL terminal uses, with the start script run straight on a pseudo-terminal
 * here (no engine in between): the pid line is taken out and its pid kept, a resize reaches the shell, and closing
 * hangs that pid up before ending the launcher.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the start script is POSIX sh; WslProbeTest covers Windows")
class PtyChannelTest {

    @Test
    void theStartScriptsPidIsKeptAndItsLineHidden() throws Exception {
        CompletableFuture<Long> hungUp = new CompletableFuture<>();
        var env = new HashMap<>(System.getenv());
        env.put("SHELL", "/bin/sh");
        PtyChannel channel = PtyChannel.start(
                List.of("/bin/sh", "-c", PosixShell.terminalScript(null, true)), env, 100, 30, hungUp::complete);
        StringBuffer seen = new StringBuffer();
        Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = channel.output().read(buf)) > 0) {
                    seen.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException ended) {
                // closed
            }
        });
        try {
            type(channel, "stty size; echo $TERM\n");
            await(seen, "30 100");
            await(seen, "xterm-256color");
            assertFalse(seen.toString().contains("EDITORA"), "the pid line is hidden: " + seen);
            assertTrue(channel.pid() > 0, "the shell's pid was read");

            channel.resize(120, 40);
            type(channel, "stty size\n");
            await(seen, "40 120");
        } finally {
            channel.close();
        }
        assertEquals(channel.pid(), hungUp.get(10, TimeUnit.SECONDS), "closing hangs up that pid");
        assertTrue(waitForEnd(channel), "and ends the launcher");
    }

    @Test
    void theShellIsTrackedAndTheShutdownHookWaitsForItsHangUp() throws Exception {
        // A container keeps a shell whose exec client went, so a quit must not exit before the hang-up is sent.
        CompletableFuture<Long> hungUp = new CompletableFuture<>();
        CompletableFuture<Long> recorded = new CompletableFuture<>();
        var env = new HashMap<>(System.getenv());
        env.put("SHELL", "/bin/sh");
        PtyChannel channel = PtyChannel.start(
                List.of("/bin/sh", "-c", PosixShell.terminalScript(null, true)),
                env,
                80,
                24,
                pid -> {
                    try {
                        Thread.sleep(400); // a `docker exec kill` takes a while
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    hungUp.complete(pid);
                },
                "container:test",
                pid -> {
                    recorded.complete(pid);
                    return List.of("container", "id", "", Long.toString(pid), "1", "docker");
                });
        Thread.ofVirtual().start(() -> {
            try {
                channel.output().transferTo(java.io.OutputStream.nullOutputStream());
            } catch (java.io.IOException ended) {
                // closed
            }
        });
        var shell = channel.shell();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (System.nanoTime() < deadline && !com.editora.process.ProcessRegistry.isTracked(shell)) {
                Thread.sleep(20);
            }
            assertTrue(com.editora.process.ProcessRegistry.isTracked(shell), "tracked once its pid is known");
            assertEquals(channel.pid(), recorded.get(10, TimeUnit.SECONDS), "and recorded for a crash-time reap");

            channel.close(); // the window closing: the hang-up starts on its own thread
            shell.signalTreeNow(true, 5_000); // what the shutdown hook does with a stubborn remote process

            assertTrue(hungUp.isDone(), "the hook returned only once the hang-up was sent");
            assertTrue(waitForEnd(channel));
        } finally {
            channel.close();
        }
    }

    private static void type(PtyChannel channel, String text) throws java.io.IOException {
        channel.input().write(text.getBytes(StandardCharsets.UTF_8));
        channel.input().flush();
    }

    private static void await(StringBuffer seen, String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline && !seen.toString().contains(needle)) {
            Thread.sleep(20);
        }
        assertTrue(seen.toString().contains(needle), () -> "\"" + needle + "\" in: [" + seen + "]");
    }

    private static boolean waitForEnd(PtyChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline && channel.isOpen()) {
            Thread.sleep(20);
        }
        return !channel.isOpen();
    }
}
