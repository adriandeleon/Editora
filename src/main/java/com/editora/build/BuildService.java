package com.editora.build;

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
 * Runs a build-tool invocation (Maven/Gradle/npm/Cargo/Go) in a project directory and streams stdout/stderr
 * live to a {@link Listener} on the JavaFX thread. Mirrors {@code com.editora.run.RunService}'s shape (daemon
 * pump threads, a monotonic generation guard against a stopped/superseded run's late output) but is keyed on
 * an explicit working directory rather than a file's parent — a build always runs at the project root. No
 * stdin support: a build isn't interactive. One instance per tool, so a polyglot project can run e.g. npm and
 * go at once (each instance still refuses a second concurrent run of its own tool).
 */
public final class BuildService {

    /** Receives lifecycle + streamed output, always on the FX thread. */
    public interface Listener {
        /** The process started; {@code commandLine} is the resolved command for display. */
        void onStart(String commandLine);

        /** One line of output ({@code stderr} true for the error stream). */
        void onOutput(String line, boolean stderr);

        /** The process exited with {@code code} (or {@code -1} if killed). */
        void onExit(int code);

        /** The process could not be launched (e.g. neither the wrapper nor the command was found). */
        void onError(String message);
    }

    private volatile Process current;
    private volatile int generation;

    /** True while a launched process is still alive. */
    public boolean isRunning() {
        Process p = current;
        return p != null && p.isAlive();
    }

    /** Launches {@code argv} in {@code workingDir} and streams output to {@code listener}. Refuses to start
     *  if a previous run is still alive (stop it first). All listener callbacks run on the FX thread. */
    public void run(Path workingDir, List<String> argv, Listener listener) {
        if (workingDir == null || argv == null || argv.isEmpty() || listener == null || isRunning()) {
            return;
        }
        int gen = ++generation;
        List<String> command = ProcessRunner.resolveExecutable(argv);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
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
        Thread waiter = new Thread(
                () -> {
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
                },
                "build-wait");
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

    private void pump(InputStream in, boolean stderr, int gen, Listener listener) {
        Thread t = new Thread(
                () -> {
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
                },
                stderr ? "build-stderr" : "build-stdout");
        t.setDaemon(true);
        t.start();
    }

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
