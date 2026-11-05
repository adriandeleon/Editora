package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * TEMPORARY, log-only: what a SIGKILLed pty program whose output nobody reads does on each OS. Prints
 * {@code PTY-PROBE} lines and asserts nothing. To be removed before merge.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX sh")
class UnreadPtyExitProbeTest {

    @Test
    void probe() throws Exception {
        LocalPtyConnector connector = LocalShell.startProgram(
                List.of("/bin/sh", "-c", "echo ready; while :; do echo tick; sleep 0.2; done"), null, 80, 24, Map.of());
        Process program = connector.trackableProcess();
        long pid = program.pid();
        try {
            char[] buf = new char[256];
            StringBuilder seen = new StringBuilder();
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!seen.toString().contains("ready") && System.nanoTime() < until) {
                int n = connector.read(buf, 0, buf.length);
                if (n <= 0) break;
                seen.append(buf, 0, n);
            }
            Thread.sleep(1_000); // unread ticks pile up in the terminal
            ProcessHandle.of(pid).ifPresent(h -> {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
                h.destroyForcibly();
            });
            boolean goneAfterKill = program.waitFor(3, TimeUnit.SECONDS);
            log("os=" + System.getProperty("os.name") + " gone 3 s after SIGKILL: " + goneAfterKill);
            log("ps after SIGKILL: " + ps(pid));
            for (var s : List.of(program.getOutputStream(), program.getInputStream(), program.getErrorStream())) {
                try {
                    s.close();
                } catch (java.io.IOException ignored) {
                    // closed
                }
            }
            boolean goneAfterClose = program.waitFor(3, TimeUnit.SECONDS);
            log("gone 3 s after closing the terminal: " + goneAfterClose);
            log("ps after close: " + ps(pid));
        } finally {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
            connector.close();
        }
    }

    private static String ps(long pid) {
        try {
            Process ps = new ProcessBuilder("ps", "-o", "pid,stat,wchan,comm", "-p", Long.toString(pid))
                    .redirectErrorStream(true)
                    .start();
            String out = new String(ps.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            ps.waitFor(5, TimeUnit.SECONDS);
            return out.replace('\n', '|');
        } catch (Exception e) {
            return e.toString();
        }
    }

    private static void log(String line) {
        System.out.println("PTY-PROBE " + line);
    }
}
