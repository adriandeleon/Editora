package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.RunConfiguration;
import com.editora.run.ProgramArgs;
import com.editora.run.PythonRunPlan;
import com.editora.run.ScriptRunCommand;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Python launches of {@link DebugCoordinator}: the plan a launch hands debugpy (the interpreter Run would start,
 * {@link PythonRunPlan}), and Debug of a Python script or module run configuration — its target, arguments, working
 * directory, Interpreter, Interpreter options and variables, after its before-launch step, as Run runs it. A
 * configuration that serves a web app ({@code manage.py runserver}, {@code python -m flask}, {@code uvicorn})
 * follows the server's reloader into its child ({@code DapManager.servesWeb}). Part of {@link DebugCoordinator}.
 */
final class PythonDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    PythonDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * Hands the next Python launch Run's plan inputs ({@link PythonRunPlan}) for {@code file}: its project root, the
     * interpreter picked for the project, the interpreter setting where it runs (a remote host's {@code python}
     * tool), its first line (a shebang) — and that machine. Debug and Run then start the same Python with the same
     * {@code PYTHONPATH}, here, on a host, in WSL or in a container.
     */
    void plan(Path file, @Nullable String firstLine) {
        Path dir = file.getParent() == null ? file : file.getParent();
        d.dap()
                .setPythonPlan(
                        PythonRunPlan.Request.file(
                                file,
                                rootOf(file),
                                com.editora.process.ExecTargets.forPath(file).toTargetPath(file),
                                List.of(),
                                firstLine,
                                d.ops.pythonInterpreterChoice(),
                                PythonRunLauncher.setting(dir, d.host.settings())),
                        PythonRunLauncher.hostFor(dir));
    }

    static @Nullable String firstLine(@Nullable String text) {
        if (text == null) {
            return null;
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** This window's project root when {@code file} is inside it, else null. */
    @Nullable
    Path rootOf(Path file) {
        Path root = d.ops.projectRoot();
        return root != null
                        && file.toAbsolutePath()
                                .normalize()
                                .startsWith(root.toAbsolutePath().normalize())
                ? root
                : null;
    }

    /** Debugs a Python script or module configuration; its files are read off the FX thread (PERF-01). */
    void debugConfig(RunConfiguration cfg) {
        List<String> args = d.debugArgs(cfg.args());
        if (args == null) {
            return;
        }
        List<String> options;
        try {
            options = ProgramArgs.parse(cfg.interpreterOptions());
        } catch (ProgramArgs.UnclosedQuoteException e) {
            d.host.setStatus(tr("status.run.optionsUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        boolean module = ScriptRunCommand.PYTHON_MODULE.equals(cfg.type());
        if (cfg.target().isBlank()) {
            d.host.setStatus(tr("status.run.configNeedsTarget", cfg.name()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        Path root = d.ops.projectRoot();
        Path cwd = com.editora.run.RunConfigWorkingDirectory.resolve(cfg, root);
        if (cwd == null) {
            d.host.setStatus(tr("status.run.configNeedsWorkingDir", cfg.name()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        boolean remote = com.editora.vfs.Vfs.isRemote(cwd);
        boolean elsewhere = !com.editora.process.ExecTargets.runsHere(cwd);
        d.planOffFx(
                "debug-python-config-plan", () -> ScriptConfigPlan.of(cfg, root, root, cwd, remote, elsewhere), a -> {
                    if (a.value() != null) {
                        debugConfig(cfg, args, options, module, cwd, remote, a.value());
                    }
                });
    }

    private void debugConfig(
            RunConfiguration cfg,
            List<String> args,
            List<String> options,
            boolean module,
            Path cwd,
            boolean remote,
            ScriptConfigPlan plan) {
        Path script = plan.script();
        if (plan.dirMissing()) {
            d.host.setStatus(tr("status.run.workingDirMissing", cfg.name(), cwd.toString()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        if (!module && (script == null || plan.scriptMissing())) {
            d.host.setStatus(
                    script == null
                            ? tr("status.run.configNeedsTarget", cfg.name())
                            : tr("status.run.scriptMissing", cfg.name(), script.toString()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        // A module has no file of its own: the session is anchored in its folder (where its venv is looked for).
        Path anchor = module || script == null ? cwd.resolve("__main__.py") : script;
        if (!d.debugEffectiveFor("python", anchor)) {
            d.host.setStatus(d.unavailableMessage("python", anchor));
            return;
        }
        if (DebugPlanning.envProblem(d.host, d.ops::editConfiguration, cfg, plan.env(), plan.envError())) {
            return;
        }
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        String target = module
                ? cfg.target().strip()
                : com.editora.process.ExecTargets.forPath(anchor).toTargetPath(anchor);
        PythonRunPlan.Request request = new PythonRunPlan.Request(
                anchor,
                d.ops.projectRoot(),
                target,
                false,
                List.of(),
                module || remote ? null : plan.firstLine(),
                cfg.interpreter(),
                options,
                d.ops.pythonInterpreterChoice(),
                PythonRunLauncher.setting(cwd, d.host.settings()),
                plan.env());
        String name = cfg.name();
        d.s().relaunch = () -> d.debugConfig(d.currentConfiguration(name, cfg));
        d.beforeLaunch().run(d.host, cfg, cwd, plan.env(), d.beforeLaunchConsole(), () -> {
            d.ops.openToolWindow();
            d.nameSession(name);
            d.dap().setProgramArgs(List.of());
            d.dap().setPythonPlan(request, PythonRunLauncher.hostFor(cwd));
            d.withClosedBreakpoints(() -> {
                if (module) {
                    d.dap().startPythonModule(anchor, target, args, cwd);
                } else {
                    d.dap().startPythonScript(anchor, args, cwd);
                }
            });
        });
    }
}
