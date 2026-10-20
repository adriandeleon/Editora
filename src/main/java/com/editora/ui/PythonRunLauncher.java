package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.config.Settings;
import com.editora.process.ExecTargets;
import com.editora.run.PythonRunPlan;
import org.jspecify.annotations.Nullable;

/**
 * Plans a Python launch ({@link PythonRunPlan}) for {@code RunCoordinator} and the pytest runner on the machine
 * the program runs on. On this machine the plan is a few file checks and is made at once; for a remote host,
 * a WSL distribution or a container the lookups (its venv, its {@code python} tool) are made off the FX thread
 * and the plan is delivered back on it.
 */
final class PythonRunLauncher {

    private PythonRunLauncher() {}

    /**
     * The interpreter setting for a program that runs where {@code where} is: on this machine the global
     * setting; elsewhere the host's {@code python} tool, else the global setting only when it is a bare command
     * name (a path names a file on this machine), else nothing (that host's own {@code python3}).
     */
    static String setting(@Nullable Path where, @Nullable Settings settings) {
        String global = settings == null ? "" : settings.getPythonDebugCommand().strip();
        if (where == null || ExecTargets.runsHere(where)) {
            return global;
        }
        String tool = ExecTargets.remoteTool(where, "python");
        if (tool != null && !tool.isBlank()) {
            return tool.strip();
        }
        String first = com.editora.run.ProgramArgs.tokenize(global).stream()
                .findFirst()
                .orElse("");
        return first.indexOf('/') < 0 && first.indexOf('\\') < 0 ? global : "";
    }

    /** The machine a program run in {@code where} runs on. */
    static PythonRunPlan.Host hostFor(@Nullable Path where) {
        if (where == null || ExecTargets.runsHere(where)) {
            return PythonRunPlan.Host.here();
        }
        var target = ExecTargets.forPath(where);
        return PythonRunPlan.Host.elsewhere(target::toTargetPath);
    }

    /**
     * Plans {@code request} for a program run in {@code where} and hands the plan to {@code onPlan} on the FX
     * thread: at once on this machine, after the lookups elsewhere. {@code firstLine} is read by the plan
     * thread when the request has none and the file is remote (a network round trip).
     */
    static void plan(PythonRunPlan.Request request, @Nullable Path where, Consumer<PythonRunPlan> onPlan) {
        PythonRunPlan.Host host = hostFor(where);
        if (host.local()) {
            onPlan.accept(PythonRunPlan.plan(request, host));
            return;
        }
        Thread.ofVirtual().name("python-run-plan").start(() -> {
            PythonRunPlan.Request r = request;
            if (r.firstLine() == null && r.file() != null && !r.module()) {
                r = new PythonRunPlan.Request(
                        r.file(),
                        r.projectRoot(),
                        r.target(),
                        r.module(),
                        r.args(),
                        RunCoordinator.firstLine(r.file()),
                        r.configured(),
                        r.interpreterOptions(),
                        r.projectChoice(),
                        r.setting(),
                        r.userEnv());
            }
            PythonRunPlan plan;
            try {
                plan = PythonRunPlan.plan(r, host);
            } catch (RuntimeException e) {
                // A lookup over a dropped connection: run the host's own python3 rather than nothing.
                plan = PythonRunPlan.plan(
                        r, PythonRunPlan.Host.elsewhere(host.toTarget()).withoutLookups());
            }
            PythonRunPlan done = plan;
            Platform.runLater(() -> onPlan.accept(done));
        });
    }
}
