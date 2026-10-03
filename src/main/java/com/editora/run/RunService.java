package com.editora.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.process.OutputPump;
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

        /** Output flushed before a newline, such as a prompt waiting for console input. */
        default void onPartialOutput(String text, boolean stderr) {
            onOutput(text, stderr);
        }

        /** The process exited with {@code code} (or {@code -1} if killed). */
        void onExit(int code);

        /** The process could not be launched (e.g. {@code java} not found). */
        void onError(String message);
    }

    private volatile Process current;

    /**
     * The bounded, batched stdout/stderr pump shared with {@code build.BuildService}. A program's console
     * drops its oldest queued lines when the UI cannot keep up (the newest output is what matters, and the
     * program is never slowed down), and flushes a line that stops short of a newline so a prompt shows.
     */
    private final OutputPump pump = new OutputPump("run", OutputPump.Overflow.DROP_OLDEST, true);
    /** Cache by executable: changing the selected JDK must never reuse the old PATH probe. */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> javaMajors =
            new java.util.concurrent.ConcurrentHashMap<>();

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
        Thread t = new Thread(
                () -> {
                    try {
                        p.getOutputStream().write((line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
                        p.getOutputStream().flush();
                    } catch (IOException ignored) {
                        // Process exited between the check and the write — nothing to report.
                    }
                },
                "run-stdin");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Probes the selected {@code java} launcher's major version once (off-thread, cached) and delivers it on the
     * FX thread: e.g. {@code 25}, {@code 21}, {@code 8} for a legacy {@code 1.8.0}; {@code -1} when java
     * is missing or the output is unparseable. Used to preflight compact-source runs (need JDK 25+).
     */
    public void detectJavaMajor(String javaExecutable, java.util.function.IntConsumer cb) {
        String executable = javaExecutable == null || javaExecutable.isBlank() ? "java" : javaExecutable;
        Integer cached = javaMajors.get(executable);
        if (cached != null) {
            cb.accept(cached);
            return;
        }
        Thread t = new Thread(
                () -> {
                    ProcessRunner.Result r =
                            ProcessRunner.run(null, java.time.Duration.ofSeconds(10), List.of(executable, "-version"));
                    // `java -version` prints to stderr; some distributions use stdout.
                    int major = javaMajorOf(r == null ? "" : r.err() + "\n" + r.out());
                    javaMajors.put(executable, major);
                    Platform.runLater(() -> cb.accept(major));
                },
                "run-java-probe");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Parses the major version out of {@code java -version} output (pure — tested): the first quoted
     * version token, e.g. {@code openjdk version "25.0.3"} → 25, {@code "21"} → 21, and the legacy
     * {@code "1.8.0_392"} → 8. Returns -1 when absent/unparseable. Public: the Doctor screen reuses it.
     */
    public static int javaMajorOf(String output) {
        if (output == null) {
            return -1;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("version \"(\\d+)(?:\\.(\\d+))?[^\"]*\"")
                .matcher(output);
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
        if (file == null) {
            return;
        }
        runInDir(file.toAbsolutePath().getParent(), argv, listener);
    }

    /**
     * Launches {@code argv} in {@code workingDir} (the project root for a project main class, else a file's
     * folder) and streams output to {@code listener}. Refuses to start if a previous run is still alive.
     * All listener callbacks run on the FX thread.
     */
    public void runInDir(Path workingDir, List<String> argv, Listener listener) {
        runInDir(workingDir, argv, java.util.Map.of(), listener);
    }

    /**
     * The process a run starts, before it is started: {@code command} in {@code workingDir} with the
     * <em>user's</em> environment — their locale included — plus the augmented PATH, then the run
     * configuration's own variables on top (so a config can still override PATH or a locale variable).
     *
     * <p>Deliberately not the parse-stable environment: forcing {@code LC_ALL=C} on the user's program made a
     * JVM child decode file names as ASCII, so a project under {@code año/} could not be run at all and
     * anything it printed outside ASCII came out as {@code ?}.
     */
    static ProcessBuilder processBuilder(Path workingDir, List<String> command, java.util.Map<String, String> env) {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workingDir != null) {
            pb.directory(workingDir.toAbsolutePath().toFile());
        }
        ProcessRunner.applyUserEnv(pb.environment(), env);
        return pb;
    }

    /**
     * As {@link #runInDir(Path, List, Listener)}, plus {@code env} — extra environment variables for the
     * child (a saved run configuration's {@code KEY=VALUE} pairs), applied over the inherited environment.
     */
    public void runInDir(Path workingDir, List<String> argv, java.util.Map<String, String> env, Listener listener) {
        if (argv == null || argv.isEmpty() || listener == null || isRunning()) {
            return;
        }
        int gen = pump.begin();
        List<String> command = ProcessRunner.resolveExecutable(argv);
        ProcessBuilder pb = processBuilder(workingDir, command, env);
        Process process;
        try {
            process = pb.start();
            // Track it: the JVM shutdown hook + the orphan reaper only know about tracked processes, so an
            // untracked long-lived program (a dev server) outlived the app and wasn't reaped on the next launch.
            com.editora.process.ProcessRegistry.track(process);
        } catch (IOException e) {
            listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        current = process;
        listener.onStart(String.join(" ", command));
        OutputPump.Sink sink = new OutputPump.Sink() {
            @Override
            public void line(String text, boolean stderr) {
                listener.onOutput(text, stderr);
            }

            @Override
            public void partial(String text, boolean stderr) {
                listener.onPartialOutput(text, stderr);
            }
        };
        OutputPump.Feed stdout = pump.start(process.getInputStream(), false, gen, sink);
        OutputPump.Feed stderr = pump.start(process.getErrorStream(), true, gen, sink);
        Thread waiter = new Thread(
                () -> {
                    int code;
                    try {
                        code = process.waitFor();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        code = -1;
                    }
                    pump.finish(stdout, stderr);
                    int finalCode = code;
                    pump.post(gen, () -> {
                        current = null;
                        listener.onExit(finalCode);
                    });
                },
                "run-wait");
        waiter.setDaemon(true);
        waiter.start();
    }

    /**
     * Kills the running process <b>and its descendants</b> (best effort); its waiter reports the exit.
     *
     * <p>{@code destroy()} on the root alone orphans the real work: {@code npm run dev}, {@code mvn}, and
     * {@code ./gradlew} all fork a child, so SIGTERM-ing the wrapper leaves a dev server holding its port or a
     * build JVM still running. {@code ProcessRegistry.killTree} kills children first (so a wrapper can't
     * reparent-orphan its child) and escalates to a force-kill after a grace period, instead of the old
     * {@code destroy(); if (isAlive()) destroyForcibly();} — where the {@code isAlive()} check right after an
     * asynchronous SIGTERM is essentially always true, so the child was SIGKILLed with no grace period at all.
     */
    public void stop() {
        Process p = current;
        if (p != null && p.isAlive()) {
            com.editora.process.ProcessRegistry.killTree(p);
        }
    }

    /** Final owner shutdown: stop the process and discard callbacks queued for a window that is closing. */
    public void shutdown() {
        pump.cancel();
        Process p = current;
        current = null;
        if (p != null && p.isAlive()) {
            com.editora.process.ProcessRegistry.killTree(p);
        }
    }
}
