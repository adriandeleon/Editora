package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

import javafx.application.Platform;

import com.editora.dap.CustomAdapters;
import com.editora.dap.LaunchJson;
import com.editora.perf.BlockingSites;
import org.jspecify.annotations.Nullable;

/**
 * The disk work Debug plans a launch by, off the FX thread (PERF-01, Pauta 6.0.1): the user's and the project's
 * {@code debug-adapters.json}, {@code launch.json} and {@code tasks.json}, a configuration's script, folder and
 * environment file, the build roots of a Go, Rust, C# or C/C++ file, a Kotlin program's plan. {@link DebugCoordinator}
 * asks here and goes on with the answer on the FX thread; a failure comes back as the exception, said there.
 */
final class DebugPlanning {

    private DebugPlanning() {}

    /**
     * Where a plan is read: a virtual thread named for it. TEST SEAM — a test may plan in place
     * ({@code (name, task) -> task.run()}), and then the plan lands at once.
     */
    static java.util.function.BiConsumer<String, Runnable> planner =
            (name, task) -> Thread.ofVirtual().name(name).start(task);

    /** Runs {@code work} on a thread called {@code name}, then {@code then} with its answer (or failure) on FX. */
    static <T> void offFx(String name, Callable<T> work, Consumer<Answer<T>> then) {
        planner.accept(name, () -> {
            BlockingSites.enter("DebugPlanning " + name);
            Answer<T> answer;
            try {
                answer = new Answer<>(work.call(), null);
            } catch (Exception e) {
                answer = new Answer<>(null, e);
            }
            Answer<T> done = answer;
            if (Platform.isFxApplicationThread()) {
                then.accept(done);
            } else {
                Platform.runLater(() -> then.accept(done));
            }
        });
    }

    /** What a plan read: its value, or why it could not be read. */
    record Answer<T>(@Nullable T value, @Nullable Exception failure) {

        /** The failure's message, for the status line. */
        String why() {
            Exception f = failure;
            return f == null ? "" : String.valueOf(f.getMessage());
        }
    }

    // --- debug-adapters.json ----------------------------------------------------------------------------------

    /** The adapters of the files that exist among {@code paths}, in order, and what was wrong with them. */
    record Adapters(List<List<CustomAdapters.Adapter>> files, List<String> problems) {}

    static Adapters readAdapters(List<Path> paths) {
        List<List<CustomAdapters.Adapter>> files = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Path p : paths) {
            if (!Files.isRegularFile(p)) {
                continue;
            }
            try {
                var parsed = CustomAdapters.parse(Files.readString(p));
                files.add(parsed.adapters());
                parsed.problems().forEach(problem -> problems.add(p.getFileName() + ": " + problem));
            } catch (IOException e) {
                problems.add(p + ": " + e.getMessage());
            }
        }
        return new Adapters(files, problems);
    }

    /** Writes the example {@code debug-adapters.json} when there is none yet. */
    static Path ensureAdaptersFile(Path file) throws IOException {
        if (!Files.exists(file)) {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(file, CustomAdapters.EXAMPLE);
        }
        return file;
    }

    // --- launch.json and tasks.json -------------------------------------------------------------------------

    /**
     * The launch.json for {@code file}'s project, read and parsed; {@code launch} null with {@code where} null when
     * there is none, with {@code where} set when it could not be read ({@code error} says why).
     */
    record LaunchRead(
            DebugCoordinator.@Nullable LaunchFile launch,
            @Nullable Path where,
            @Nullable String error) {}

    static LaunchRead readLaunchJson(@Nullable Path project, @Nullable Path file) {
        Path launchJson = LaunchJson.find(project, file);
        if (launchJson == null) {
            return new LaunchRead(null, null, null);
        }
        LaunchJson.File parsed;
        try {
            parsed = LaunchJson.parse(Files.readString(launchJson));
        } catch (IOException | LaunchJson.LaunchJsonException e) {
            return new LaunchRead(null, launchJson, String.valueOf(e.getMessage()));
        }
        Path vscode = launchJson.toAbsolutePath().getParent();
        Path workspace = vscode == null || vscode.getParent() == null ? launchJson : vscode.getParent();
        return new LaunchRead(new DebugCoordinator.LaunchFile(launchJson, workspace, parsed), launchJson, null);
    }

    /** The {@code tasks.json} beside {@code launchJson}, read and parsed; null when it cannot be read. */
    static LaunchJson.@Nullable File readTasks(Path launchJson) throws LaunchJson.LaunchJsonException {
        try {
            return LaunchJson.parse(Files.readString(launchJson.resolveSibling("tasks.json")));
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code file}'s text when it is at most 2 MB, else (or unreadable) empty. */
    static String readQuietly(Path file) {
        try {
            return Files.size(file) > 2_000_000 ? "" : Files.readString(file);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    /**
     * Reads the launch.json for {@code file}'s project off the FX thread, then {@code then} with it on the FX thread;
     * null after saying why it cannot be used (that there is none only when {@code sayNone}).
     */
    static void readLaunchJson(
            CoordinatorHost host,
            @Nullable Path project,
            @Nullable Path file,
            boolean sayNone,
            Consumer<DebugCoordinator.@Nullable LaunchFile> then) {
        offFx("launch-json-read", () -> readLaunchJson(project, file), answer -> {
            LaunchRead read = answer.value();
            if (read == null || read.where() == null) {
                if (sayNone) {
                    host.setStatus(tr("status.debug.noLaunchJson"));
                }
                then.accept(null);
                return;
            }
            if (read.launch() == null) {
                host.setStatus(tr("status.debug.launchJsonInvalid", read.where(), read.error()));
            }
            then.accept(read.launch());
        });
    }

    /**
     * The command line of {@code tasks.json}'s task {@code label}, to {@code then} on the FX thread; nothing when it
     * cannot be run here (said). The file is read off the FX thread; its variables are put in on it.
     */
    static void preLaunchTask(
            CoordinatorHost host,
            Path launchJson,
            String label,
            java.util.function.Supplier<LaunchJson.Context> context,
            java.util.Map<String, String> answers,
            Consumer<List<String>> then) {
        offFx("tasks-json-read", () -> readTasks(launchJson), read -> {
            LaunchJson.File tasks = read.value();
            if (read.failure() instanceof LaunchJson.LaunchJsonException e) {
                host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
                return;
            }
            java.util.Map<String, Object> task = tasks == null ? null : LaunchJson.task(tasks, label);
            if (tasks == null || task == null) {
                host.setStatus(tr("status.debug.taskMissing", label));
                return;
            }
            try {
                Object resolved = LaunchJson.substitute(
                        LaunchJson.forPlatform(task, LaunchJson.osKey(com.editora.Os.current())),
                        context.get(),
                        tasks.inputs(),
                        new java.util.HashMap<>(answers));
                @SuppressWarnings("unchecked")
                java.util.Map<String, Object> t = (java.util.Map<String, Object>) resolved;
                List<String> argv = t == null ? null : LaunchJson.taskArgv(t, com.editora.Os.isWindows());
                if (argv == null) {
                    host.setStatus(tr("status.debug.taskUnsupported", label));
                } else {
                    then.accept(argv);
                }
            } catch (LaunchJson.LaunchJsonException e) {
                host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
            } catch (LaunchJson.Cancelled e) {
                // asked and cancelled: nothing runs
            }
        });
    }

    private static String tr(String key, @Nullable Object... args) {
        return com.editora.i18n.Messages.tr(key, args);
    }

    // --- a configuration's files ----------------------------------------------------------------------------

    /**
     * What a configuration's Debug read: its folder and script (null when it names none), whether the script and
     * (when asked) its own folder are missing, its environment with any {@code @file} loaded (or the error), the
     * script's text (when asked), and the trust gate a JVM script's project asks (when asked).
     */
    record ConfigRead(
            @Nullable Path cwd,
            @Nullable Path script,
            boolean scriptMissing,
            boolean dirMissing,
            java.util.Map<String, String> env,
            @Nullable RuntimeException envError,
            String text,
            com.editora.run.RunTrust.@Nullable Gate gate) {}

    static ConfigRead readConfig(
            com.editora.config.RunConfiguration cfg, @Nullable Path root, boolean readText, boolean jvmGate) {
        Path cwd = com.editora.run.RunConfigWorkingDirectory.resolve(cfg, root);
        Path script = cwd == null ? null : com.editora.run.RunConfigWorkingDirectory.script(cfg, root, cwd);
        if (cwd == null || script == null || !com.editora.process.ExecTargets.runsHere(script)) {
            return new ConfigRead(cwd, script, false, false, java.util.Map.of(), null, "", null);
        }
        boolean scriptMissing = !Files.isRegularFile(script);
        boolean dirMissing = !cfg.workingDir().isBlank() && !Files.isDirectory(cwd);
        java.util.Map<String, String> env = java.util.Map.of();
        RuntimeException envError = null;
        try {
            env = com.editora.run.EnvVars.resolve(cfg.env(), RunCoordinator.inheritedVariables(false), cwd);
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException | com.editora.run.EnvVars.EnvFileException e) {
            envError = e;
        }
        String text = readText && !scriptMissing ? readQuietly(script) : "";
        com.editora.run.RunTrust.Gate gate = null;
        if (jvmGate && !scriptMissing) {
            // A Clojure file's project (project.clj, deps.edn, bb.edn) or a Scala CLI project folder is loaded with
            // it: that tool's workspace-trust gate, as Run asks it.
            gate = com.editora.run.ScriptRunCommand.SCALA.equals(cfg.type())
                    ? com.editora.run.RunTrust.scalaCli(
                            com.editora.run.ScalaRun.ownerOf(script, null, Files::isRegularFile))
                    : com.editora.run.JvmScriptRun.trustGate(
                            cfg.type(), script, cfg.workingDir().isBlank() ? null : cwd);
        }
        return new ConfigRead(cwd, script, scriptMissing, dirMissing, env, envError, text, gate);
    }

    /** Says what is wrong with {@code read}'s environment and opens the form there; false when nothing is. */
    static boolean envProblem(
            CoordinatorHost host,
            Consumer<String> edit,
            com.editora.config.RunConfiguration cfg,
            java.util.Map<String, String> env,
            @Nullable RuntimeException envError) {
        if (envError instanceof com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.envUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
        } else if (envError instanceof com.editora.run.EnvVars.EnvFileException e) {
            host.setStatus(
                    tr("status.run.envFileUnreadable", cfg.name(), e.file().toString()));
        } else {
            return false;
        }
        edit.accept(cfg.name());
        return true;
    }

    /** A Kotlin program's compiler and plan, read off the FX thread; {@code plan} null without a compiler. */
    record KotlinRead(@Nullable String kotlinc, com.editora.run.KotlinBuild.@Nullable Plan plan) {}

    static KotlinRead readKotlin(Path source, @Nullable Path configDir) throws IOException {
        String kotlinc = com.editora.run.JvmTools.locate(com.editora.run.KotlinRun.TOOL);
        return new KotlinRead(
                kotlinc, kotlinc == null ? null : com.editora.run.KotlinBuild.plan(source, kotlinc, configDir));
    }

    /** What a Node.js or npm configuration's Debug read: its folder, environment, package manager and script. */
    record NodeRead(
            @Nullable Path cwd,
            java.util.Map<String, String> env,
            @Nullable RuntimeException envError,
            com.editora.build.NpmPackageManager.@Nullable Detection npm,
            @Nullable Path script,
            boolean scriptMissing) {}

    static NodeRead readNode(com.editora.config.RunConfiguration cfg, @Nullable Path root) {
        Path cwd = com.editora.run.RunConfigWorkingDirectory.resolve(cfg, root);
        if (cwd == null || !com.editora.process.ExecTargets.runsHere(cwd)) {
            return new NodeRead(cwd, java.util.Map.of(), null, null, null, false);
        }
        java.util.Map<String, String> env = java.util.Map.of();
        RuntimeException envError = null;
        try {
            env = com.editora.run.EnvVars.resolve(cfg.env(), RunCoordinator.inheritedVariables(false), cwd);
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException | com.editora.run.EnvVars.EnvFileException e) {
            envError = e;
        }
        boolean npm = com.editora.run.ScriptRunCommand.NPM.equals(cfg.type());
        Path script = npm ? null : com.editora.run.RunConfigWorkingDirectory.script(cfg, root, cwd);
        return new NodeRead(
                cwd,
                env,
                envError,
                npm ? com.editora.build.BuildTool.npmManager(cwd, root) : null,
                script,
                !npm && (script == null || !Files.isRegularFile(script)));
    }

    /** What Debug File read before its launch: the project trust gate (null: none). The rest it warmed. */
    record FileRead(com.editora.run.RunTrust.@Nullable Gate gate) {}

    /**
     * Reads the trust gate of {@code path}'s project and looks, so the FX thread then answers from memory, at what
     * Debug File asks next: the Java build root and its build files, the Settings JDK's {@code java}, the
     * inherited {@code JAVA_HOME}'s, and PATH's {@code java}.
     */
    static FileRead warmJavaLaunch(String language, Path path, String text, String settingsJdk) {
        var gate = com.editora.run.RunTrust.forFile(language, path, text, null, Files::isRegularFile);
        if ("java".equals(language) || com.editora.run.JvmScriptRun.supports(language)) {
            Path root = JavaProjectRoot.find(path);
            if (root != null) {
                RunOpsHost.buildFiles(root);
            }
            com.editora.run.JdkToolchain.isGone(settingsJdk);
            com.editora.run.JdkToolchain.inheritedJavaExecutable(System.getenv());
            com.editora.process.ProcessRunner.resolveExecutable(List.of("java"));
        }
        return new FileRead(gate);
    }

    /** A Java configuration's Debug plan: its build root, its project, its plan (one for Run and Debug, review D9). */
    record JavaRead(
            @Nullable Path buildRoot, @Nullable Path root, com.editora.run.JavaLaunchPlan.@Nullable Result planned) {}

    /** Reads it, and looks at what its launch asks next (the build tool, PATH's java) for the FX thread. */
    static JavaRead planJava(
            com.editora.config.RunConfiguration cfg, Path routing, @Nullable Path window, String settingsJdk) {
        Path buildRoot = JavaProjectRoot.find(routing);
        Path root = JavaLaunchPrep.projectOf(routing, buildRoot, window);
        if (root == null) {
            return new JavaRead(buildRoot, null, null);
        }
        if (buildRoot != null) {
            com.editora.run.BuildBeforeLaunch.toolAt(buildRoot);
        }
        com.editora.process.ProcessRunner.resolveExecutable(List.of("java"));
        return new JavaRead(
                buildRoot,
                root,
                com.editora.run.JavaLaunchPlan.forConfiguration(
                        cfg, com.editora.run.JavaLaunchPlan.Inputs.of(root, settingsJdk)));
    }
}
