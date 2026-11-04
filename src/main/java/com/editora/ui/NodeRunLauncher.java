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
import com.editora.run.TsRunRoute;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

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
            @Nullable Path projectRoot,
            String configured,
            String setting,
            List<String> args,
            List<String> nodeOptions,
            boolean test,
            Map<String, String> userEnv,
            @Nullable Path workingDir) {}

    /**
     * A ready launch.
     *
     * @param plan what to run
     * @param dir the folder it runs in
     * @param target the file as the plan names it (the compiled {@code .js} when that runs instead of a
     *     {@code .ts}): where the program starts in {@code plan.argv()}
     * @param notice a line for the console before the program's output (which file runs, a stale build), or null
     * @param route how a TypeScript file runs ({@link TsRunRoute.Kind#PLAIN} for everything else)
     * @param source why this Node.js was chosen ({@code .nvmrc, via nvm}), for the runtime chip; null on a host
     */
    record Ready(
            NodeRunPlan plan,
            @Nullable Path dir,
            String target,
            @Nullable String notice,
            com.editora.run.TsRunRoute.Kind route,
            @Nullable String source) {}

    /** Why {@code node} was chosen, in the user's words ({@code .nvmrc, via nvm}); null when unknown. */
    static @Nullable String source(NodeRuntimes.@Nullable NodeRuntime node) {
        return node == null ? null : com.editora.doctor.NodeRuntimeRow.reason(node);
    }

    /**
     * The console line for a version file that asks for a Node.js nvm does not have, or null: the run goes on
     * with another Node, and says which (review C105).
     */
    static @Nullable String pinNotice(NodeRuntimes.@Nullable NodeRuntime node) {
        if (node == null || node.unmetVersion() == null) {
            return null;
        }
        return tr(
                "run.console.nodePinMissing",
                String.valueOf(node.versionFile()),
                node.unmetVersion(),
                com.editora.doctor.NodeRuntimeRow.reason(node));
    }

    /** {@code version} with "Chosen from: {@code source}" under its banner, for the chip's tooltip. */
    static com.editora.run.@Nullable RuntimeVersion withSource(
            com.editora.run.@Nullable RuntimeVersion version, @Nullable String source) {
        if (version == null || source == null || source.isBlank()) {
            return version;
        }
        String detail = version.detail().isBlank() ? "" : version.detail() + "\n";
        return new com.editora.run.RuntimeVersion(version.label(), detail + tr("run.runtimeSource", source));
    }

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
                : here
                        ? NodeRuntimes.workingDirectory(file, spec.projectRoot())
                        : hostPackageRoot(file, spec.projectRoot());
        String name = file.getFileName() == null ? "" : file.getFileName().toString();
        String firstLine = RunCoordinator.firstLine(file);
        String version = null;
        Path runFile = file;
        String notice = null;
        List<String> loaderFlags = List.of();
        TsRunRoute.Kind route = TsRunRoute.Kind.PLAIN;
        boolean typeScript = NodeRunPlan.isTypeScript(name);
        if (typeScript) {
            version = version(runtime, dir);
            if (here && (firstLine == null || !firstLine.startsWith("#!"))) {
                // The project's tsx, else the compiled file, else plain Node (review C102).
                TsRunRoute chosen = TsRunRoute.choose(
                        file,
                        spec.projectRoot(),
                        Files::isRegularFile,
                        NodeRunLauncher::readSmall,
                        NodeRunLauncher::modified);
                route = chosen.kind();
                if (route == TsRunRoute.Kind.TSX) {
                    loaderFlags = TsRunRoute.tsxFlags(version);
                } else if (route == TsRunRoute.Kind.BUILT) {
                    runFile = chosen.target();
                    name = String.valueOf(runFile.getFileName());
                    notice = tr(
                            chosen.stale() ? "run.console.builtFileStale" : "run.console.builtFile",
                            relative(dir, runFile),
                            relative(dir, file));
                }
            }
        }
        String noColor = here ? System.getenv("NO_COLOR") : null;
        String target = ExecTargets.forPath(runFile).toTargetPath(runFile);
        NodeRunPlan plan = NodeRunPlan.plan(new NodeRunPlan.Request(
                target,
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
                here ? p -> Files.isExecutable(Path.of(p)) : null,
                loaderFlags));
        String pin = here ? pinNotice(runtime) : null;
        if (pin != null) {
            notice = notice == null ? pin : pin + "\n" + notice;
        }
        return new Ready(plan, dir, target, notice, route, here ? source(runtime) : null);
    }

    /**
     * On a remote host, WSL or a container: the package root as there, read through the file's own file system
     * (a few file checks, off the FX thread), so a program runs from the same folder as it does here (review
     * C109); the file's folder when there is none or the host cannot be asked.
     */
    static @Nullable Path hostPackageRoot(Path file, @Nullable Path projectRoot) {
        try {
            Path root = projectRoot != null && projectRoot.getFileSystem() == file.getFileSystem() ? projectRoot : null;
            return NodeRuntimes.packageRoot(file, root, null, Files::isRegularFile)
                    .orElse(file.getParent());
        } catch (RuntimeException e) {
            return file.getParent();
        }
    }

    /** {@code file} relative to {@code dir} when below it, else the whole path ('/'-separated for the console). */
    static String relative(@Nullable Path dir, Path file) {
        try {
            Path rel = dir != null && file.startsWith(dir) ? dir.relativize(file) : file;
            return rel.toString().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    private static java.util.Optional<String> readSmall(Path p) {
        try {
            return Files.size(p) > 256 * 1024 ? java.util.Optional.empty() : java.util.Optional.of(Files.readString(p));
        } catch (java.io.IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
    }

    /** A host's Node: the configuration's Interpreter, else the host's {@code node} tool, else its own node. */
    private static NodeRuntimes.NodeRuntime remoteRuntime(@Nullable Path file, String configured) {
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
    private static @Nullable String version(NodeRuntimes.NodeRuntime runtime, @Nullable Path dir) {
        try {
            ProcessRunner.Result r = ProcessRunner.run(dir, VERSION_TIMEOUT, runtime.argv(List.of("--version")));
            return r == null ? null : (r.out() + "\n" + r.err()).strip();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
