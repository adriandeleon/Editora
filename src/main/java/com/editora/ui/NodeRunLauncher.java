package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.process.ExecTargets;
import com.editora.process.ProcessRunner;
import com.editora.run.NodeRunPlan;
import com.editora.run.NodeRuntimes;
import com.editora.run.ProgramArgs;

/**
 * Plans a Node.js launch ({@link NodeRunPlan}) for {@code RunCoordinator}: Run File, a {@code node} run
 * configuration, or a test file's ▶. Which Node and which folder are disk and PATH questions
 * ({@link NodeRuntimes}), and a TypeScript file also asks that Node for its version, so the plan is made off the
 * FX thread and handed back on it.
 */
final class NodeRunLauncher {

    private NodeRunLauncher() {}

    /**
     * What to plan.
     *
     * @param file the script
     * @param projectRoot this window's project root, or null
     * @param configured a run configuration's Interpreter ("" for Run File)
     * @param setting the Node.js setting ("" = automatic)
     * @param args program arguments
     * @param nodeOptions a run configuration's Node arguments (tokenized)
     * @param test whether to run the file's tests ({@code node --test})
     * @param userEnv a run configuration's variables
     * @param workingDir a configuration's own working directory, or null for the package root
     */
    record Spec(
            Path file,
            Path projectRoot,
            String configured,
            String setting,
            List<String> args,
            List<String> nodeOptions,
            boolean test,
            Map<String, String> userEnv,
            Path workingDir) {}

    /** A ready launch: the plan and the folder it runs in. */
    record Ready(NodeRunPlan plan, Path dir) {}

    private static final Duration VERSION_TIMEOUT = Duration.ofSeconds(10);

    /** Plans {@code spec} off the FX thread; {@code onReady} runs on the FX thread. */
    static void plan(Spec spec, Consumer<Ready> onReady) {
        Thread.ofVirtual().name("node-run-plan").start(() -> {
            Ready ready = planNow(spec);
            Platform.runLater(() -> onReady.accept(ready));
        });
    }

    /** The blocking part of {@link #plan} (disk, PATH, a version probe). Not on the FX thread. */
    static Ready planNow(Spec spec) {
        Path file = spec.file();
        boolean here = ExecTargets.runsHere(file);
        NodeRuntimes.NodeRuntime runtime = here
                ? NodeRuntimes.resolveLocal(file, spec.projectRoot(), spec.configured(), spec.setting())
                : remoteRuntime(file, spec.configured());
        Path dir = spec.workingDir() != null
                ? spec.workingDir()
                : here ? NodeRuntimes.workingDirectory(file, spec.projectRoot()) : file.getParent();
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        String firstLine = RunCoordinator.firstLine(file);
        String version = null;
        if (NodeRunPlan.isTypeScript(name)) {
            version = version(runtime, dir);
        }
        String noColor = here ? System.getenv("NO_COLOR") : null;
        NodeRunPlan plan = NodeRunPlan.plan(new NodeRunPlan.Request(
                ExecTargets.forPath(file).toTargetPath(file),
                name,
                spec.args(),
                firstLine,
                runtime,
                spec.configured() != null && !spec.configured().isBlank(),
                version,
                spec.nodeOptions(),
                spec.test(),
                noColor != null && !noColor.isEmpty(),
                spec.userEnv(),
                here ? p -> Files.isExecutable(Path.of(p)) : null));
        return new Ready(plan, dir);
    }

    /** A host's Node: the configuration's Interpreter, else the host's {@code node} tool, else its own node. */
    private static NodeRuntimes.NodeRuntime remoteRuntime(Path file, String configured) {
        String command =
                configured != null && !configured.isBlank() ? configured : ExecTargets.remoteTool(file, "node");
        List<String> tokens = command == null || command.isBlank() ? List.of() : ProgramArgs.tokenize(command.strip());
        return new NodeRuntimes.NodeRuntime(
                tokens.isEmpty() ? List.of("node") : tokens,
                configured != null && !configured.isBlank()
                        ? NodeRuntimes.Source.CONFIGURATION
                        : NodeRuntimes.Source.SETTING,
                null);
    }

    /** What {@code runtime --version} prints, or null when it cannot say. */
    private static String version(NodeRuntimes.NodeRuntime runtime, Path dir) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(dir, VERSION_TIMEOUT, runtime.argv(List.of("--version")));
            return r == null ? null : (r.out() + "\n" + r.err()).strip();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
