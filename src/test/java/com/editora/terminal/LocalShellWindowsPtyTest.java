package com.editora.terminal;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.jediterm.core.util.TermSize;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The integrated terminal's local shell on Windows (ConPTY): PowerShell starts at the size it was given, follows a
 * resize, and ends when its tab closes. The FX lanes do not run on Windows, so this pure
 * test is the only one that drives a Windows shell at all.
 */
@EnabledOnOs(OS.WINDOWS)
class LocalShellWindowsPtyTest {

    @Test
    void powerShellStartsAtItsSizeFollowsAResizeAndEnds() throws Exception {
        LocalPtyConnector shell = LocalShell.start(List.of(), null, 80, 24);
        String name = shell.getName().toLowerCase(java.util.Locale.ROOT);
        assumeTrue(name.contains("pwsh") || name.contains("powershell"), "no PowerShell on this machine: " + name);
        StringBuffer seen = new StringBuffer();
        Thread reader = Thread.ofVirtual().start(() -> {
            char[] buf = new char[4096];
            try {
                int n;
                while ((n = shell.read(buf, 0, buf.length)) > 0) {
                    seen.append(buf, 0, n);
                }
            } catch (java.io.IOException ended) {
                // closed
            }
        });
        Process process = shell.trackableProcess();
        try {
            typeUntil(shell, seen, "Write-Output (\"W=\" + [Console]::WindowWidth)\r", "W=80");
            shell.resize(new TermSize(120, 30));
            typeUntil(shell, seen, "Write-Output (\"W=\" + [Console]::WindowWidth)\r", "W=120");
            shell.close();

            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "the shell ended with its tab");
        } finally {
            process.destroyForcibly();
            reader.join(5000);
        }
    }

    /** Types {@code line} every few seconds until {@code needle} appears (a profile can swallow early input). */
    private static void typeUntil(LocalPtyConnector shell, StringBuffer seen, String line, String needle)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < deadline) {
            shell.write(line.getBytes(StandardCharsets.UTF_8));
            long wait = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < wait) {
                if (seen.toString().contains(needle)) return;
                Thread.sleep(50);
            }
        }
        assertTrue(seen.toString().contains(needle), () -> needle + " in: " + seen);
    }
}
