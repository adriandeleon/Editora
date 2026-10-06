package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.RunConfiguration;
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

    /**
     * Runs {@code cfg}'s before-launch command, if it has one, then {@code then} — or reports the failure and
     * runs nothing. With no before-launch step this is a straight call, so the common case is unchanged.
     * {@code then} runs on the FX thread, after {@link Console#ended}.
     */
    void run(
            CoordinatorHost host,
            RunConfiguration cfg,
            Path cwd,
            Map<String, String> environment,
            Console console,
            Runnable then) {
        String command = cfg.beforeLaunch();
        List<String> argv = command == null || command.isBlank() ? List.of() : ProgramArgs.tokenize(command);
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
        host.setStatus(tr("status.run.beforeLaunch", cfg.name()));
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
                }
                console.output(line, stderr);
            }

            @Override
            public void onExit(int code) {
                active = false;
                console.ended(code, null);
                if (stopRequested) {
                    host.setStatus(tr("status.run.beforeLaunchStopped", cfg.name()));
                } else if (code == 0) {
                    then.run();
                } else {
                    // Surface the tool's own output: "before-launch failed" alone tells the user nothing
                    // about which step or why.
                    host.setStatus(tr(
                            "status.run.beforeLaunchFailed",
                            cfg.name(),
                            lastLine[0].isBlank() ? "exit " + code : lastLine[0].strip()));
                }
            }

            @Override
            public void onError(String message) {
                active = false;
                console.ended(-1, message);
                host.setStatus(tr("status.run.beforeLaunchFailed", cfg.name(), message));
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
