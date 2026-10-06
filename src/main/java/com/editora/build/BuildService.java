package com.editora.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.OutputPump;
import com.editora.process.ProcessRunner;

/**
 * Runs a build-tool invocation (Maven/Gradle/npm/Cargo/Go) in a project directory and streams stdout/stderr
 * live to a {@link Listener} on the JavaFX thread. Mirrors {@code com.editora.run.RunService}'s shape and
 * shares its {@link OutputPump} (bounded queue, per-pulse batches, a line cap, readers joined before the exit
 * is reported, a generation guard against a stopped/superseded run's late output) but is keyed on
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

    /**
     * The bounded, batched stdout/stderr pump shared with {@code run.RunService}. A build's stream is
     * <em>parsed</em> — Go, Cargo and npm TAP test results come from it — so a full queue makes the reader
     * wait ({@link OutputPump.Overflow#BLOCK}) rather than drop lines, and only whole lines are delivered.
     */
    private final OutputPump pump = new OutputPump("build", OutputPump.Overflow.BLOCK, false);

    /**
     * True from a successful launch until its exit has been <b>delivered</b> to the listener — not merely
     * until the process dies. The exit is reported only after the readers and the queue have drained, and a
     * run started in that gap would {@code pump.begin()} over it: the previous run's remaining output and its
     * {@code onExit} were discarded, leaving whoever waited for that exit (a test run, a before-launch step)
     * "running" forever.
     */
    public boolean isRunning() {
        return current != null;
    }

    /** Launches {@code argv} in {@code workingDir} and streams output to {@code listener}. Refuses to start
     *  if a previous run is still alive (stop it first). All listener callbacks run on the FX thread. */
    public void run(Path workingDir, List<String> argv, Listener listener) {
        run(workingDir, argv, Map.of(), listener);
    }

    /**
     * The build process, before it is started: the <em>user's</em> environment (their locale included) plus
     * the augmented PATH, then {@code environment} on top. Not the parse-stable {@code LC_ALL=C} one — Maven,
     * Gradle and {@code javac} are JVMs, and a JVM in the C locale cannot open a source file or project
     * directory whose name is not ASCII; the output is streamed to the user, not parsed.
     */
    static ProcessBuilder processBuilder(Path workingDir, List<String> command, Map<String, String> environment) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        ProcessRunner.applyUserEnv(pb.environment(), environment);
        return pb;
    }

    /** As {@link #run(Path, List, Listener)}, with environment overrides such as Maven's selected JDK. */
    public void run(Path workingDir, List<String> argv, Map<String, String> environment, Listener listener) {
        if (workingDir == null || argv == null || argv.isEmpty() || listener == null || isRunning()) {
            return;
        }
        int gen = pump.begin();
        List<String> command = ProcessRunner.resolveExecutable(argv);
        ProcessBuilder pb = processBuilder(workingDir, command, environment);
        Process process;
        try {
            process = pb.start();
            com.editora.process.ProcessRegistry.track(process); // so the shutdown hook / reaper can kill it

        } catch (IOException e) {
            listener.onError(e.getMessage() == null ? e.toString() : e.getMessage());
            return;
        }
        current = process;
        listener.onStart(String.join(" ", command));
        OutputPump.Sink sink = listener::onOutput;
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
                    // The process is gone but its last lines can still be in the pipes: join the readers
                    // first, or a stream-parsed test run finishes before its final results arrive and
                    // Cargo's trailing `failures:` block is dropped by the already-finished run.
                    pump.finish(stdout, stderr);
                    int finalCode = code;
                    pump.post(gen, () -> {
                        if (current == process) {
                            current = null;
                        }
                        listener.onExit(finalCode);
                    });
                },
                "build-wait");
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
