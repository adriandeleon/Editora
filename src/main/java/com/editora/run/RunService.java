package com.editora.run;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Iterator;
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

    private static final int MAX_PENDING_OUTPUT_CHARS = 256 * 1024;
    private static final int MAX_PENDING_OUTPUT_EVENTS = 2_048;
    private static final int MAX_OUTPUT_LINE_CHARS = 64 * 1024;
    private static final int MAX_EVENTS_PER_PULSE = 256;
    private static final int MAX_CHARS_PER_PULSE = 64 * 1024;
    private static final String OUTPUT_DROPPED = "[output truncated while the UI was busy]";

    private record PendingFx(int generation, int chars, boolean output, Runnable action) {}

    private record Pump(Thread thread, InputStream stream) {}

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
    private final Object outputLock = new Object();
    private final ArrayDeque<PendingFx> pendingFx = new ArrayDeque<>();
    private int pendingOutputChars;
    private int pendingOutputEvents;
    private boolean outputDrainScheduled;
    private Runnable droppedOutputNotice;
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
        Thread t = new Thread(
                () -> {
                    ProcessRunner.Result r =
                            ProcessRunner.run(null, java.time.Duration.ofSeconds(10), List.of("java", "-version"));
                    // `java -version` prints to stderr; some distributions use stdout.
                    int major = javaMajorOf(r == null ? "" : r.err() + "\n" + r.out());
                    javaMajor = major;
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
     * As {@link #runInDir(Path, List, Listener)}, plus {@code env} — extra environment variables for the
     * child (a saved run configuration's {@code KEY=VALUE} pairs), applied over the inherited environment.
     */
    public void runInDir(Path workingDir, List<String> argv, java.util.Map<String, String> env, Listener listener) {
        if (argv == null || argv.isEmpty() || listener == null || isRunning()) {
            return;
        }
        int gen = ++generation;
        synchronized (outputLock) {
            pendingFx.clear();
            pendingOutputChars = 0;
            pendingOutputEvents = 0;
            droppedOutputNotice = null;
        }
        List<String> command = ProcessRunner.resolveExecutable(argv);
        ProcessBuilder pb = new ProcessBuilder(command);
        Path dir = workingDir == null ? null : workingDir.toAbsolutePath();
        if (dir != null) {
            pb.directory(dir.toFile());
        }
        ProcessRunner.applyStandardEnv(pb);
        if (env != null && !env.isEmpty()) {
            pb.environment().putAll(env); // after applyStandardEnv so a config can override PATH etc.
        }
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
        Pump stdout = pump(process.getInputStream(), false, gen, listener);
        Pump stderr = pump(process.getErrorStream(), true, gen, listener);
        Thread waiter = new Thread(
                () -> {
                    int code;
                    try {
                        code = process.waitFor();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        code = -1;
                    }
                    finishPump(stdout);
                    finishPump(stderr);
                    int finalCode = code;
                    enqueueFx(
                            gen,
                            0,
                            false,
                            () -> {
                                current = null;
                                listener.onExit(finalCode);
                            },
                            null);
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
        generation++;
        synchronized (outputLock) {
            pendingFx.clear();
            pendingOutputChars = 0;
            pendingOutputEvents = 0;
            droppedOutputNotice = null;
        }
        Process p = current;
        current = null;
        if (p != null && p.isAlive()) {
            com.editora.process.ProcessRegistry.killTree(p);
        }
    }

    /** Drains a stream on a daemon thread without ever materializing more than one bounded line. */
    private Pump pump(InputStream in, boolean stderr, int gen, Listener listener) {
        Thread t = new Thread(
                () -> {
                    try (BufferedReader reader =
                            new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        char[] chars = new char[8_192];
                        StringBuilder line = new StringBuilder();
                        boolean truncated = false;
                        int read;
                        while ((read = reader.read(chars)) != -1) {
                            for (int i = 0; i < read; i++) {
                                char ch = chars[i];
                                if (ch == '\n') {
                                    emitLine(line, truncated, stderr, gen, listener);
                                    line.setLength(0);
                                    truncated = false;
                                } else if (line.length() < MAX_OUTPUT_LINE_CHARS) {
                                    line.append(ch);
                                } else {
                                    truncated = true;
                                }
                            }
                        }
                        if (!line.isEmpty() || truncated) {
                            emitLine(line, truncated, stderr, gen, listener);
                        }
                    } catch (IOException ignored) {
                        // Stream closed as the process ended — nothing to report.
                    }
                },
                stderr ? "run-stderr" : "run-stdout");
        t.setDaemon(true);
        t.start();
        return new Pump(t, in);
    }

    private void emitLine(StringBuilder line, boolean truncated, boolean stderr, int gen, Listener listener) {
        int length = line.length();
        if (length > 0 && line.charAt(length - 1) == '\r') {
            line.setLength(length - 1); // match BufferedReader.readLine() for CRLF
        }
        String text = line + (truncated ? " … [line truncated]" : "");
        enqueueFx(
                gen,
                text.length(),
                true,
                () -> listener.onOutput(text, stderr),
                () -> listener.onOutput(OUTPUT_DROPPED, stderr));
    }

    private static void finishPump(Pump pump) {
        if (join(pump.thread())) {
            return;
        }
        try {
            pump.stream().close(); // a descendant may still hold the pipe open after the root exits
        } catch (IOException ignored) {
            // best effort
        }
        join(pump.thread()); // bounded again; no output is intentionally accepted after the exit event
    }

    private static boolean join(Thread thread) {
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    private void enqueueFx(int gen, int chars, boolean output, Runnable action, Runnable onDrop) {
        boolean schedule = false;
        synchronized (outputLock) {
            if (gen != generation) {
                return;
            }
            pendingFx.addLast(new PendingFx(gen, chars, output, action));
            pendingOutputChars += chars;
            if (output) {
                pendingOutputEvents++;
            }
            while (pendingOutputChars > MAX_PENDING_OUTPUT_CHARS || pendingOutputEvents > MAX_PENDING_OUTPUT_EVENTS) {
                PendingFx removed = removeOldestOutput();
                if (removed == null) {
                    break;
                }
                pendingOutputChars -= removed.chars();
                pendingOutputEvents--;
                droppedOutputNotice = onDrop;
            }
            if (!outputDrainScheduled) {
                outputDrainScheduled = true;
                schedule = true;
            }
        }
        if (schedule) {
            Platform.runLater(this::drainFx);
        }
    }

    private PendingFx removeOldestOutput() {
        Iterator<PendingFx> it = pendingFx.iterator();
        while (it.hasNext()) {
            PendingFx event = it.next();
            if (event.output()) {
                it.remove();
                return event;
            }
        }
        return null;
    }

    private void drainFx() {
        java.util.ArrayList<PendingFx> batch = new java.util.ArrayList<>();
        Runnable notice;
        boolean more;
        synchronized (outputLock) {
            notice = droppedOutputNotice;
            droppedOutputNotice = null;
            int chars = 0;
            while (!pendingFx.isEmpty() && batch.size() < MAX_EVENTS_PER_PULSE) {
                PendingFx next = pendingFx.peekFirst();
                if (!batch.isEmpty() && chars + next.chars() > MAX_CHARS_PER_PULSE) {
                    break;
                }
                pendingFx.removeFirst();
                pendingOutputChars -= next.chars();
                if (next.output()) {
                    pendingOutputEvents--;
                }
                chars += next.chars();
                batch.add(next);
            }
            more = !pendingFx.isEmpty();
            outputDrainScheduled = more;
        }
        if (notice != null) {
            notice.run();
        }
        for (PendingFx event : batch) {
            if (event.generation() == generation) {
                event.action().run();
            }
        }
        if (more) {
            Platform.runLater(this::drainFx);
        }
    }
}
