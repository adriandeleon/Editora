package com.editora.ui;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.RunConfiguration;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Debugging a Node.js program: a JavaScript or TypeScript file, or a {@code node} or {@code npm} run configuration,
 * planned as Run plans it ({@link NodeRunLauncher}, the package manager) and launched under vscode-js-debug
 * ({@link NodeDebugConfigs}). Part of {@link DebugCoordinator}.
 */
final class NodeDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    NodeDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * Debug on a {@code node} or {@code npm} run configuration: the same launch Run makes ({@link NodeDebugConfigs}),
     * under vscode-js-debug. Restart reads the configuration again.
     */
    void debugNodeConfig(RunConfiguration cfg) {
        if (!d.debugEffectiveFor("javascript")) {
            d.host.setStatus(d.unavailableMessage("javascript", null));
            return;
        }
        String name = cfg.name();
        List<String> args;
        List<String> nodeOptions;
        try {
            args = com.editora.run.ProgramArgs.parse(cfg.args());
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            d.host.setStatus(tr("status.run.argsUnclosedQuote", String.valueOf(e.quote())));
            d.ops.editConfiguration(name);
            return;
        }
        try {
            nodeOptions = com.editora.run.ProgramArgs.parse(cfg.interpreterOptions());
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            d.host.setStatus(tr("status.run.optionsUnclosedQuote", name, String.valueOf(e.quote())));
            d.ops.editConfiguration(name);
            return;
        }
        Path root = d.ops.projectRoot(); // its files are read off the FX thread (PERF-01)
        d.planOffFx("debug-config-plan", () -> DebugPlanning.readNode(cfg, root), a -> {
            if (a.value() != null) {
                debugNodeConfig(cfg, args, nodeOptions, root, a.value());
            }
        });
    }

    private void debugNodeConfig(
            RunConfiguration cfg,
            List<String> args,
            List<String> nodeOptions,
            @Nullable Path root,
            DebugPlanning.NodeRead read) {
        String name = cfg.name();
        Path cwd = read.cwd();
        if (cwd == null) {
            d.host.setStatus(tr("status.run.configNeedsWorkingDir", name));
            d.ops.editConfiguration(name);
            return;
        }
        if (!com.editora.process.ExecTargets.runsHere(cwd)) {
            d.host.setStatus(tr("status.debug.nodeConfigRemote", tr("settings.runConfig.type." + cfg.type())));
            return;
        }
        if (DebugPlanning.envProblem(d.host, d.ops::editConfiguration, cfg, read.env(), read.envError())) {
            return;
        }
        Map<String, String> env = read.env();
        d.s().relaunch = () -> d.debugConfig(d.currentConfiguration(name, cfg));
        if (com.editora.run.ScriptRunCommand.NPM.equals(cfg.type())) {
            com.editora.run.RunTrust.Gate npmGate = com.editora.run.RunTrust.npm(cwd);
            if (!d.factsKnown(
                            "debug-npm-trust-look",
                            d.facts.gate(npmGate),
                            () -> debugNodeConfig(cfg, args, nodeOptions, root, read))
                    || !d.trusted(npmGate)) {
                return; // a package script runs its pre/post hooks: npm's workspace-trust gate, as Run asks it
            }
            // The project's own package manager, as Run and the npm tool window use it.
            var detected = read.npm();
            if (detected == null) {
                return; // not reached: an npm configuration's plan detects its manager
            }
            String override = d.host.settings().getNpmCommand();
            List<String> manager = com.editora.build.BuildExecutable.resolve(
                    List.of(),
                    com.editora.build.NpmPackageManager.overrideApplies(override, detected.manager()) ? override : "",
                    detected.manager());
            Map<String, String> npmEnv = new LinkedHashMap<>(NpmRunSupport.environment(System.getenv("NO_COLOR")));
            npmEnv.putAll(env);
            startJsProgram(
                    cwd.resolve("package.json"),
                    NodeDebugConfigs.forPackageManager(
                            com.editora.run.ScriptRunCommand.npm(manager, cfg.target(), args), cwd, npmEnv),
                    name);
            return;
        }
        Path script = read.script();
        if (script == null || read.scriptMissing()) {
            d.host.setStatus(tr("status.run.scriptMissing", name, String.valueOf(script)));
            d.ops.editConfiguration(name);
            return;
        }
        NodeRunLauncher.plan(
                new NodeRunLauncher.Spec(
                        script,
                        root,
                        cfg.interpreter(),
                        d.host.settings().getNodePath(),
                        args,
                        nodeOptions,
                        false,
                        env,
                        cfg.workingDir().isBlank() ? null : cwd),
                ready -> {
                    if (ready.plan().error() != null) {
                        d.host.setStatus(tr(ready.plan().error()));
                        return;
                    }
                    var program = NodeDebugConfigs.fromRunPlan(
                            ready.plan().argv(),
                            ready.target(), // the compiled .js when that runs instead of a .ts
                            ready.plan().env(),
                            ready.dir());
                    if (program == null) {
                        d.host.setStatus(
                                tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type." + cfg.type())));
                        return;
                    }
                    startJsProgram(script, program, name);
                });
    }

    /**
     * Debug on a local JavaScript or TypeScript file: planned by {@link NodeRunLauncher} as Run plans it, then split
     * into js-debug's launch ({@link NodeDebugConfigs#fromRunPlan}). A plan js-debug cannot launch — a shebang
     * naming Deno or Bun — falls back to the file under the resolved Node.js.
     */
    void debugJsFile(Path file, List<String> fileArgs, List<String> nodeArgs) {
        if (nodeArgs.isEmpty()) { // an Electron app's main-process file starts the app, else Node.js runs the file
            d.electron.debugOr(file, fileArgs, () -> debugNodeFile(file, fileArgs, nodeArgs));
        } else {
            debugNodeFile(file, fileArgs, nodeArgs);
        }
    }

    private void debugNodeFile(Path file, List<String> fileArgs, List<String> nodeArgs) {
        String problem = com.editora.dap.JsLaunch.launchProblemKey(String.valueOf(file.getFileName()), null);
        NodeRunLauncher.plan(
                new NodeRunLauncher.Spec(
                        file,
                        d.ops.projectRoot(),
                        "",
                        d.host.settings().getNodePath(),
                        fileArgs,
                        nodeArgs,
                        false,
                        Map.of(),
                        null),
                ready -> {
                    String why = ready.plan().error() != null
                            ? ready.plan().error()
                            : NodeDebugConfigs.nodeRuns(ready, file) ? problem : null; // JSX/TSX on plain Node
                    if (why != null) {
                        d.host.setStatus(tr(why, "?"));
                        return;
                    }
                    var program = NodeDebugConfigs.fromRunPlan(
                            ready.plan().argv(), ready.target(), ready.plan().env(), ready.dir());
                    if (program == null || !NodeDebugConfigs.debuggableRuntime(program.runtime())) {
                        d.withClosedBreakpoints(
                                () -> d.dap().startLaunch(file, "javascript", d::pickMainClass, "", null));
                        return;
                    }
                    d.dap().setProgramArgs(List.of());
                    d.dap().setJsNodeArgs(List.of());
                    d.dap().setJsProgram(program);
                    d.withClosedBreakpoints(() -> d.dap().startLaunch(file, "javascript", d::pickMainClass, "", null));
                });
    }

    /** Starts a planned JavaScript session called {@code label}, anchored at {@code anchor} (FX thread). */
    private void startJsProgram(Path anchor, com.editora.dap.DapManager.@Nullable JsProgram program, String label) {
        if (program == null) {
            return;
        }
        d.ops.openToolWindow();
        d.nameSession(label);
        d.dap().setProgramArgs(List.of());
        d.dap().setJsNodeArgs(List.of());
        d.dap().setJsProjectRoot(d.ops.projectRoot());
        d.dap().setJsProgram(program);
        d.withClosedBreakpoints(() -> d.dap().startLaunch(anchor, "javascript", d::pickMainClass, "", null));
    }
}
