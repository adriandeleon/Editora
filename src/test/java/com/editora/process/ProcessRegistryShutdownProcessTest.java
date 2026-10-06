package com.editora.process;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the shutdown hook does to tracked children when the app quits.
 *
 * <p>It used to force-kill every tree at once, so a language server never got to release its workspace lock
 * or flush its index and a running program was cut off mid-write. It now asks first (SIGTERM), waits a bounded
 * moment, and force-kills only what is still alive.
 *
 * <p>POSIX signals and a shell stand-in, so Windows sits this out.
 */
@DisabledOnOs(OS.WINDOWS)
class ProcessRegistryShutdownProcessTest {

    /** Starts {@code script} under {@code sh} and returns once it has printed its "ready" line. */
    private static Process startAndAwaitReady(String script, Path marker) throws Exception {
        Process p = new ProcessBuilder("sh", "-c", script, marker.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("ready", out.readLine(), "the stand-in must have installed its signal handling first");
        return p;
    }

    @Test
    void aChildThatHandlesSigtermGetsToShutDownCleanly(@TempDir Path dir) throws Exception {
        Path marker = dir.resolve("clean-shutdown");
        // "$0" is the marker: written only if the TERM handler actually ran.
        Process child = startAndAwaitReady(
                "trap 'echo done > \"$0\"; exit 0' TERM; echo ready; while :; do sleep 0.05; done", marker);
        try {
            ProcessRegistry.track(child);

            ProcessRegistry.killAll();

            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertTrue(Files.exists(marker), "the child must be asked to stop (SIGTERM) before it is force-killed");
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void aChildThatIgnoresSigtermIsStillKilledAfterTheBoundedWait(@TempDir Path dir) throws Exception {
        Process child = startAndAwaitReady("trap '' TERM; echo ready; while :; do sleep 0.05; done", dir.resolve("x"));
        try {
            ProcessRegistry.track(child);

            long t0 = System.nanoTime();
            ProcessRegistry.killAll();
            long waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);

            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "a stubborn child must not survive the app");
            assertTrue(waitedMs >= ProcessRegistry.GRACE_MS - 100, "it is given the grace period first: " + waitedMs);
            assertTrue(waitedMs < ProcessRegistry.GRACE_MS + 5_000, "and the wait is bounded: " + waitedMs + " ms");
        } finally {
            child.destroyForcibly();
        }
    }
}
