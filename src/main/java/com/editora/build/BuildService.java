package com.editora.build;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.process.BusyExecutable;
import com.editora.process.OutputPump;
import com.editora.process.ProcessRunner;
import org.jspecify.annotations.Nullable;

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

    private volatile @Nullable Process current;

    /** Whether Stop interrupts first (Node and its package managers): see {@link #stop()}. */
    private volatile boolean interruptOnStop;

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
        return current != null || starting;
    }

    /**
     * True from {@link #run} until the build has started (or failed to) on the {@code build-start} thread
     * (PERF-01, Pauta 6.0.1). A second press meanwhile finds the service running and starts nothing.
     */
    private volatile boolean starting;

    /** Stop was pressed while the build was being started: it is ended as soon as it has started. */
    private volatile boolean stopWhileStarting;

    /** {@link #shutdown} came while a start was under way: the build is ended and nothing is reported. */
    private volatile boolean shutDown;

    /** Where a build is started: a virtual thread per start. TEST SEAM — a test may hold the starts. */
    java.util.concurrent.Executor starter = com.editora.process.BlockingTasks.named("build-start");

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
    public void run(@Nullable Path workingDir, List<String> argv, Map<String, String> environment, Listener listener) {
        if (workingDir == null || argv == null || argv.isEmpty() || listener == null || isRunning()) {
            return;
        }
        starting = true;
        stopWhileStarting = false;
        shutDown = false;
        int gen = pump.begin();
        boolean remote = !com.editora.process.ExecTargets.runsHere(workingDir);
        // Copies: the caller may reuse its lists, and the start reads them later on its own thread.
        List<String> program = List.copyOf(argv);
        Map<String, String> env = environment == null ? Map.of() : new java.util.LinkedHashMap<>(environment);
        starter.execute(() -> start(gen, workingDir, program, env, remote, listener));
    }

    /**
     * Finds, starts and tracks the build on the {@code build-start} thread, then hands it to the readers.
     * {@code onStart} is queued on the pump before the first line of output; a failed start reports
     * {@code onError} on the FX thread.
     */
    private void start(
            int gen,
            Path workingDir,
            List<String> argv,
            Map<String, String> environment,
            boolean remote,
            Listener listener) {
        List<String> command;
        Process process;
        try {
            command = remote ? argv : ProcessRunner.resolveExecutable(argv);
            // A remote build runs on its host, long-lived so Stop and a lost connection end the whole tree.
            process = remote
                    ? com.editora.run.RunService.startRemote(workingDir, command, environment)
                    : BusyExecutable.start(processBuilder(workingDir, command, environment));
            com.editora.process.ProcessRegistry.track(process); // so the shutdown hook / reaper can kill it
        } catch (IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            javafx.application.Platform.runLater(() -> {
                starting = false;
                if (!shutDown) {
                    listener.onError(message);
                }
            });
            return;
        }
        if (shutDown) {
            starting = false;
            com.editora.process.ProcessRegistry.killTree(process);
            return;
        }
        interruptOnStop = !remote && com.editora.run.NodeRunPlan.interruptsOnStop(command.get(0));
        current = process; // before starting is cleared: isRunning() never reads false in between
        starting = false;
        if (stopWhileStarting) {
            stop();
        }
        String shown = String.join(" ", command);
        pump.postStart(gen, () -> listener.onStart(shown));
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
        if (p == null && starting) {
            stopWhileStarting = true; // ended as soon as it has started
            p = current; // unless it started meanwhile, past the start thread's look at the flag
        }
        if (p != null && p.isAlive()) {
            if (interruptOnStop) {
                // npm/pnpm/yarn/bun scripts: SIGINT first, as Ctrl+C in a terminal, so a script's own
                // process.on('SIGINT') cleanup runs; whatever is left is killed after the grace period.
                com.editora.process.ProcessRegistry.interruptThenKillTree(p);
            } else {
                com.editora.process.ProcessRegistry.killTree(p);
            }
        }
    }

    /** Final owner shutdown: stop the process and discard callbacks queued for a window that is closing. */
    public void shutdown() {
        shutDown = true;
        pump.cancel();
        Process p = current;
        current = null;
        if (p != null && p.isAlive()) {
            com.editora.process.ProcessRegistry.killTree(p);
        }
    }
}
