package com.editora.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.process.ProcessRunner;

/**
 * Runs a single Java source file via the JDK's source-file launcher ({@code java <file.java>}) and streams
 * its stdout/stderr live to a {@link Listener} on the JavaFX thread — the proper UX for a possibly
 * long-running program (versus capture-and-wait). One process at a time; {@link #stop()} kills it. A
 * monotonically increasing generation guards against a stopped/superseded process's late lines leaking to
 * the panel.
 *
 * <p>This reuses {@link ProcessRunner}'s PATH augmentation + bare-command resolution so a GUI-launched
 * {@code .app} finds {@code java} (Homebrew/SDKMAN/etc.) the same way Git/Mermaid do. For a Java 25
 * compact source file the launcher compiles it in memory and runs its top-level {@code main}; no
 * {@code --enable-preview} is needed (JEP 512 is final in 25), the launching {@code java} just has to be
 * JDK 25+.
 */
public final class RunService {

    /** Receives lifecycle + streamed output, always on the FX thread. */
    public interface Listener {
        /** The process started; {@code commandLine} is the resolved command for display. */
        void onStart(String commandLine);

        /** One line of program output ({@code stderr} true for the error stream). */
        void onOutput(String line, boolean stderr);

        /** The process exited with {@code code} (or {@code -1} if killed). */
        void onExit(int code);

        /** The process could not be launched (e.g. {@code java} not found). */
        void onError(String message);
    }

    private volatile Process current;
    private volatile int generation;

    /** True while a launched process is still alive. */
    public boolean isRunning() {
        Process p = current;
        return p != null && p.isAlive();
    }

    /**
     * Launches {@code argv} (e.g. {@code [java, <file>]} or {@code [python3, <file>]}) in {@code file}'s
     * directory and streams output to {@code listener}. Refuses to start if a previous run is still alive
     * (stop it first). All listener callbacks run on the FX thread.
     */
    public void run(Path file, List<String> argv, Listener listener) {
        if (file == null || argv == null || argv.isEmpty() || listener == null || isRunning()) {
            return;
        }
        int gen = ++generation;
        List<String> command = ProcessRunner.resolveExecutable(argv);
        ProcessBuilder pb = new ProcessBuilder(command);
        Path dir = file.toAbsolutePath().getParent();
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        ProcessRunner.applyStandardEnv(pb);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        current = process;
        listener.onStart(String.join(" ", command));
        pump(process.getInputStream(), false, gen, listener);
        pump(process.getErrorStream(), true, gen, listener);
        Thread waiter = new Thread(() -> {
            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                code = -1;
            }
            int finalCode = code;
            postIfCurrent(gen, () -> {
                current = null;
                listener.onExit(finalCode);
            });
        }, "run-wait");
        waiter.setDaemon(true);
        waiter.start();
    }

    /** Kills the running process (best effort); its waiter reports the exit. */
    public void stop() {
        Process p = current;
        if (p != null && p.isAlive()) {
            p.destroy();
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    /** Drains a stream line-by-line on a daemon thread, posting each line to the FX thread (if still current). */
    private void pump(InputStream in, boolean stderr, int gen, Listener listener) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String text = line;
                    postIfCurrent(gen, () -> listener.onOutput(text, stderr));
                }
            } catch (IOException ignored) {
                // Stream closed as the process ended — nothing to report.
            }
        }, stderr ? "run-stderr" : "run-stdout");
        t.setDaemon(true);
        t.start();
    }

    /** Runs {@code action} on the FX thread only if {@code gen} is still the active run (drops stale output). */
    private void postIfCurrent(int gen, Runnable action) {
        if (gen == generation) {
            Platform.runLater(() -> {
                if (gen == generation) {
                    action.run();
                }
            });
        }
    }
}
