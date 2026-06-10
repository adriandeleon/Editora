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
    /** Cached {@code java -version} major (0 = not probed yet, -1 = probe failed/unparseable). */
    private volatile int javaMajor;

    /** True while a launched process is still alive. */
    public boolean isRunning() {
        Process p = current;
        return p != null && p.isAlive();
    }

    /**
     * Writes one line to the running process's stdin (for programs reading the console, e.g. a compact
     * source file calling {@code IO.readln}). The write happens off the FX thread — a full pipe buffer
     * must never block the UI. No-op when nothing is running.
     */
    public void sendInput(String line) {
        Process p = current;
        if (p == null || !p.isAlive() || line == null) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                p.getOutputStream().write((line + System.lineSeparator())
                        .getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().flush();
            } catch (IOException ignored) {
                // Process exited between the check and the write — nothing to report.
            }
        }, "run-stdin");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Probes the {@code java} launcher's major version once (off-thread, cached) and delivers it on the
     * FX thread: e.g. {@code 25}, {@code 21}, {@code 8} for a legacy {@code 1.8.0}; {@code -1} when java
     * is missing or the output is unparseable. Used to preflight compact-source runs (need JDK 25+).
     */
    public void detectJavaMajor(java.util.function.IntConsumer cb) {
        int cached = javaMajor;
        if (cached != 0) {
            cb.accept(cached);
            return;
        }
        Thread t = new Thread(() -> {
            ProcessRunner.Result r = ProcessRunner.run(null, java.time.Duration.ofSeconds(10),
                    List.of("java", "-version"));
            // `java -version` prints to stderr; some distributions use stdout.
            int major = javaMajorOf(r == null ? "" : r.err() + "\n" + r.out());
            javaMajor = major;
            Platform.runLater(() -> cb.accept(major));
        }, "run-java-probe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Parses the major version out of {@code java -version} output (pure — tested): the first quoted
     * version token, e.g. {@code openjdk version "25.0.3"} → 25, {@code "21"} → 21, and the legacy
     * {@code "1.8.0_392"} → 8. Returns -1 when absent/unparseable.
     */
    static int javaMajorOf(String output) {
        if (output == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("version \"(\\d+)(?:\\.(\\d+))?[^\"]*\"").matcher(output);
        if (!m.find()) {
            return -1;
        }
        int first = Integer.parseInt(m.group(1));
        if (first == 1 && m.group(2) != null) {
            return Integer.parseInt(m.group(2)); // legacy 1.x scheme: "1.8.0_392" → 8
        }
        return first;
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
