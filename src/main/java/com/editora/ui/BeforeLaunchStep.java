package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import com.editora.build.BuildTool;
import com.editora.config.RunConfiguration;
import com.editora.run.BuildBeforeLaunch;
import com.editora.run.ProgramArgs;
import com.editora.run.RunService;

import static com.editora.i18n.Messages.tr;

/**
 * A run configuration's before-launch command — usually a build — run as a process the user can see and
 * stop, gating the launch that follows it.
 *
 * <p>It goes through {@link RunService}, the same streaming service a program runs through, which is what
 * gives it everything the old capture-all {@code ProcessRunner.run} on an ad-hoc thread lacked: its output
 * streams into a console instead of vanishing until the end; it is registered with {@code ProcessRegistry},
 * so it is killed with the window instead of outliving it; {@link #stop} kills it; and the owner is
 * {@link #isActive busy} from the request until the process exits, so a second Run press reports "already
 * running" instead of starting a second, concurrent build of the same project.
 *
 * <p>Shared by {@link RunCoordinator} and {@link DebugCoordinator}: a failed build must abort a debug launch
 * exactly as it aborts a run, or every breakpoint sits on a stale line number.
 */
final class BeforeLaunchStep {

    /** Where the step shows itself. All calls arrive on the FX thread. */
    interface Console {
        /** The command is starting ({@code commandLine} is what will run). */
        void started(String commandLine);

        void output(String line, boolean stderr);

        /** The step is over: {@code launchError} non-null when it never started, else its exit {@code code}. */
        void ended(int code, String launchError);
    }

    private final RunService service;

    /** Whether the owner has a console input field wired to {@link #service}, so a prompt can be answered. */
    private final boolean interactive;

    /** FX thread: true from the request until the process has exited (or failed to start). */
    private boolean active;

    private boolean stopRequested;

    /**
     * @param service the streaming service the command runs on. Passing the owner's own service makes the
     *     step and the program mutually exclusive, which is what a Run console wants.
     */
    BeforeLaunchStep(RunService service) {
        this(service, false);
    }

    /**
     * @param interactive false closes the command's stdin as soon as it has started: with nowhere to type an
     *     answer (the Debug console), a step that reads stdin would otherwise wait forever and hold the
     *     launch in "preparing". It sees end of input instead, as it did when steps ran through
     *     {@code ProcessRunner}.
     */
    BeforeLaunchStep(RunService service, boolean interactive) {
        this.service = service;
        this.interactive = interactive;
    }

    /** Whether a before-launch command is in flight — the owner is busy and its Stop control applies. */
    boolean isActive() {
        return active;
    }

    /** Whether the last step ended because {@link #stop} was asked (a retry must not follow a Stop). */
    boolean wasStopped() {
        return stopRequested;
    }

    /**
     * Runs {@code cfg}'s before-launch command, if it has one, then {@code then} — or reports the failure and
     * runs nothing. With no before-launch step this is a straight call, so the common case is unchanged.
     * {@code then} runs on the FX thread, after {@link Console#ended}. A symbolic step ({@code maven:compile})
     * is run through {@code executables}, which answers a tool's argv prefix — or null when the launch may
     * not run it (the user declined to trust the project's wrapper): then nothing runs, and the gate has
     * already said why.
     */
    void run(
            CoordinatorHost host,
            RunConfiguration cfg,
            Function<BuildTool, List<String>> executables,
            Path cwd,
            Map<String, String> environment,
            Console console,
            Runnable then) {
        List<String> argv = argvFor(cfg, executables);
        if (argv == null) {
            return;
        }
        run(host, new Command(cfg.name(), argv, "status.run.beforeLaunch"), cwd, environment, console, then);
    }

    /** As {@link #run(CoordinatorHost, RunConfiguration, Function, Path, Map, Console, Runnable)} for a plain command. */
    void run(
            CoordinatorHost host,
            RunConfiguration cfg,
            Path cwd,
            Map<String, String> environment,
            Console console,
            Runnable then) {
        run(host, cfg, tool -> List.of(tool.commandExample()), cwd, environment, console, then);
    }

    /**
     * The command {@code cfg}'s before-launch field names: empty for none, the tokenized text for a plain
     * command, a build tool's executable + goals for a symbolic step — or null when {@code executables}
     * refused the tool (nothing may run). Pure given {@code executables}.
     */
    static List<String> argvFor(RunConfiguration cfg, Function<BuildTool, List<String>> executables) {
        String command = cfg.beforeLaunch();
        if (command == null || command.isBlank()) {
            return List.of();
        }
        BuildBeforeLaunch.Symbolic symbolic = BuildBeforeLaunch.symbolic(command);
        if (symbolic == null) {
            return ProgramArgs.tokenize(command);
        }
        List<String> executable = executables.apply(symbolic.tool());
        return executable == null ? null : BuildBeforeLaunch.withGoals(executable, symbolic.goals());
    }

    /**
     * A step of the launch's own rather than a configuration's: the compile before a Kotlin program runs.
     *
     * @param name what the status messages call it (a configuration's or a file's name)
     * @param argv the command; empty runs {@code then} straight away
     * @param statusKey the message said while it runs; {@code <statusKey>Failed} (with the name and the last
     *     line of output) and {@code <statusKey>Stopped} (with the name) say how it ended otherwise
     */
    record Command(String name, List<String> argv, String statusKey) {}

    /**
     * What a failed step's status quotes: the first {@code [ERROR]} line a build tool printed (Maven's says
     * what went wrong; its last line only says where to read more — review D4), else the last non-blank
     * line, else the exit code.
     */
    static String failureDetail(String firstError, String lastLine, int code) {
        if (firstError != null && !firstError.isBlank()) {
            return firstError.strip();
        }
        return lastLine == null || lastLine.isBlank() ? "exit " + code : lastLine.strip();
    }

    /** Whether {@code line} is a build tool's error line ({@code [ERROR] …}, Gradle's {@code e: …} / {@code error: …}). */
    static boolean isErrorLine(String line) {
        if (line == null) {
            return false;
        }
        String t = line.strip();
        return t.startsWith("[ERROR]") || t.startsWith("error:") || t.startsWith("e: ") || t.contains(": error:");
    }

    /** Runs {@code command}, then {@code then} when it exits 0 — or reports the failure and runs nothing. */
    void run(
            CoordinatorHost host,
            Command command,
            Path cwd,
            Map<String, String> environment,
            Console console,
            Runnable then) {
        List<String> argv = command.argv();
        if (argv.isEmpty()) {
            then.run();
            return;
        }
        if (active || service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        active = true;
        stopRequested = false;
        String[] lastLine = {""};
        String[] firstError = {null};
        host.setStatus(tr(command.statusKey(), command.name()));
        console.started(String.join(" ", argv));
        service.runInDir(cwd, argv, environment, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                // the header is already up; the resolved path adds nothing the user typed
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                if (!line.isBlank()) {
                    lastLine[0] = line; // a build tool's summary, not its banner
                    if (firstError[0] == null && isErrorLine(line)) {
                        firstError[0] = line;
                    }
                }
                console.output(line, stderr);
            }

            @Override
            public void onExit(int code) {
                active = false;
                console.ended(code, null);
                if (stopRequested) {
                    host.setStatus(tr(command.statusKey() + "Stopped", command.name()));
                } else if (code == 0) {
                    then.run();
                } else {
                    // Surface the tool's own output: "before-launch failed" alone tells the user nothing
                    // about which step or why.
                    host.setStatus(tr(
                            command.statusKey() + "Failed",
                            command.name(),
                            failureDetail(firstError[0], lastLine[0], code)));
                }
            }

            @Override
            public void onError(String message) {
                active = false;
                console.ended(-1, message);
                host.setStatus(tr(command.statusKey() + "Failed", command.name(), message));
            }
        });
        if (!interactive) {
            service.closeInput();
        }
    }

    /** Kills the running step (and its children); the launch it was gating does not happen. */
    void stop() {
        if (active) {
            stopRequested = true;
            service.stop();
        }
    }
}
