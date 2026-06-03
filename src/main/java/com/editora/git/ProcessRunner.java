package com.editora.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs an external command (here, {@code git}) via {@link ProcessBuilder}, capturing stdout, stderr,
 * and the exit code with a hard timeout. Both streams are drained concurrently so a chatty command
 * can't deadlock on a full pipe buffer.
 *
 * <p>This is the only place Editora shells out. Callers run it off the JavaFX thread (see
 * {@link GitService}); it does no threading itself beyond the stderr drainer.
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
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toFile());
        }
        // Keep the locale stable so we parse git's English output regardless of the user's environment.
        pb.environment().put("LC_ALL", "C");
        pb.environment().put("GIT_OPTIONAL_LOCKS", "0"); // status must never block on the index lock
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, "", e.getMessage() == null ? "failed to start" : e.getMessage());
        }
        // Drain stderr on a side thread while we read stdout, so neither pipe can fill and stall.
        ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        Thread errReader = new Thread(() -> drain(process.getErrorStream(), errBuf), "git-stderr");
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
