package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.RunConfiguration;
import com.editora.editor.EditorBuffer;
import com.editora.run.ProgramArgs;
import com.editora.run.PythonRunPlan;
import com.editora.run.ScriptRunCommand;
import com.editora.test.PytestScanner;
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

    /**
     * Debug Test at Caret on a pytest file ({@code test_*.py}, {@code *_test.py}): the test at the caret (its class
     * with {@code classLevel}, the whole file outside any test) runs under pytest in debugpy. False — nothing
     * done — for any other buffer, which the caller then handles as before.
     */
    boolean debugPythonTestAtCaret(@Nullable EditorBuffer b, boolean classLevel) {
        if (b == null
                || b.getPath() == null
                || !b.isPython()
                || !com.editora.test.PytestScanner.isTestFile(
                        b.getPath().getFileName().toString())) {
            return false;
        }
        int caret = b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
        var found = d.scan(
                b, "pytest:" + caret, t -> PytestScanner.atLine(t, caret), () -> debugPythonTestAtCaret(b, classLevel));
        if (found == null) {
            return true; // found off the FX thread (PERF-01): asked again once it is
        }
        PytestScanner.TestTarget target = found.value();
        if (classLevel && target != null && target.className() != null) {
            target = new com.editora.test.PytestScanner.TestTarget(
                    target.line(), target.endLine(), target.className(), null);
        }
        debugPythonTest(b, target);
        return true;
    }

    /**
     * Debugs {@code target} of pytest file {@code b} ({@code null}: the whole file): {@code python -m pytest
     * <node id>} under debugpy, from the project root (the file's folder with no project), under the file's
     * interpreter. Without pytest, a {@code unittest.TestCase} test runs under {@code python -m unittest}
     * instead. Public for the per-test gutter ▶'s Debug entry ({@code TestNavigationCoordinator}).
     */
    public void debugPythonTest(EditorBuffer b, com.editora.test.PytestScanner.@Nullable TestTarget target) {
        Path file = b == null ? null : b.getPath();
        if (file == null) {
            d.host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        Path root = rootOf(file);
        Path cwd = root != null ? root : file.getParent();
        if (cwd == null) {
            return; // a saved file is always in a folder
        }
        String relative = cwd.relativize(file).toString().replace('\\', '/');
        var fallback = d.scan( // the class header is looked for off the FX thread (PERF-01)
                b, "unittest:" + target, t -> unittestFallback(t, relative, target), () -> debugPythonTest(b, target));
        if (fallback == null) {
            return;
        }
        debugPythonTests(
                file,
                cwd,
                List.of(com.editora.test.PytestScanner.nodeId(relative, target)),
                java.util.Objects.requireNonNullElse(fallback.value(), List.of()),
                file.getFileName() + (target == null ? "" : " :: " + target.displayName()));
    }

    /**
     * Pure: the pytest test a Python test glyph stands for. The glyphs carry {@link
     * com.editora.test.JavaTestScanner.TestTarget}s ({@code RunScan.pythonTestLines}): a class (method null), a
     * module-level function (class {@code ""}), or — class {@code ""} and no method — the whole file (null here).
     */
    void debugPythonGlyph(EditorBuffer b, com.editora.test.JavaTestScanner.TestTarget glyph) {
        var found = d.scan(b, "glyph:" + glyph, t -> pytestTarget(t, glyph), () -> debugPythonGlyph(b, glyph));
        if (found != null) { // the test's end is found off the FX thread (PERF-01): asked again once it is
            debugPythonTest(b, found.value());
        }
    }

    static com.editora.test.PytestScanner.@Nullable TestTarget pytestTarget(
            String source, com.editora.test.JavaTestScanner.TestTarget glyph) {
        if (glyph == null || (glyph.className().isEmpty() && glyph.methodName() == null)) {
            return null;
        }
        com.editora.test.PytestScanner.TestTarget found = com.editora.test.PytestScanner.atLine(source, glyph.line());
        int end = found == null ? glyph.line() : found.endLine();
        return new com.editora.test.PytestScanner.TestTarget(
                glyph.line(), end, glyph.className().isEmpty() ? null : glyph.className(), glyph.methodName());
    }

    /**
     * Debugs Python tests by id in {@code cwd}, under {@code anchor}'s interpreter (the test file, or the folder
     * a run was in): pytest node ids — with {@code unittestIds} to fall back on when the interpreter has no
     * pytest — or, with no pytest ids, {@code python -m unittest} on {@code unittestIds} (a run that was
     * unittest's). What a Test Results row's Debug and a test ▶'s Debug Test come to.
     */
    void debugPythonTests(
            Path anchor, Path cwd, List<String> pytestIds, List<String> unittestIds, @Nullable String label) {
        if (!d.debugEffectiveFor("python", anchor)) {
            d.host.setStatus(d.unavailableMessage("python", anchor));
            return;
        }
        EditorBuffer open = d.ops.bufferForPath(anchor);
        if (open != null
                && !d.savedOrThen(List.of(open), () -> debugPythonTests(anchor, cwd, pytestIds, unittestIds, label))) {
            return;
        }
        List<String> fallback = unittestIds == null ? List.of() : unittestIds;
        d.ops.openToolWindow();
        d.nameSession(label);
        d.dap().setProgramArgs(List.of());
        plan(anchor, open == null ? null : PythonDebugLaunch.firstLine(open.getContent()));
        d.s().relaunch = () -> debugPythonTests(anchor, cwd, pytestIds, unittestIds, label);
        d.withClosedBreakpoints(() -> {
            if (pytestIds == null || pytestIds.isEmpty()) {
                d.dap().startPythonModule(anchor, "unittest", fallback, cwd);
            } else {
                d.dap().startPythonTest(anchor, pytestIds, fallback, cwd);
            }
        });
    }

    /**
     * Pure: the {@code python -m unittest} arguments that run {@code target} when pytest is missing — only for a
     * {@code unittest.TestCase} class (or a method of one), named as a dotted path from the folder it runs in;
     * empty when unittest cannot run it.
     */
    static List<String> unittestFallback(
            String source, String relative, com.editora.test.PytestScanner.@Nullable TestTarget target) {
        if (target == null || target.className() == null || !relative.endsWith(".py")) {
            return List.of();
        }
        String[] lines = source.split("\n", -1);
        String header = target.line() < lines.length ? lines[target.line()] : "";
        if (!header.contains("TestCase")) {
            // the method's class header: the nearest "class Name" above
            header = "";
            for (int i = Math.min(target.line(), lines.length - 1); i >= 0; i--) {
                if (lines[i].stripLeading().startsWith("class " + target.className())) {
                    header = lines[i];
                    break;
                }
            }
        }
        if (!header.contains("TestCase")) {
            return List.of();
        }
        String module = relative.substring(0, relative.length() - 3).replace('/', '.');
        return List.of(
                module + "." + target.className() + (target.functionName() == null ? "" : "." + target.functionName()));
    }
}
