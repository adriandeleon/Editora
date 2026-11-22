package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.run.RunService;
import org.jspecify.annotations.Nullable;

/**
 * The debugged program of one session, when Editora starts it itself (a launch with
 * {@link LaunchConfig#CLIENT_CONSOLE}, answered by the adapter's {@code runInTerminal} request). It runs
 * through the same {@link RunService} as the Run console, so it gets the user's environment with the
 * augmented PATH, is tracked by the process registry (never outliving the app), has its stdout/stderr
 * pumped and bounded the same way — and has a standard input, which a debuggee started by the adapter has
 * not.
 *
 * <p>One instance per session: a new launch never waits for the previous program to finish dying. All
 * methods and callbacks are on the FX thread.
 */
final class Debuggee {

    /** What the program does; every call is on the FX thread. */
    interface Sink {
        /** Program output, as console text: a whole line ends in a newline, a flushed prompt does not. */
        void output(String text, boolean stderr);

        /** The program ended with {@code code} ({@code -1} when killed), after its last output. */
        void exited(int code);
    }

    private final RunService service = new RunService();
    private boolean inputClosed;

    /** Where the program runs instead of {@link #service}: a Terminal tab; null for the Debug console. */
    private final @Nullable ProgramTerminal terminal;

    private final String title;
    private com.editora.terminal.@Nullable ProgramRun run;

    Debuggee() {
        this(null, "");
    }

    /** A program that runs in a Terminal tab titled {@code title} when {@code terminal} is given. */
    Debuggee(@Nullable ProgramTerminal terminal, String title) {
        this(terminal, title, false);
    }

    /**
     * As above; {@code ordered}: its stderr shares stdout's pipe, so the Debug console shows its lines in the order
     * they were written (review D102), all as standard output.
     */
    Debuggee(@Nullable ProgramTerminal terminal, String title, boolean ordered) {
        this.terminal = terminal;
        this.title = title == null ? "" : title;
        service.setMergeErrorStream(ordered);
    }

    /** Whether the program runs in a Terminal tab, which then owns its input and output. */
    boolean inTerminal() {
        return terminal != null;
    }

    /**
     * Starts {@code argv} in {@code dir} with {@code env} over the user's environment (a null value unsets that
     * variable); a folder on a remote host's connection starts the program on that host, as Run does. The start
     * itself runs off the FX thread (PERF-01, Pauta 6.2.0): the answer completes on the FX thread with the process
     * id (-1 when it is not known yet, a program on another machine), or fails with why the program could not be
     * started. Its output only comes after that.
     */
    java.util.concurrent.CompletableFuture<Long> start(
            @Nullable Path dir, List<String> argv, Map<String, String> env, Sink sink) {
        java.util.concurrent.CompletableFuture<Long> started = new java.util.concurrent.CompletableFuture<>();
        if (terminal != null) {
            try {
                run = terminal.start(title, dir, argv, env == null ? Map.of() : env, sink::exited);
                started.complete(run.pid()); // -1 until the program has started: the reply then names none
            } catch (java.io.IOException | RuntimeException e) {
                started.completeExceptionally(e);
            }
            return started;
        }
        service.runInDir(dir, argv, env, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                started.complete(service.pid());
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                sink.output(line + "\n", stderr);
            }

            @Override
            public void onPartialOutput(String text, boolean stderr) {
                sink.output(text, stderr);
            }

            @Override
            public void onExit(int code) {
                sink.exited(code);
            }

            @Override
            public void onError(String message) {
                started.completeExceptionally(new java.io.IOException(
                        message == null || message.isBlank()
                                ? com.editora.i18n.Messages.tr("debug.error.programNotStarted")
                                : message));
            }
        });
        if (!service.isRunning() && !started.isDone()) {
            started.completeExceptionally(
                    new java.io.IOException(com.editora.i18n.Messages.tr("debug.error.programNotStarted")));
        }
        return started;
    }

    /** True until the program's exit has been delivered to the sink (its last output included). */
    boolean isRunning() {
        return run != null ? run.isRunning() : service.isRunning();
    }

    /** Whether a typed line can still reach the program: it is alive and its input has not been ended. */
    boolean acceptsInput() {
        return run == null && service.isRunning() && !inputClosed;
    }

    /** False once {@link #closeInput} has ended the program's input. */
    boolean inputOpen() {
        return run == null && !inputClosed;
    }

    void sendInput(String line) {
        if (acceptsInput()) {
            service.sendInput(line);
        }
    }

    /** Ends the program's standard input: whatever reads it sees end of file. */
    void closeInput() {
        inputClosed = true;
        if (run == null) {
            service.closeInput();
        }
    }

    /** Ends the program but keeps listening: its remaining output and its exit are still delivered. */
    void terminate() {
        if (run != null) {
            run.stop();
        } else {
            service.stop();
        }
    }

    /** Ends the program and forgets it: nothing more is delivered (Stop, Restart, the window closing). */
    void kill() {
        if (run != null) {
            run.stop(); // its tab stays, with what it printed
        } else {
            service.shutdown();
        }
    }
}
