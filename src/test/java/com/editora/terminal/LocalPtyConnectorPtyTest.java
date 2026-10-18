package com.editora.terminal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.editora.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a local terminal's process ends. A program (Run in Terminal) is ended as console Run ends one, so one that
 * ignores SIGTERM and SIGHUP is still killed; a shell is hung up, so a job its user detached keeps running.
 */
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "POSIX signals and sh")
class LocalPtyConnectorPtyTest {

    @Test
    void stoppingAProgramThatIgnoresTermAndHupStillEndsIt() throws Exception {
        LocalPtyConnector connector = LocalShell.startProgram(
                List.of("/bin/sh", "-c", "trap '' TERM HUP INT; echo ready; while :; do sleep 1; done"),
                null,
                80,
                24,
                null);
        Process program = connector.trackableProcess();
        try {
            ProcessRegistry.track(program);
            await(connector, "ready");

            connector.close(); // Stop, or closing its tab

            assertTrue(
                    program.waitFor(1_500 + 5_000, TimeUnit.MILLISECONDS),
                    "a program that ignores the polite signals is killed after the grace period");
        } finally {
            program.destroyForcibly();
        }
    }

    @Test
    void closingAShellLeavesTheJobItsUserDetached() throws Exception {
        LocalPtyConnector connector = LocalShell.start(List.of("/bin/sh", "-i"), null, 80, 24);
        Process shell = connector.trackableProcess();
        ProcessHandle job = null;
        try {
            byte[] line = "nohup sleep 300 >/dev/null 2>&1 & echo JOB=$!\n".getBytes(StandardCharsets.UTF_8);
            connector.write(line);
            Matcher m = Pattern.compile("JOB=(\\d+)").matcher(await(connector, "JOB=\\d+"));
            assertTrue(m.find());
            job = ProcessHandle.of(Long.parseLong(m.group(1))).orElseThrow();

            connector.close();

            assertTrue(shell.waitFor(10, TimeUnit.SECONDS), "the shell ends");
            assertTrue(job.isAlive(), "the nohup job is the user's, and keeps running");
        } finally {
            if (job != null) job.destroyForcibly();
            shell.destroyForcibly();
        }
    }

    /** Reads until {@code regex} appears in the output; returns what was read. */
    private static String await(LocalPtyConnector connector, String regex) throws Exception {
        ByteArrayOutputStream seen = new ByteArrayOutputStream();
        Pattern pattern = Pattern.compile(regex);
        Thread reader = Thread.ofVirtual().start(() -> {
            char[] buf = new char[1024];
            try {
                int n;
                while ((n = connector.read(buf, 0, buf.length)) > 0) {
                    synchronized (seen) {
                        seen.writeBytes(new String(buf, 0, n).getBytes(StandardCharsets.UTF_8));
                    }
                    if (pattern.matcher(seen.toString(StandardCharsets.UTF_8)).find()) {
                        return;
                    }
                }
            } catch (java.io.IOException ended) {
                // closed
            }
        });
        reader.join(java.time.Duration.ofSeconds(20));
        String text;
        synchronized (seen) {
            text = seen.toString(StandardCharsets.UTF_8);
        }
        assertTrue(pattern.matcher(text).find(), () -> regex + " in: [" + text + "]");
        return text;
    }
}
