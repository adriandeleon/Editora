package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.run.RunService;

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

    /**
     * Starts {@code argv} in {@code cwd} with {@code env} over the user's environment (a null value unsets
     * that variable). Returns the process id, or throws with the reason the program could not be started.
     */
    long start(String cwd, List<String> argv, Map<String, String> env, Sink sink) throws java.io.IOException {
        String[] failure = {null};
        Path dir = cwd == null || cwd.isBlank() ? null : Path.of(cwd);
        service.runInDir(dir, argv, env, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {}

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
                failure[0] = message == null ? "" : message;
            }
        });
        if (failure[0] != null || !service.isRunning()) {
            throw new java.io.IOException(failure[0] == null ? "the program could not be started" : failure[0]);
        }
        return service.pid();
    }

    /** True until the program's exit has been delivered to the sink (its last output included). */
    boolean isRunning() {
        return service.isRunning();
    }

    /** Whether a typed line can still reach the program: it is alive and its input has not been ended. */
    boolean acceptsInput() {
        return service.isRunning() && !inputClosed;
    }

    /** False once {@link #closeInput} has ended the program's input. */
    boolean inputOpen() {
        return !inputClosed;
    }

    void sendInput(String line) {
        if (acceptsInput()) {
            service.sendInput(line);
        }
    }

    /** Ends the program's standard input: whatever reads it sees end of file. */
    void closeInput() {
        inputClosed = true;
        service.closeInput();
    }

    /** Ends the program but keeps listening: its remaining output and its exit are still delivered. */
    void terminate() {
        service.stop();
    }

    /** Ends the program and forgets it: nothing more is delivered (Stop, Restart, the window closing). */
    void kill() {
        service.shutdown();
    }
}
