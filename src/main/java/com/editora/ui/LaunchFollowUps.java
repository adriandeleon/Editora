package com.editora.ui;

import java.nio.file.Path;

import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * What a launch.json configuration asks for besides its launch, for {@link DebugCoordinator}: its
 * {@code serverReadyAction}, watched in the program's output, and its {@code postDebugTask}, run when its session
 * ends ({@link LaunchFollowUp} holds one launch's). FX thread only.
 */
final class LaunchFollowUps {

    private final DebugCoordinator d;

    LaunchFollowUps(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * What configuration {@code name} asks for besides its launch ({@link LaunchFollowUp}): its
     * {@code serverReadyAction} and {@code postDebugTask}; null when it asks for neither.
     */
    @Nullable
    LaunchFollowUp followUp(
            Path launchJson,
            Path workspace,
            com.editora.dap.LaunchJson.File parsed,
            String name,
            java.util.Map<String, Object> body,
            java.util.Map<String, String> answers,
            @Nullable EditorBuffer b) {
        com.editora.dap.ServerReadyAction ready = null;
        String problem = "";
        try {
            ready = com.editora.dap.ServerReadyAction.of(body, workspace.toString());
        } catch (com.editora.dap.ServerReadyAction.Invalid e) {
            problem = tr("debug.serverReady.invalid", e.getMessage());
        }
        String post = body.get("postDebugTask") instanceof String t ? t.strip() : "";
        if (ready == null && post.isEmpty() && problem.isEmpty()) {
            return null;
        }
        LaunchFollowUp f = new LaunchFollowUp(name, ready, post, launchJson, workspace, parsed, answers, b);
        f.problem = problem;
        return f;
    }

    /**
     * {@code session}'s follow-up counts from its next start, and ends with it: the address's session is stopped when
     * it asked for that, and the {@code postDebugTask} runs.
     */
    void state(DebugSession session, DapManager.State state) {
        LaunchFollowUp f = session.followUp;
        if (f == null) {
            return;
        }
        if (state == DapManager.State.STARTING && !f.started) {
            f.started = true;
            if (!f.problem.isEmpty()) {
                d.later(() -> d.panel().appendNotice(f.problem)); // after the start has cleared the console
            }
        } else if (state == DapManager.State.INACTIVE && f.started) {
            session.followUp = null;
            ended(f, null);
        }
    }

    /** The program's output, watched for its {@code serverReadyAction}'s pattern; acted on at the first match. */
    void output(String text, String category) {
        LaunchFollowUp f = d.s().followUp;
        com.editora.dap.ServerReadyAction ready = f == null ? null : f.serverReady;
        if (f == null
                || ready == null
                || !f.started
                || DapManager.COMMAND_CATEGORY.equals(category)
                || "telemetry".equals(category)) {
            return;
        }
        com.editora.dap.ServerReadyAction.Result r = ready.feed(text);
        if (r == null) {
            return;
        }
        String uri = r.uri();
        if (uri == null) {
            d.panel().appendNotice(tr("debug.serverReady.noAddress", r.problem()));
            return;
        }
        String os = com.editora.dap.LaunchJson.osKey(com.editora.Os.current());
        switch (ready.kind()) {
            case OPEN_EXTERNALLY -> {
                d.panel().appendNotice(tr("debug.serverReady.opening", uri));
                d.host.openExternalUrl(uri);
            }
            case DEBUG_WITH_CHROME, DEBUG_WITH_EDGE -> {
                java.util.Map<String, Object> body = ready.browserLaunch(uri);
                if (!d.browser.browserArgs.isEmpty()) {
                    body.put("runtimeArgs", d.browser.browserArgs); // as F5 on a page starts it (headless in tests)
                }
                startFollowing(f, String.valueOf(body.get("name")), uri, body);
            }
            case START_DEBUGGING -> {
                java.util.Map<String, Object> config = ready.config();
                if (config != null) {
                    String name = config.get("name") instanceof String n && !n.isBlank() ? n : f.name;
                    startFollowing(f, name, uri, com.editora.dap.LaunchJson.forPlatform(config, os));
                    return;
                }
                com.editora.dap.LaunchJson.Configuration c = f.parsed.configuration(ready.name());
                if (c == null) {
                    d.panel().appendNotice(tr("debug.serverReady.missing", ready.name()));
                    return;
                }
                startFollowing(f, c.name(), uri, com.editora.dap.LaunchJson.forPlatform(c.body(), os));
            }
        }
    }

    /**
     * Starts configuration {@code body} ({@code name}) for the server of {@code f}, ready at {@code uri}: beside it, in
     * a session of its own, which the server's end stops when the action says {@code killOnServerStop}.
     */
    private void startFollowing(LaunchFollowUp f, String name, String uri, java.util.Map<String, Object> body) {
        d.panel().appendNotice(tr("debug.serverReady.starting", name, uri));
        DebugSession server = d.s();
        d.launchJson.askInputs(
                f.parsed.inputs(),
                com.editora.dap.LaunchJson.inputsUsed(body),
                0,
                new java.util.LinkedHashMap<>(f.answers),
                answers -> {
                    DebugSession target = d.inSession(server, d::sessionForLaunch);
                    f.child = target;
                    f.childAt = target.runtimeGeneration;
                    d.inSession(
                            target,
                            () -> d.launchJson.resolveAndRun(
                                    f.launchJson, f.workspace, f.parsed, name, body, answers, f.buffer));
                });
    }

    /**
     * The session of {@code f} ended: the session its address started is stopped when the action says
     * {@code killOnServerStop}, then the {@code postDebugTask} runs, then {@code then} (a restart's launch again).
     */
    void ended(LaunchFollowUp f, @Nullable Runnable then) {
        com.editora.dap.ServerReadyAction ready = f.serverReady;
        DebugSession child = f.child;
        if (ready != null
                && ready.killOnServerStop()
                && child != null
                && f.childHolds()
                && d.sessions().all().contains(child)) {
            d.inSession(child, () -> {
                if (d.sessionLive()) {
                    d.stop();
                }
            });
        }
        if (f.postDebugTask.isEmpty()) {
            if (then != null) {
                then.run();
            }
            return;
        }
        String task = f.postDebugTask;
        d.launchJson.preLaunchTask(f.launchJson, f.workspace, task, f.buffer, f.answers, argv -> {
            if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
                d.host.setStatus(tr("status.run.busy"));
                return;
            }
            BeforeLaunchStep.Console console = d.beforeLaunchConsole(false); // after the program's output
            d.beforeLaunch()
                    .run(
                            d.host,
                            new BeforeLaunchStep.Command(task, argv, "status.run.postDebugTask"),
                            f.workspace,
                            java.util.Map.of(),
                            new BeforeLaunchStep.Console() {
                                @Override
                                public void started(String commandLine) {
                                    console.started(commandLine);
                                }

                                @Override
                                public void output(String line, boolean stderr) {
                                    console.output(line, stderr);
                                }

                                @Override
                                public void ended(int code, @Nullable String launchError) {
                                    console.ended(code, launchError);
                                    if (then == null) { // no launch follows: the next one starts afresh
                                        d.s().consoleStarted = false;
                                        d.s().announceStart = false;
                                    }
                                }
                            },
                            () -> d.later(() -> {
                                if (then != null) {
                                    then.run();
                                } else {
                                    d.host.setStatus(tr("status.run.postDebugTaskDone", task));
                                }
                            }));
        });
    }
}
