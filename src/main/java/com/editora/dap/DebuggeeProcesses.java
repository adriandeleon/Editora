package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * The programs Editora starts itself for a session (an adapter's {@code runInTerminal}), how they end, and the
 * exit code of a Java program, which java-debug does not report (the {@link JavaExitCodeWrapper}).
 *
 * <p>Moved out of {@link DapManager} for size: the code is the manager's, reaching its session state through
 * {@link #dap}, and runs on the same threads with the same session checks as it did there.
 */
final class DebuggeeProcesses {
    private final DapManager dap;

    DebuggeeProcesses(DapManager dap) {
        this.dap = dap;
    }

    /**
     * The adapter's {@code runInTerminal}: starts the program of session {@code epoch} as Editora's child and
     * completes {@code started} with its process id. The command, working directory and environment are the
     * adapter's, untouched; the environment goes over the user's own with the augmented PATH, as for Run.
     */
    void startDebuggee(
            long epoch, String cwd, List<String> argv, Map<String, String> env, CompletableFuture<Long> started) {
        if (!dap.isCurrent(epoch)) {
            started.completeExceptionally(new java.util.concurrent.CancellationException("the session has ended"));
            return;
        }
        killDebuggee(); // a session has one program
        boolean launcher = isAdapterLauncher(argv);
        if (!launcher) {
            dap.programStartedEpoch = epoch; // its exit is the program's: the adapter's exited event is not reported
        }
        // A Node.js program's stdout and stderr share one pipe: two pipes read by two threads reordered its lines
        // (review D102). Java and Python keep their red stderr.
        Debuggee program = new Debuggee(dap.shared.programTerminal, programTitle(), dap.jsSession);
        try {
            // The adapter names the folder as its machine does: on a remote file's host, a path there.
            Path file = dap.debugFile;
            DapPaths paths = DapManager.pathsFor(file);
            Path dir = cwd == null || cwd.isBlank() ? null : paths.fromAdapter(cwd);
            if (dir == null && file != null && paths != DapPaths.LOCAL) {
                dir = file.getParent(); // never this machine's folder of the same name
            }
            if (dap.jsSession && !program.inTerminal()) {
                // What runs, first in the console (muted), as for Python: exactly what js-debug asked for, which
                // for a compiled project's .ts source is its built .js (review D106).
                dap.sessionEvents.onOutput(
                        epoch, com.editora.run.RunService.commandLine(argv) + "\n", DapManager.COMMAND_CATEGORY);
                Path source = dap.debugFile;
                Path built = JsLaunch.compiledInstead(source, dir, argv);
                if (built != null && source != null) {
                    // The two files' times are read off the FX thread (PERF-01), and the program starts after the
                    // notice, which comes first in the console as before.
                    Path from = dir;
                    Thread.ofVirtual().name("dap-js-stale-check").start(() -> {
                        boolean stale = JsLaunch.builtIsStale(source, built, dap.fileTimes);
                        dap.fx(() -> {
                            if (!dap.isCurrent(epoch)) {
                                // Stop or Restart while the times were read: no program for a session that is gone.
                                program.kill();
                                started.completeExceptionally(
                                        new java.util.concurrent.CancellationException("the session has ended"));
                                return;
                            }
                            if (stale) {
                                // A notice, not stderr: js-debug's copies of the program's streams are dropped.
                                dap.sessionEvents.onNotice(
                                        epoch,
                                        tr(
                                                "debug.js.builtStale",
                                                String.valueOf(built.getFileName()),
                                                source.getFileName()),
                                        true);
                            }
                            startProgram(epoch, program, from, argv, env, started, launcher);
                        });
                    });
                    return;
                }
            }
            startProgram(epoch, program, dir, argv, env, started, launcher);
        } catch (RuntimeException e) {
            program.kill();
            started.completeExceptionally(e); // the adapter fails the launch with this, which reports it
        }
    }

    /**
     * Ends session {@code epoch}, whose adapter's launcher ended at {@code since} ({@link System#nanoTime}), once the
     * adapter has reported the program's exit; after {@link DapManager#LAUNCHER_EXIT_GRACE_MILLIS} without it, says only that
     * the program ended (FX thread).
     */
    private void endAfterAdapterExit(long epoch, long since) {
        if (!dap.isCurrent(epoch)) {
            return; // the adapter ended it meanwhile
        }
        if (exitReportedEpoch != epoch) {
            if (System.nanoTime() - since < TimeUnit.MILLISECONDS.toNanos(DapManager.LAUNCHER_EXIT_GRACE_MILLIS)) {
                CompletableFuture.delayedExecutor(50, TimeUnit.MILLISECONDS)
                        .execute(() -> dap.fx(() -> endAfterAdapterExit(epoch, since)));
                return;
            }
            dap.outputPump.flush();
            dap.listener.onOutput(com.editora.i18n.Messages.tr("debug.console.programEnded") + "\n", "console");
        }
        dap.sessionEvents.endSession(epoch);
    }

    /**
     * Whether {@code argv}, asked for by an adapter's {@code runInTerminal}, starts the adapter's own launcher rather
     * than the program: recent CodeLLDB (1.12) has the client start {@code codelldb-launch --connect=…}, which connects
     * back, has the adapter start the program with its standard streams, and is ended by the adapter with SIGTERM
     * once the program has exited. Its exit code (143 on Linux) is not the program's; the adapter's {@code exited}
     * event has that.
     */
    static boolean isAdapterLauncher(List<String> argv) {
        if (argv == null || argv.isEmpty() || argv.getFirst() == null) {
            return false;
        }
        String first = argv.getFirst().replace('\\', '/');
        String name = first.substring(first.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - ".exe".length());
        }
        return name.equals("codelldb-launch");
    }

    /**
     * Starts {@code program} for the session of {@code epoch} and answers the adapter's request when it has. When an
     * adapter's {@code launcher} ({@link #isAdapterLauncher}) ends, its own code is not reported: the session ends
     * after the adapter's exited event ({@link #endAfterAdapterExit}).
     */
    private void startProgram(
            long epoch,
            Debuggee program,
            @Nullable Path dir,
            List<String> argv,
            Map<String, String> env,
            CompletableFuture<Long> started,
            boolean launcher) {
        try {
            dap.debuggee = program; // a Stop while it starts ends it as soon as it has started
            program.start(dir, argv, env, new Debuggee.Sink() {
                        @Override
                        public void output(String text, boolean stderr) {
                            String shown = dap.jsSession ? dap.jsNoise.filter(text) : text;
                            if (dap.debuggee == program && dap.isCurrent(epoch) && !shown.isEmpty()) {
                                dap.listener.onOutput(shown, stderr ? "stderr" : "stdout");
                            }
                        }

                        @Override
                        public void exited(int code) {
                            if (dap.debuggee != program) {
                                return; // stopped: whoever stopped it has ended, or is ending, the session
                            }
                            boolean inputWasOpen = program.inputOpen();
                            dap.debuggee = null;
                            if (dap.isCurrent(epoch)) {
                                if (inputWasOpen) {
                                    dap.listener.onProgramInput(false);
                                }
                                if (launcher) {
                                    // The adapter's exited event says how the program ended (its code, not this
                                    // launcher's SIGTERM): the session ends once it has come, or after a grace.
                                    endAfterAdapterExit(epoch, System.nanoTime());
                                    return;
                                }
                                dap.listener.onProgramExit(code);
                                dap.sessionEvents.endSession(epoch);
                            }
                        }
                    })
                    .whenComplete((pid, failure) -> {
                        if (failure != null) {
                            if (dap.debuggee == program) {
                                dap.debuggee = null;
                            }
                            program.kill();
                            started.completeExceptionally(
                                    failure); // the adapter fails the launch with this, which reports it
                            return;
                        }
                        started.complete(pid);
                        if (dap.debuggee != program || !dap.isCurrent(epoch)) {
                            return; // stopped while it started
                        }
                        if (!program.inTerminal()) {
                            dap.listener.onProgramInput(true); // in a terminal, the terminal takes the input
                        } else {
                            // The Debug console stays empty: say where the program's output and input are (review
                            // E106).
                            dap.sessionEvents.onOutput(
                                    epoch,
                                    tr("debug.console.inTerminal", programTitle()) + "\n",
                                    DapManager.COMMAND_CATEGORY);
                        }
                    });
        } catch (RuntimeException e) {
            if (dap.debuggee == program) {
                dap.debuggee = null;
            }
            program.kill();
            started.completeExceptionally(e); // the adapter fails the launch with this, which reports it
        }
    }

    static Optional<java.time.Instant> modifiedTime(Path file) {
        try {
            return Optional.of(java.nio.file.Files.getLastModifiedTime(file).toInstant());
        } catch (java.io.IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A Terminal tab's title for the debugged program: the file being debugged. */
    private String programTitle() {
        Path file = dap.debugFile;
        String name = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString();
        return com.editora.i18n.Messages.tr("terminal.debugTitle", name);
    }

    /**
     * The adapter reports the program exited: the console says with which code, after the program's last
     * output. A program Editora started itself reports its own exit instead (see {@link #startDebuggee}); an
     * adapter's own launcher it started does not ({@link #isAdapterLauncher}).
     */
    void onExited(long epoch, int exitCode) {
        dap.fx(() -> {
            if (!dap.isCurrent(epoch) || dap.programStartedEpoch == epoch || exitReportedEpoch == epoch) {
                return;
            }
            exitReportedEpoch = epoch;
            dap.outputPump.flush();
            if (dap.javaAdapterEpoch == epoch) {
                // java-debug reports 0 whatever the program exited with (System.exit(3) included, seen live).
                // The wrapper the program was started through recorded its real code; without one the console
                // says only that the program ended, not a code that is not its own (review A10). It waits for
                // the output still on its way, which would otherwise come after it (E104).
                dap.exitAwaitingOutput = epoch;
                dap.sessionEvents.outputTrailing(epoch);
                dap.sessionEvents.whenOutputQuiet(
                        epoch, System.nanoTime(), () -> dap.sessionEvents.reportAwaitedExit(epoch));
                return;
            }
            dap.listener.onProgramExit(exitCode);
        });
    }

    /** A session's exit-code wrapper ({@link JavaExitCodeWrapper}) and the session it belongs to. */
    private record ExitWrapper(long epoch, JavaExitCodeWrapper wrapper, String realJava) {}

    /**
     * The {@code java} a launch of {@code epoch} really runs: its {@code javaExec}, or the one behind the
     * exit-code wrapper. Anything that runs it itself (the runtime chip's {@code -version}) must run this one: a
     * run of the wrapper would record that run's exit code as the program's.
     */
    String realJavaExec(long epoch, String javaExec) {
        ExitWrapper own = exitWrapper;
        return own != null && own.epoch() == epoch ? own.realJava() : javaExec;
    }

    /** The current session's exit-code wrapper, or null (the program console is on, a remote file, Windows). */
    private volatile @Nullable ExitWrapper exitWrapper;

    /** An exit-code wrapper made ahead of time for each {@code java}, off the FX thread (PERF-01). */
    private static final Map<String, JavaExitCodeWrapper> SPARE_EXIT_WRAPPERS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Makes a spare wrapper for {@code java} on a virtual thread, unless one is ready (not on Windows). */
    static void prepareExitWrapper(@Nullable String java) {
        if (java == null
                || java.isBlank()
                || LaunchConfig.Target.LOCAL.windows()
                || SPARE_EXIT_WRAPPERS.containsKey(java)) {
            return;
        }
        Thread.ofVirtual().name("dap-java-exit-wrapper").start(() -> {
            com.editora.perf.BlockingSites.enter("JavaExitCodeWrapper.create");
            try {
                JavaExitCodeWrapper made = JavaExitCodeWrapper.create(java);
                if (SPARE_EXIT_WRAPPERS.putIfAbsent(java, made) != null) {
                    made.delete(); // another was made meanwhile
                }
            } catch (java.io.IOException | RuntimeException e) {
                DapManager.LOG.log(Level.FINE, "No exit-code wrapper made ahead", e);
            }
        });
    }

    /**
     * Starts the program of a launch java-debug runs itself (the program console off) through a wrapper that
     * records its exit code: {@code args}' {@code javaExec} becomes the wrapper, which runs the real one. Only for
     * a program on this machine and not on Windows (see {@link JavaExitCodeWrapper}); and only when the launch
     * names its {@code java} — without one java-debug picks its own, which the wrapper cannot know. Off the FX
     * thread: the connect thread, before the launch request.
     */
    void wrapForExitCode(@Nullable Path file, Map<String, Object> args, long epoch) {
        Object java = args.get("javaExec");
        if (!(java instanceof String real)
                || real.isBlank()
                || !com.editora.process.ExecTargets.runsHere(file)
                || LaunchConfig.Target.LOCAL.windows()) {
            return;
        }
        try {
            // Called on the connect thread, never the FX thread (PERF-01): the one made ahead for this java (a
            // launch's plan makes it) is taken, else one is made here — a launch without its wrapper never
            // reports its exit code (the regression of #1664 for a loose file). The next one is made ahead.
            JavaExitCodeWrapper spare = SPARE_EXIT_WRAPPERS.remove(real);
            JavaExitCodeWrapper wrapper = spare != null ? spare : JavaExitCodeWrapper.create(real);
            prepareExitWrapper(real);
            if (!dap.isCurrent(epoch)) {
                wrapper.delete(); // stopped meanwhile: no program runs through it
                return;
            }
            releaseExitWrapper();
            exitWrapper = new ExitWrapper(epoch, wrapper, real);
            args.put("javaExec", wrapper.script().toString());
        } catch (java.io.IOException | RuntimeException e) {
            DapManager.LOG.log(Level.FINE, "No exit-code wrapper; the program runs as before", e);
        }
    }

    /**
     * Says how the program of {@code epoch} ended: its real exit code when its wrapper recorded one — written a
     * moment after java-debug reports the VM's death, so it is waited for off the FX thread — else that it
     * ended. Nothing when another session has started meanwhile (its console is not this program's).
     */
    void reportJavaExit(long epoch) {
        ExitWrapper own = exitWrapper;
        if (own == null || own.epoch() != epoch) {
            dap.listener.onOutput(com.editora.i18n.Messages.tr("debug.console.programEnded") + "\n", "console");
            return;
        }
        exitWrapper = null;
        // On a thread of its own, not the common pool: the wait polls a file for up to three seconds. Not the
        // connect thread either, which the next session's start-up would then queue behind.
        CompletableFuture.supplyAsync(
                        () -> own.wrapper().awaitExitCode(java.time.Duration.ofSeconds(3)),
                        com.editora.process.BlockingTasks.named("dap-java-exit-code"))
                .whenComplete((code, e) -> dap.fx(() -> {
                    // Its folder is removed off the FX thread (PERF-01, Pauta 6.2.0).
                    Thread.ofVirtual()
                            .name("dap-java-exit-wrapper")
                            .start(() -> own.wrapper().delete());
                    if (dap.managerClosed || dap.sessionEpoch - epoch > 1) {
                        return; // a new session (or a Stop after the end) owns the console now
                    }
                    dap.outputPump.flush();
                    if (code != null) {
                        dap.listener.onProgramExit(code);
                    } else {
                        dap.listener.onOutput(
                                com.editora.i18n.Messages.tr("debug.console.programEnded") + "\n", "console");
                    }
                }));
    }

    /** The session is over: Delve's program it ran is no longer in use, and goes ({@link GoLaunch#releaseDebugBinary}). */
    void releaseGoDebugBinary() {
        Object output = dap.goDebugBinary;
        dap.goDebugBinary = null;
        if (output != null) {
            GoLaunch.releaseDebugBinary(output, com.editora.process.BlockingTasks.named("dap-remove-go-binary"));
        }
    }

    /** Removes the exit-code wrapper of a session that will not report its exit (stopped, replaced). */
    void releaseExitWrapper() {
        ExitWrapper old = exitWrapper;
        exitWrapper = null;
        if (old != null) {
            // After the stop: java-debug may still be ending the program the wrapper runs.
            CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(old.wrapper()::delete);
        }
    }

    /** The session whose exit code was already reported (a child session may report it again). */
    private long exitReportedEpoch = -1;

    /** Ends the session's own program, if it has one, and stops listening to it (FX thread). */
    void killDebuggee() {
        Debuggee program = dap.debuggee;
        dap.debuggee = null;
        if (program != null) {
            program.kill();
        }
    }
}
