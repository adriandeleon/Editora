package com.editora.process;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command via {@link ProcessBuilder}, capturing stdout, stderr, and the exit code
 * with a hard timeout. Both streams are drained concurrently so a chatty command can't deadlock on a
 * full pipe buffer.
 *
 * <p>This is the single place Editora shells out (used by {@code git} integration and the Mermaid
 * CLI). It does no threading itself beyond the stderr drainer, so callers must run it off the JavaFX
 * thread (see {@code GitService} / {@code MermaidService}). {@code LC_ALL=C} is always set so output
 * parses the same regardless of the user's locale; feature-specific env vars go through the
 * {@link #run(Path, Duration, List, Map)} overload.
 */
public final class ProcessRunner {

    /** Outcome of one command: process {@code exit} code plus its captured {@code out}/{@code err}. */
    public record Result(int exit, String out, String err) {
        public boolean ok() {
            return exit == 0;
        }

        /** A human-readable error: stderr if present, else stdout, trimmed. */
        public String message() {
            String e = err == null ? "" : err.strip();
            return e.isEmpty() ? (out == null ? "" : out.strip()) : e;
        }
    }

    private ProcessRunner() {
    }

    /**
     * Runs {@code command} in {@code workingDir} (may be {@code null} for the JVM's cwd), waiting at
     * most {@code timeout}. On timeout the process is destroyed and a non-zero {@link Result} returned.
     */
    public static Result run(Path workingDir, Duration timeout, List<String> command) {
        return run(workingDir, timeout, command, Map.of());
    }

    /**
     * As {@link #run(Path, Duration, List)} but with {@code extraEnv} merged into the child process's
     * environment (on top of the inherited environment + {@code LC_ALL=C}).
     */
    public static Result run(Path workingDir, Duration timeout, List<String> command,
            Map<String, String> extraEnv) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        // Keep the locale stable so we parse English output regardless of the user's environment.
        pb.environment().put("LC_ALL", "C");
        pb.environment().putAll(extraEnv);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, "", e.getMessage() == null ? "failed to start" : e.getMessage());
        }
        // Drain stderr on a side thread while we read stdout, so neither pipe can fill and stall.
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errReader = new Thread(() -> drain(process.getErrorStream(), errBuf), "proc-stderr");
        errReader.setDaemon(true);
        errReader.start();

        ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        drain(process.getInputStream(), outBuf);

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new Result(-1, outBuf.toString(StandardCharsets.UTF_8), "command timed out");
            }
            errReader.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, "", "interrupted");
        }
        return new Result(process.exitValue(),
                outBuf.toString(StandardCharsets.UTF_8),
                errBuf.toString(StandardCharsets.UTF_8));
    }

    private static void drain(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[8192];
        try (in) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } catch (IOException ignored) {
            // Stream closed early (process exited); whatever we captured is good enough.
        }
    }
}
