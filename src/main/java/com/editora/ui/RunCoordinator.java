package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.RunConfiguration;
import com.editora.editor.EditorBuffer;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.JavaRunCommand;
import com.editora.run.JdkToolchain;
import com.editora.run.ProgramArgs;
import com.editora.run.ReadPromptShim;
import com.editora.run.RunConfigRouting;
import com.editora.run.RunConfigWorkingDirectory;
import com.editora.run.RunService;
import com.editora.run.ScriptRunCommand;
import com.editora.run.ShellCommand;
import com.editora.run.StackTraceLinks;

import static com.editora.i18n.Messages.tr;

/**
 * Run-a-file feature (the gutter ▶ / "Run File" flow + the Run tool-window console), extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link RunService} + the
 * {@link RunPanel}; {@code MainController} keeps the {@code ToolWindow} (built with {@link #panel()}),
 * the local-file run gate, and the shared stack-trace link resolver
 * ({@code openRunLink}, also used by the Debug + External-Tool consoles) — which reuses {@link #lastRunDir()}.
 */
final class RunCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (Run tool window, buffer save, program args, link jump). */
    interface Ops {
        void openToolWindow();

        /**
         * Runs {@code command} in a Terminal tab titled {@code title} instead of the Run console (a configuration's
         * Run in a Terminal tab); {@code onExit} gets its exit code, -1 when there was none.
         */
        default com.editora.terminal.ProgramRun runInTerminal(
                String title,
                Path dir,
                List<String> command,
                java.util.Map<String, String> env,
                java.util.function.IntConsumer onExit)
                throws java.io.IOException {
            throw new java.io.IOException("no terminal");
        }

        /**
         * A program started or ended, so anything reading {@link RunCoordinator#isRunning()} must re-read it —
         * today the toolbar's Stop button, which is enabled only while something is running.
         *
         * <p>Fired from the one place a run is launched and from the listener that ends it, rather than left
         * to whatever else happens to refresh the toolbar: with no such call the Stop button only ever
         * re-evaluated when the configuration list was rebuilt or its selection changed, so it sat disabled
         * through the entire run it exists to interrupt.
         */
        void onRunStateChanged();

        /**
         * Opens the Run Configurations page on {@code name}, so a configuration that cannot run takes you to
         * where you fix it rather than only saying what is wrong.
         */
        void editConfiguration(String name);

        /** Saves {@code buffer} (dirty → write, untitled → Save-As); {@code false} if cancelled/failed. */
        boolean saveBuffer(EditorBuffer buffer);

        /** The remembered program-arguments string for {@code path} ("" when none). */
        String programArgs(Path path);

        /** Persists the program-arguments string for {@code path} (workspace state + durable save). */
        void setProgramArgs(Path path, String args);

        /** A stack-trace location double-clicked in the console: resolve + jump (shared resolver). */
        void openLink(StackTraceLinks.Link link);

        /** An HTTP(S) URL single-clicked in program output: open it in the system browser. */
        default void openUrl(String url) {}

        /** The nearest Maven/Gradle project root above {@code file}, or {@code null} if there is none. */
        Path javaProjectRoot(Path file);

        /** This window's open project root, or {@code null} when it has no project. */
        Path projectRoot();

        /** Runs the tests of a Python test file instead of the file as a script; false when it has none. */
        default boolean runPythonTestFile(Path file, String text) {
            return false;
        }

        /** Runs a Node.js test file's tests ({@code node --test}) into Test Results; false when it cannot. */
        default boolean runNodeTestFile(Path file) {
            return false;
        }

        /** The Python interpreter picked for this window's project ("" = detect it). */
        default String pythonInterpreterChoice() {
            return "";
        }

        /** Whether a project main class can be resolved+run (jdtls + the java-debug bundle available today). */
        boolean javaLaunchAvailable();

        /** This window's saved run configurations (empty when none). */
        List<RunConfiguration> runConfigurations();

        /** The configuration name selected in the toolbar, or blank. */
        String selectedRunConfigName();

        /** Enumerates {@code routingFile}'s project main classes (empty if none/unavailable). FX thread. */
        void resolveJavaMainClasses(Path routingFile, Consumer<List<JavaMainClass>> cb);

        /** Resolves a chosen main class's classpath + java executable ({@link JavaLaunchInfo#error()} on
         *  failure). FX thread. */
        void resolveJavaLaunch(Path routingFile, JavaMainClass mainClass, Consumer<JavaLaunchInfo> cb);

        /** Whether {@code root} holds a Maven build file ({@code pom.xml}). */
        boolean mavenProjectAt(Path root);

        /** Whether {@code root} holds a Gradle build file ({@code build.gradle[.kts]}). */
        boolean gradleProjectAt(Path root);

        /** Resolves the Maven module's launch classpath off-thread ({@code mvn compile
         *  dependency:build-classpath} + {@code target/classes}); delivers null/empty on failure. FX thread. */
        void resolveMavenClasspath(Path root, Consumer<List<String>> cb);

        /** Same resolution under a selected JDK. The default preserves test/alternate hosts. */
        default void resolveMavenClasspath(Path root, String jdkHome, Consumer<List<String>> cb) {
            resolveMavenClasspath(root, cb);
        }

        /** Runs the Gradle Run task via the build tool (streams to Output) — the no-jdtls Gradle
         *  fallback. Runs {@code bootRun} for a Spring Boot project, else {@code run}; {@code root} locates the
         *  build script. */
        void runGradleRunTask(Path root);

        /**
         * Runs every request of a {@code .http}/{@code .rest} buffer in the HTTP Client — what "Run File" means
         * for a request file. Returns {@code false} when the window has no HTTP Client to hand it to.
         */
        default boolean runHttpFile(EditorBuffer buffer) {
            return false;
        }

        /**
         * Brings the deployment {@code name} up to date (see {@link DeploymentCoordinator#deployThen}), then hands
         * its copy to {@code then}. A window without deployments says so and ends {@code console}.
         */
        default void deployThen(
                String name, DeploymentCoordinator.Console console, Consumer<DeploymentCoordinator.Deployed> then) {
            console.ended(false, null);
        }

        /** Stops a running deploy of this window. */
        default void cancelDeploy() {}

        /**
         * Attaches the Java debugger to a JVM waiting on this machine's {@code port} (a tunnel to a deployed
         * program), anchored at the open Java file {@code anchor}; {@code stopOwner} ends the program.
         */
        default void attachJavaDebugger(Path anchor, int port, Runnable stopOwner) {}
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final RunService service = new RunService();
    private final BeforeLaunchStep beforeLaunch = new BeforeLaunchStep(service, true); // the console can answer it
    /** The Run console, built on its first use (a run, or the tool window's first open). */
    private RunPanel panel;
    /** The console font to apply when the panel is built (set by every settings apply, panel or not). */
    private String outputFontFamily;

    private int outputFontSize;

    /**
     * The most recent launch, for {@code run.rerun}. Assigned only by {@link #streamRun}, all four together:
     * the before-launch console once set {@code lastRunDir} on its own, and Rerun then replayed the previous
     * program in the before-launch step's directory.
     */
    private Path lastRunDir;

    /** Where the console's current output came from — a run or a before-launch step — for its file links. */
    private Path consoleDir;

    private java.util.Map<String, String> lastRunEnv = java.util.Map.of();
    private String lastRunLabel;
    private List<String> lastRunCommand;
    /** The script the last run ran (a file, or a script configuration's target); null for a Java launch. */
    private Path lastRunScript;
    /** Stop was pressed for the running program, so its exit is reported as stopped, not as an exit code. */
    private boolean stopRequested;

    /**
     * The project root a configuration's launch resolves against while it runs on a deployment: the
     * deployment's folder on its host. Null for every launch on this machine (each one sets it).
     */
    private Path launchRoot;

    /** The deployment the current configuration launch runs on, or null. */
    private DeploymentCoordinator.Deployed deployed;

    /** A deploy is running before a launch: Stop ends it, and a second Run waits. */
    private boolean deploying;

    /** What the deploy before this launch did: the first line of its console, once. */
    private String pendingDeploySummary;

    /** Watches the next program's output (a deployed program's JDWP banner); null for most runs. */
    private java.util.function.BiConsumer<String, Boolean> pendingOutputWatcher;

    /**
     * What Rerun repeats for the next program, null for most: a deployed configuration (deploy, then run), a Kotlin
     * program (compile an edited file again).
     */
    private Runnable pendingRerun;

    /** {@link #pendingRerun} of the last program. */
    private Runnable lastConfigRerun;

    /** The tunnel to a deployed program's debug port, closed when the program ends. */
    private com.editora.process.PortForward debugTunnel;

    /** What a test run streamed in this console reports to: its output lines, then its exit. */
    interface TestStream {
        void line(String text, boolean stderr);

        /** The run ended: its exit code, or {@code -1} when it was stopped or could not start. */
        void exit(int code);
    }

    /** The hooks the next {@link #streamRun} hands its output to (set by {@link #runTests}). */
    private TestStream pendingTestStream;

    /** How Rerun repeats the last run when it was a test run (which feeds Test Results too); else null. */
    private Runnable lastTestRerun;

    RunCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    /** Follows the panel's run state once the panel exists (built on the first run, or the window's first open). */
    private final javafx.beans.property.ReadOnlyBooleanWrapper running =
            new javafx.beans.property.ReadOnlyBooleanWrapper(this, "running");

    /** True while the Run console shows a live program or before-launch step — the tool window's busy badge. */
    javafx.beans.property.ReadOnlyBooleanProperty runningProperty() {
        return running.getReadOnlyProperty();
    }

    RunPanel panel() {
        if (panel == null) {
            panel = new RunPanel(this::stopRun);
            running.bind(panel.runningProperty());
            panel.setOnInput(service::sendInput); // stdin field → the running process
            panel.setOnEndInput(service::closeInput); // End Input / Ctrl+D → end of file for the process
            panel.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
            panel.setOnUrl(ops::openUrl); // single-clicked HTTP(S) URL → browser
            if (outputFontFamily != null) {
                panel.setOutputFont(outputFontFamily, outputFontSize);
            }
        }
        return panel;
    }

    /** Matches the console font to the editor font; remembered for a panel not built yet. */
    void setOutputFont(String family, int size) {
        outputFontFamily = family;
        outputFontSize = size;
        if (panel != null) {
            panel.setOutputFont(family, size);
        }
    }

    /** Working directory of the most recent run, or {@code null} — used by the shared link resolver. */
    Path lastRunDir() {
        return consoleDir;
    }

    void runActiveFile() {
        runActiveFile(false, false);
    }

    /** Prompts for program arguments (pre-filled with the file's remembered ones), then runs. */
    void runActiveFileWithArgs() {
        runActiveFile(true, false);
    }

    /**
     * {@code run.trace}: runs the active shell script with {@code -x} and a trace prompt that names each
     * command's file and line ({@link ShellCommand#TRACE_PROMPT}), so every traced line in the console links
     * to its source — the cheap first step towards debugging a script.
     */
    void runActiveFileWithTrace() {
        runActiveFile(false, true);
    }

    /** Re-runs the most recent run (same file + argv) without touching the active tab. */
    void rerunLast() {
        if (lastRunCommand == null || lastRunDir == null) {
            host.setStatus(tr("status.run.noRerun"));
            return;
        }
        // Save the script being rerun, wherever its tab is (the tab in front may be something else, a README, and
        // saving only that let the edited script run as it was on disk), then the tab in front as before.
        if (!beginRunRequest() || !saveScriptAndActive(lastRunScript)) {
            return;
        }
        if (lastTestRerun != null) {
            lastTestRerun.run();
            return;
        }
        if (lastConfigRerun != null) {
            lastConfigRerun.run();
            return;
        }
        streamRun(lastRunLabel, lastRunDir, lastRunCommand, lastRunEnv, lastRunScript);
    }

    /**
     * Runs a test command (pytest, unittest) in this console: its lines and exit also go to {@code hooks}, and
     * Rerun repeats it through {@code rerun}. False, having said why, when another program is running or a
     * save was refused.
     */
    boolean runTests(
            String label,
            Path dir,
            List<String> argv,
            java.util.Map<String, String> env,
            Path script,
            TestStream hooks,
            Runnable rerun) {
        if (!beginRunRequest() || !saveScriptAndActive(script)) {
            return false;
        }
        pendingTestStream = hooks;
        streamRun(label, dir, argv, env, script);
        lastTestRerun = rerun;
        return true;
    }

    /**
     * {@code run.mainClass}: run a main class in the active file's Maven/Gradle project in the Run console.
     * Requires a Java file open in the project (to route jdtls). Resolves every project main class, lets the
     * user pick one (when several), builds a {@code java -cp …} argv and runs it with the project root as the
     * working directory.
     */
    void runMainClass() {
        startMainClassRun(null);
    }

    /** Runs a specific main class by fully-qualified name (the gutter ▶ on a {@code main} method). */
    void runMainClassNamed(String fqn) {
        startMainClassRun(fqn);
    }

    /**
     * The file a saved configuration should be resolved against — see {@link RunConfigRouting}.
     *
     * <p>Shared with {@link DebugCoordinator}, because a configuration's {@code kind} decides which of the two
     * launches it, and both need to agree on which project it belongs to.
     *
     * @return null when no Java file is open anywhere, the one case neither can resolve
     */
    static Path routingFor(CoordinatorHost host, RunConfiguration cfg) {
        List<Path> open = new java.util.ArrayList<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null && RemoteExec.runsFor(b.getPath()) && "java".equals(b.getLanguage())) {
                open.add(b.getPath());
            }
        });
        EditorBuffer active = host.activeBuffer();
        Path activeJava = active != null
                        && active.getPath() != null
                        && RemoteExec.runsFor(active.getPath())
                        && "java".equals(active.getLanguage())
                ? active.getPath()
                : null;
        return RunConfigRouting.pick(open, activeJava, cfg.workingDir(), host::isLspManaged);
    }

    /**
     * Runs a non-Java configuration — a Python or shell script, a make target, or an NPM script.
     *
     * <p>Needs no language server and no open Java file (see {@link ScriptRunCommand}). The working directory
     * is the configuration's own when set, else the open project root. A standalone file-backed script can
     * still fall back to its target's folder; build-tool targets cannot because their targets are names.
     */
    private void runScriptConfig(RunConfiguration cfg) {
        List<String> args = programArgs(cfg.args(), cfg);
        if (args == null) {
            return;
        }
        if (ScriptRunCommand.build(cfg.type(), cfg.target(), args).isEmpty()) {
            if (ScriptRunCommand.needsTarget(cfg.type())) {
                reportIncomplete(cfg, "status.run.configNeedsTarget");
            } else {
                // An unknown type is not something the form can put right by being opened at it.
                host.setStatus(tr("status.run.configBadType", cfg.name()));
            }
            return;
        }
        Path cwd = RunConfigWorkingDirectory.resolve(cfg, configRoot());
        if (cwd == null) {
            reportIncomplete(cfg, "status.run.configNeedsWorkingDir");
            return;
        }
        // Two questions: whether reading the folder is a network round trip (an SFTP folder), and whether the
        // program runs elsewhere (a remote host, a container, WSL) — where this machine's environment and
        // executables mean nothing.
        boolean remote = com.editora.vfs.Vfs.isRemote(cwd);
        boolean elsewhere = !com.editora.process.ExecTargets.runsHere(cwd);
        Path script = RunConfigWorkingDirectory.script(cfg, configRoot(), cwd);
        if (!remote) {
            // Checked here, so the message names what is missing: a missing directory used to surface as
            // "Cannot run program /usr/bin/bash … No such file or directory", blaming bash.
            if (!java.nio.file.Files.isDirectory(cwd)) {
                host.setStatus(tr("status.run.workingDirMissing", cfg.name(), cwd.toString()));
                ops.editConfiguration(cfg.name());
                return;
            }
            if (script != null && !java.nio.file.Files.isRegularFile(script)) {
                host.setStatus(tr("status.run.scriptMissing", cfg.name(), script.toString()));
                ops.editConfiguration(cfg.name());
                return;
            }
        }
        java.util.Map<String, String> env;
        try {
            env = com.editora.run.EnvVars.resolve(cfg.env(), inheritedVariables(elsewhere));
        } catch (ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.envUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (ScriptRunCommand.isPython(cfg.type())) {
            runPythonConfig(cfg, cwd, script, args, env, remote);
            return;
        }
        if (ScriptRunCommand.isJvmScript(cfg.type())) {
            runJvmScriptConfig(cfg, cwd, script, args, env, elsewhere);
            return;
        }
        if (ScriptRunCommand.NODE.equals(cfg.type())) {
            List<String> nodeOptions;
            try {
                nodeOptions = ProgramArgs.parse(cfg.interpreterOptions());
            } catch (ProgramArgs.UnclosedQuoteException e) {
                host.setStatus(tr("status.run.optionsUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
                ops.editConfiguration(cfg.name());
                return;
            }
            NodeRunLauncher.plan(
                    new NodeRunLauncher.Spec(
                            script,
                            ops.projectRoot(),
                            cfg.interpreter(),
                            host.settings().getNodePath(),
                            args,
                            nodeOptions,
                            false,
                            env,
                            cfg.workingDir().isBlank() ? null : cwd),
                    ready -> streamNode(cfg.name(), ready, script));
            return;
        }
        if (ScriptRunCommand.NPM.equals(cfg.type())) {
            // The project's own package manager (pnpm-lock.yaml → pnpm …), as the npm tool window runs it, and the
            // npm command setting when it is for that manager.
            com.editora.build.NpmPackageManager.Detection detected =
                    com.editora.build.BuildTool.npmManager(cwd, ops.projectRoot());
            String override = host.settings().getNpmCommand();
            List<String> manager = com.editora.build.BuildExecutable.resolve(
                    List.of(),
                    com.editora.build.NpmPackageManager.overrideApplies(override, detected.manager()) ? override : "",
                    detected.manager());
            launchReason = detected.reason(); // "pnpm-lock.yaml": what a not-found message names
            java.util.Map<String, String> npmEnv = new java.util.LinkedHashMap<>(
                    NpmRunSupport.environment(elsewhere ? null : System.getenv("NO_COLOR")));
            npmEnv.putAll(env);
            streamRun(cfg.name(), cwd, ScriptRunCommand.npm(manager, cfg.target(), args), npmEnv, script);
            return;
        }
        if (!ScriptRunCommand.SHELL.equals(cfg.type())) {
            streamRun(cfg.name(), cwd, ScriptRunCommand.build(cfg.type(), cfg.target(), args), env, script);
            return;
        }
        // A shell script runs under what its shebang asks for, unless the configuration names an interpreter.
        // Reading the first line of a file on a remote host is a network round trip, so that one is off FX.
        java.util.function.Consumer<String> launch = firstLine -> {
            ShellCommand.Launch shell;
            try {
                shell = ShellCommand.launch(
                        firstLine, cfg.target(), cfg.interpreter(), cfg.interpreterOptions(), existsHere(elsewhere));
            } catch (ProgramArgs.UnclosedQuoteException e) {
                host.setStatus(tr("status.run.optionsUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
                ops.editConfiguration(cfg.name());
                return;
            }
            Path dir = shebangDir(shell, cwd, remote); // a file check: not over the network
            if (dir == null) {
                return;
            }
            streamRun(
                    cfg.name(),
                    dir,
                    ScriptRunCommand.build(
                            cfg.type(),
                            // As written when it names the script from where the interpreter starts; else the
                            // script as its machine names it (a target is relative to the project root, C9).
                            cwd.equals(dir)
                                            && cwd.resolve(cfg.target().strip())
                                                    .normalize()
                                                    .equals(script)
                                    ? cfg.target()
                                    : com.editora.process.ExecTargets.forPath(script)
                                            .toTargetPath(script),
                            args,
                            shell),
                    shellEnvironment(shell, env, elsewhere), // this machine's variables only when it runs here
                    script);
        };
        if (!remote) {
            launch.accept(firstLine(script));
            return;
        }
        Thread.ofVirtual().name("run-shebang").start(() -> {
            String line = firstLine(script);
            javafx.application.Platform.runLater(() -> launch.accept(line));
        });
    }

    /**
     * A Python script or module configuration: the interpreter is planned like Run File's (its Interpreter and
     * Interpreter options, else the project's), with the configuration's variables on top.
     */
    private void runPythonConfig(
            RunConfiguration cfg,
            Path cwd,
            Path script,
            List<String> args,
            java.util.Map<String, String> env,
            boolean remote) {
        List<String> options;
        try {
            options = ProgramArgs.parse(cfg.interpreterOptions());
        } catch (ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.optionsUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
            ops.editConfiguration(cfg.name());
            return;
        }
        boolean module = ScriptRunCommand.PYTHON_MODULE.equals(cfg.type());
        String target = module
                ? cfg.target().strip()
                : com.editora.process.ExecTargets.forPath(script).toTargetPath(script);
        com.editora.run.PythonRunPlan.Request request = new com.editora.run.PythonRunPlan.Request(
                module ? null : script,
                configRoot(),
                target,
                module,
                args,
                module || remote ? null : firstLine(script),
                cfg.interpreter(),
                options,
                ops.pythonInterpreterChoice(),
                PythonRunLauncher.setting(cwd, host.settings()),
                env);
        PythonRunLauncher.plan(request, cwd, plan -> streamPython(cfg.name(), cwd, plan, script));
    }

    /** The first line of {@code script} (at most 512 bytes), or null when it cannot be read. */
    static String firstLine(Path script) {
        if (script == null) {
            return null;
        }
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(script)) {
            String head = new String(in.readNBytes(512), java.nio.charset.StandardCharsets.UTF_8);
            int nl = head.indexOf('\n');
            return nl >= 0 ? head.substring(0, nl) : head;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** For a run on this machine, whether an absolute interpreter path exists; null (as written) on a host. */
    private static java.util.function.Predicate<String> existsHere(boolean remote) {
        return remote ? null : p -> java.nio.file.Files.isExecutable(Path.of(p));
    }

    /**
     * Runs a Groovy or Kotlin script configuration: its tool on the configuration's JDK (else the Settings one),
     * with the configuration's VM arguments in {@code JAVA_OPTS} — the variable the launcher hands its JVM. A
     * Kotlin program ({@code .kt}) is compiled and run by {@code java} instead, its VM arguments on that command.
     */
    private void runJvmScriptConfig(
            RunConfiguration cfg,
            Path cwd,
            Path script,
            List<String> args,
            java.util.Map<String, String> env,
            boolean elsewhere) {
        String language = cfg.type();
        String tool = elsewhere
                ? com.editora.run.JvmScriptRun.tool(language)
                : com.editora.run.JvmTools.locate(com.editora.run.JvmScriptRun.tool(language));
        if (tool == null) {
            host.setStatus(tr(com.editora.run.JvmScriptRun.missingToolKey(language)));
            return;
        }
        String jdkHome = !cfg.jdkHome().isBlank()
                ? cfg.jdkHome()
                : elsewhere ? remoteJdkHome(cwd) : host.settings().getMavenJdkHome();
        java.util.Map<String, String> launch = new java.util.LinkedHashMap<>(
                elsewhere ? remoteJdkEnvironment(jdkHome) : JdkToolchain.environment(jdkHome, processPath()));
        launch.putAll(env);
        launch = com.editora.run.JvmScriptRun.withJvmOption(
                launch,
                elsewhere ? java.util.Map.of() : System.getenv(),
                com.editora.run.JvmScriptRun.jvmOptionsVariable(language),
                cfg.vmArgs().strip());
        String target = script != null
                ? com.editora.process.ExecTargets.forPath(script).toTargetPath(script)
                : cfg.target().strip();
        if (com.editora.run.KotlinRun.shape(target) == com.editora.run.KotlinRun.Shape.PROGRAM
                && ScriptRunCommand.KOTLIN.equals(language)) {
            if (elsewhere || script == null) {
                host.setStatus(tr("status.run.kotlinProgramRemote"));
                return;
            }
            java.util.Map<String, String> programEnv =
                    new java.util.LinkedHashMap<>(JdkToolchain.environment(jdkHome, processPath()));
            programEnv.putAll(env);
            String java = JdkToolchain.javaExecutable(jdkHome);
            runKotlinProgram(
                    cfg.name(),
                    script,
                    cwd,
                    java.isBlank() ? "java" : java,
                    ProgramArgs.tokenize(cfg.vmArgs()),
                    args,
                    programEnv,
                    tool);
            return;
        }
        streamRun(cfg.name(), cwd, com.editora.run.JvmScriptRun.argv(language, tool, target, args), launch, script);
    }

    /**
     * Runs Kotlin program {@code source}: compiled by {@code kotlinc} into a jar cached by its content (in the Run
     * console, as a before-launch step the user can watch and stop), then {@code java -jar} — or straight to
     * {@code java -jar} when this content's jar is already there. The runtime chip shows the compiler's Kotlin
     * and the JDK both run on.
     */
    private void runKotlinProgram(
            String label,
            Path source,
            Path cwd,
            String javaExecutable,
            List<String> jvmOptions,
            List<String> args,
            java.util.Map<String, String> env,
            String kotlinc) {
        com.editora.run.KotlinBuild.Plan plan;
        try {
            plan = com.editora.run.KotlinBuild.plan(source, kotlinc, host.configDir());
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.run.failed", e.getMessage()));
            return;
        }
        List<String> command = com.editora.run.KotlinRun.programArgv(
                javaExecutable, jvmOptions, plan.jar().toString(), args);
        Runnable launch = () -> {
            runtimeCommand = List.of(kotlinc);
            // Rerun compiles an edited file again rather than replaying the old jar.
            pendingRerun = () -> runKotlinProgram(label, source, cwd, javaExecutable, jvmOptions, args, env, kotlinc);
            streamRun(label, cwd, command, env, source);
        };
        if (plan.compile().isEmpty()) {
            launch.run();
            return;
        }
        beforeLaunch.run(
                host,
                new BeforeLaunchStep.Command(
                        source.getFileName().toString(), plan.compile(), "status.run.kotlinCompile"),
                cwd,
                env,
                new BeforeLaunchStep.Console() {
                    @Override
                    public void started(String commandLine) {
                        consoleDir = source.getParent(); // kotlinc's error lines name the file: links resolve
                        consoleUsed = true;
                        ops.openToolWindow();
                        panel().started(commandLine);
                        runtimeGeneration++; // the compile is not the program: no runtime chip for it
                        panel().setRuntime(null);
                    }

                    @Override
                    public void output(String line, boolean stderr) {
                        panel().appendOutput(line, stderr);
                    }

                    @Override
                    public void ended(int code, String launchError) {
                        if (launchError != null) {
                            panel().failed(launchError);
                        } else if (code != 0) {
                            panel().finished(code);
                        }
                        ops.onRunStateChanged();
                    }
                },
                () -> {
                    try {
                        com.editora.run.KotlinBuild.finish(plan);
                    } catch (java.io.IOException e) {
                        host.setStatus(tr("status.run.failed", e.getMessage()));
                        return;
                    }
                    launch.run();
                });
        ops.onRunStateChanged(); // the compile is running now: the toolbar's Stop button applies to it
    }

    /**
     * What {@code $NAME} in a configuration's environment expands to: this machine's environment, with the
     * PATH a run actually gets (the augmented one). A remote host's environment is not known here, so there a
     * reference is left as written.
     */
    static java.util.function.Function<String, String> inheritedVariables(boolean remote) {
        return com.editora.run.JavaLaunchPlan.inherited(remote);
    }

    /**
     * Program arguments as typed, read like a shell reads them; null after saying why when a quote is never
     * closed (which used to swallow the rest of the line into one argument). {@code cfg} names the
     * configuration whose field it was, so its form can be opened; null for a file's own arguments.
     */
    private List<String> programArgs(String text, RunConfiguration cfg) {
        try {
            return ProgramArgs.parse(text);
        } catch (ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.argsUnclosedQuote", String.valueOf(e.quote())));
            if (cfg != null) {
                ops.editConfiguration(cfg.name());
            }
            return null;
        }
    }

    /**
     * Reports that {@code cfg} is missing something it needs, and opens its form so it can be filled in.
     *
     * <p>Naming the problem is only half the action: the status line said which configuration was incomplete
     * and left you to go and find it. Pressing Run is a request to run it, so taking you to the field that
     * would make it run is the natural answer — and it is what every IDE does here.
     */
    private void reportIncomplete(RunConfiguration cfg) {
        reportIncomplete(cfg, "status.run.configNeedsMainClass");
    }

    private void reportIncomplete(RunConfiguration cfg, String messageKey) {
        host.setStatus(tr(messageKey, cfg.name()));
        ops.editConfiguration(cfg.name());
    }

    /**
     * Whether a program is currently running, so the toolbar Stop button and the Run stripe can reflect it: the
     * console's, or a configuration's running in a Terminal tab.
     */
    boolean isRunning() {
        return consoleRunning() || terminalProgramRunning() || deploying; // a deploy before a launch counts
    }

    /** Whether the console is busy: a program, or the build before one. */
    private boolean consoleRunning() {
        return service.isRunning() || beforeLaunch.isActive();
    }

    /** The configuration's program running in a Terminal tab, or null; Run's Stop ends it. */
    private com.editora.terminal.ProgramRun terminalProgram;

    /** Set by Run's Stop on {@link #terminalProgram}, so its end says "stopped" whatever its exit code. */
    private boolean terminalStopRequested;

    private boolean terminalProgramRunning() {
        com.editora.terminal.ProgramRun program = terminalProgram;
        return program != null && program.isRunning();
    }

    /** Set by the first launch (or before-launch step) that reaches the console; never cleared. */
    private boolean consoleUsed;

    /** Bumped by each run, so a slow runtime probe cannot label a newer run's console (see showRuntime). */
    private long runtimeGeneration;

    /**
     * Whether the Run console has something to show: a live process, or the output of one that ran in this
     * window. Availability must not fall back to "the active tab is runnable" when a run exits — that closed
     * the console on the very output it was opened for, and an instant-exit program never appeared at all.
     */
    boolean consoleInUse() {
        return consoleUsed || consoleRunning();
    }

    /** The root a configuration resolves against: its deployment's folder on the host, else the project's. */
    private Path configRoot() {
        return launchRoot != null ? launchRoot : ops.projectRoot();
    }

    /** {@code local} (a folder of the project) as the launch sees it: its copy on the host when deployed. */
    private Path onLaunchHost(Path local) {
        if (deployed == null || local == null) {
            return local;
        }
        Path copy = deployed.paths().toHost(local);
        return copy != null ? copy : deployed.remoteRoot();
    }

    /** Runs a saved {@link RunConfiguration}: its main class with its own program/VM args + working dir. */
    void runConfig(RunConfiguration cfg) {
        if (!beginRunRequest()) {
            return;
        }
        if (cfg.isDeployed()) {
            runDeployedConfig(cfg, false);
            return;
        }
        terminalRun = cfg.runInTerminal(); // read where the launch is built (streamRun), after any build
        // Save first, as Debug and the gutter marker do. The save used to sit inside the Java launch, after
        // the before-launch build — which therefore compiled the file as it was on disk, and the program ran
        // without the edit on screen; a script configuration never saved at all.
        Path script = cfg.isJava() ? null : configScript(cfg);
        if (!saveScriptAndActive(script)) {
            return;
        }
        // A before-launch step gates everything after it: if the build fails there is nothing worth running,
        // and launching the previous binary anyway is the failure mode this exists to prevent.
        withBeforeLaunch(cfg, () -> {
            if (!cfg.isJava()) {
                runScriptConfig(cfg);
            } else {
                runJavaConfig(cfg);
            }
        });
    }

    /** A script configuration's script file, resolved as its run will resolve it; null when it has none. */
    private Path configScript(RunConfiguration cfg) {
        Path cwd = RunConfigWorkingDirectory.resolve(cfg, configRoot());
        return cwd == null ? null : RunConfigWorkingDirectory.script(cfg, configRoot(), cwd);
    }

    /**
     * Runs or debugs {@code cfg} on its deployment: every open file of the project is saved, the copy on the
     * host brought up to date, and then the configuration runs there as it would in a remote project — its
     * before-launch step, its working directory, its interpreter or JDK are the host's. Paths in the
     * configuration that name files of the project name their copies.
     */
    void runDeployedConfig(RunConfiguration given, boolean debug) {
        Path localRoot = ops.projectRoot();
        if (localRoot == null) {
            host.setStatus(tr("status.run.configsNeedProjectWindow"));
            return;
        }
        if (debug && !given.isJava()) {
            host.setStatus(tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type." + given.type())));
            return;
        }
        if (!saveProjectBuffers(localRoot)) {
            return; // a deploy sends what is on disk: an unsaved edit would not reach the host
        }
        com.editora.deploy.DeployPaths mapping = new com.editora.deploy.DeployPaths(localRoot, localRoot);
        RunConfiguration cfg = new RunConfiguration(
                given.name(),
                given.type(),
                mapping.hostField(given.target()),
                given.mainClass(),
                given.projectName(),
                given.args(),
                given.vmArgs(),
                mapping.hostField(given.workingDir()),
                given.env(),
                given.beforeLaunch(),
                "", // a JDK of this machine means nothing on the host: its jdk.home tool, else its java
                given.interpreter(),
                given.interpreterOptions(),
                given.runInTerminal(),
                given.deployment());
        deploying = true;
        ops.onRunStateChanged();
        ops.deployThen(cfg.deployment(), deployConsole(), d -> {
            pendingRerun = () -> runDeployedConfig(given, debug); // Rerun deploys again: the copy may be stale
            deployed = d;
            launchRoot = d.remoteRoot();
            pendingDeploySummary = d.summary();
            terminalRun = cfg.runInTerminal();
            withBeforeLaunch(cfg, () -> {
                if (cfg.isJava()) {
                    runDeployedJava(cfg, debug);
                } else {
                    runScriptConfig(cfg);
                }
            });
        });
    }

    /** Debugs {@code cfg} on its deployment (Debug's entry): refused while something already runs, as Run is. */
    void debugDeployedConfig(RunConfiguration cfg) {
        if (beginRunRequest()) {
            runDeployedConfig(cfg, true);
        }
    }

    /** Saves every open, edited file of the project at {@code root}; false when a save was refused. */
    private boolean saveProjectBuffers(Path root) {
        List<EditorBuffer> dirty = new java.util.ArrayList<>();
        host.forEachBuffer(b -> {
            if (b.isDirty()
                    && b.getPath() != null
                    && com.editora.deploy.DeployPaths.relative(root, b.getPath()) != null) {
                dirty.add(b);
            }
        });
        for (EditorBuffer b : dirty) {
            if (!ops.saveBuffer(b)) {
                return false;
            }
        }
        return true;
    }

    /** The Run console as a deploy's: its progress while it runs, its failure if it fails. */
    private DeploymentCoordinator.Console deployConsole() {
        return new DeploymentCoordinator.Console() {
            @Override
            public void started(String title) {
                consoleUsed = true;
                consoleDir = ops.projectRoot();
                panel().started(title);
                runtimeGeneration++;
                panel().setRuntime(null);
            }

            @Override
            public void line(String text) {
                panel().appendOutput(text, false);
            }

            @Override
            public void ended(boolean ok, String message) {
                deploying = false;
                if (!ok && message != null) {
                    panel().appendOutput(message, true);
                    panel().failed(message);
                }
                ops.onRunStateChanged();
            }
        };
    }

    /**
     * A deployed Java configuration: built on the host with its Maven or Gradle (the copy has sources, not
     * classes), then started there with the host's JDK. Debug starts it under a JDWP agent on the host's
     * loopback, tunnels that port here, and attaches the debugger through the local project — whose sources
     * are the same files.
     */
    private void runDeployedJava(RunConfiguration cfg, boolean debug) {
        if (cfg.missingMainClass()) {
            reportIncomplete(cfg);
            return;
        }
        if (cfg.mainClassLooksLikeAFile()) {
            host.setStatus(tr("status.run.mainClassIsAFile", cfg.mainClass()));
            ops.editConfiguration(cfg.name());
            return;
        }
        Path localRoot = deployed.localRoot();
        Path routing = routingFor(host, cfg);
        if (debug && routing == null) {
            host.setStatus(tr("status.run.configNeedsJavaFile")); // the debugger is attached through it
            return;
        }
        Path localModule = routing == null ? null : ops.javaProjectRoot(routing);
        if (localModule == null || com.editora.deploy.DeployPaths.relative(localRoot, localModule) == null) {
            localModule = localRoot;
        }
        boolean maven = ops.mavenProjectAt(localModule);
        boolean gradle = !maven && ops.gradleProjectAt(localModule);
        if (!maven && !gradle) {
            host.setStatus(tr("status.deploy.javaNeedsBuild", cfg.name()));
            return;
        }
        Path hostModule = onLaunchHost(localModule);
        com.editora.run.JavaLaunchPlan.Result planned = com.editora.run.JavaLaunchPlan.forConfiguration(
                cfg, com.editora.run.JavaLaunchPlan.Inputs.of(hostModule, "", maven));
        if (!planned.ok()) {
            reportPlanProblem(cfg, planned.problem());
            return;
        }
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        List<String> vm = new java.util.ArrayList<>(plan.vmArgs());
        if (debug) {
            vm.add(0, "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0");
        }
        String java = plan.javaExecutable().isBlank() ? "java" : plan.javaExecutable();
        String label = shortName(cfg.mainClass());
        Consumer<List<String>> launch = cp -> {
            if (cp == null || cp.isEmpty()) {
                return;
            }
            if (debug) {
                pendingOutputWatcher = debugAttacher(routing, hostModule);
            }
            streamRun(
                    label,
                    plan.cwd(),
                    JavaRunCommand.build(java, List.of(), cp, cfg.mainClass(), vm, plan.args()),
                    plan.environment());
        };
        host.setStatus(tr("status.deploy.building", deployed.deployment().name()));
        if (maven) {
            ops.resolveMavenClasspath(hostModule, plan.jdkHome(), cp -> {
                if (cp == null || cp.isEmpty()) {
                    host.setStatus(tr("status.deploy.buildFailed", cfg.name()));
                }
                launch.accept(cp);
            });
            return;
        }
        Path gradleRoot = onLaunchHost(gradleBuildRoot(localRoot, localModule));
        Thread.ofVirtual().name("deploy-gradle-cp").start(() -> {
            String[] error = new String[1];
            List<String> cp =
                    com.editora.build.GradleHostClasspath.resolve(gradleRoot, hostModule, plan.jdkHome(), error);
            javafx.application.Platform.runLater(() -> {
                if (cp == null || cp.isEmpty()) {
                    host.setStatus(tr(
                            "status.deploy.gradleFailed",
                            cfg.name(),
                            error[0] == null || error[0].isBlank() ? "gradle" : error[0]));
                }
                launch.accept(cp);
            });
        });
    }

    /** The Gradle build {@code module} belongs to: the topmost folder up to {@code root} with a settings file. */
    static Path gradleBuildRoot(Path root, Path module) {
        Path found = module;
        for (Path dir = module; dir != null && dir.startsWith(root); dir = dir.getParent()) {
            if (java.nio.file.Files.isRegularFile(dir.resolve("settings.gradle"))
                    || java.nio.file.Files.isRegularFile(dir.resolve("settings.gradle.kts"))) {
                found = dir;
            }
        }
        return found;
    }

    /**
     * Watches a deployed program's output for the JDWP agent's "Listening" line, tunnels that port of the host
     * to this machine, and attaches the debugger to it through {@code anchor}.
     */
    private java.util.function.BiConsumer<String, Boolean> debugAttacher(Path anchor, Path onHost) {
        boolean[] attached = {false};
        return (line, stderr) -> {
            int port = com.editora.test.TestDebug.jdwpPort(line);
            if (port <= 0 || attached[0]) {
                return;
            }
            attached[0] = true;
            Thread.ofVirtual().name("deploy-debug-tunnel").start(() -> {
                try {
                    com.editora.process.PortForward tunnel =
                            com.editora.process.ExecTargets.forPath(onHost).forwardLocal("127.0.0.1", port);
                    javafx.application.Platform.runLater(() -> {
                        closeDebugTunnel();
                        debugTunnel = tunnel;
                        ops.attachJavaDebugger(anchor, tunnel.localPort(), this::stopRun);
                    });
                } catch (java.io.IOException | RuntimeException e) {
                    javafx.application.Platform.runLater(
                            () -> host.setStatus(tr("status.deploy.tunnelFailed", String.valueOf(e.getMessage()))));
                }
            });
        };
    }

    private void closeDebugTunnel() {
        if (debugTunnel != null) {
            debugTunnel.close();
            debugTunnel = null;
        }
    }

    /**
     * Saves the open tab of {@code script} (null = none) and then the tab in front: the script is what runs,
     * and a before-launch step may read the file being edited in front. False when a save was refused.
     */
    private boolean saveScriptAndActive(Path script) {
        return (script == null || saveOpenBuffer(script)) && saveActiveBuffer();
    }

    /**
     * Saves the open tab of {@code file} if it has unsaved edits, whichever tab is in front; false when that
     * save was refused or failed. A file with no open tab, or none edited, needs nothing.
     */
    private boolean saveOpenBuffer(Path file) {
        EditorBuffer[] found = new EditorBuffer[1];
        host.forEachBuffer(b -> {
            if (found[0] == null && b.getPath() != null && com.editora.config.PathKeys.samePath(b.getPath(), file)) {
                found[0] = b;
            }
        });
        EditorBuffer b = found[0];
        return b == null || !b.isDirty() || ops.saveBuffer(b);
    }

    /**
     * The environment of a shell run: the shebang's {@code env} assignments, then {@code own} (a
     * configuration's variables, which win), then defaults nothing above set — {@code TERM} (the console draws
     * colours, so a colour terminal type; inherited it was whatever launched Editora, often unset or
     * {@code dumb}), the {@code read -p} prompt shim for a local bash, and the trace prompt when the
     * interpreter traces.
     */
    private static java.util.Map<String, String> shellEnvironment(
            ShellCommand.Launch shell, java.util.Map<String, String> own, boolean remote) {
        // A null value unsets the variable (the shebang's `env -u NAME`), so "absent" is containsKey, not get.
        java.util.Map<String, String> all = new java.util.LinkedHashMap<>(shell.env());
        all.putAll(own);
        java.util.function.BiConsumer<String, String> byDefault = (k, v) -> {
            if (!all.containsKey(k)) {
                all.put(k, v);
            }
        };
        byDefault.accept("TERM", SHELL_TERM);
        if (!remote && ShellCommand.isBash(shell.interpreter().get(0))) {
            ReadPromptShim.environment(System.getenv()).forEach(byDefault);
        }
        if (ShellCommand.traces(shell)) {
            byDefault.accept("PS4", ShellCommand.TRACE_PROMPT);
        }
        return all;
    }

    /**
     * Where a shell run starts: {@code cwd}, or the folder the shebang's {@code env -C DIR} names (relative to
     * {@code cwd}). Null, after saying so, when that folder does not exist here.
     */
    private Path shebangDir(ShellCommand.Launch shell, Path cwd, boolean remote) {
        return chdir(shell.chdir(), cwd, remote);
    }

    /** {@code cwd}, or {@code chdir} (a shebang's {@code env -C DIR}) resolved against it; null when missing. */
    private Path chdir(String chdir, Path cwd, boolean remote) {
        if (chdir == null || chdir.isBlank()) {
            return cwd;
        }
        Path dir = cwd.resolve(chdir).normalize();
        if (!remote && !java.nio.file.Files.isDirectory(dir)) {
            host.setStatus(tr("status.run.shebangDirMissing", dir.toString()));
            return null;
        }
        return dir;
    }

    /** The terminal type a shell run gets: the Run console renders colour codes but not cursor movement. */
    static final String SHELL_TERM = "xterm-256color";

    /** Saves the active buffer if it is a dirty local file; false when that save was refused or failed. */
    private boolean saveActiveBuffer() {
        EditorBuffer b = host.activeBuffer();
        return b == null || b.getPath() == null || !b.isDirty() || ops.saveBuffer(b);
    }

    /**
     * Runs {@code cfg}'s before-launch command in the Run console, then {@code then} — see
     * {@link BeforeLaunchStep}. On this coordinator's own {@link RunService}, so the step and a program
     * exclude each other and {@link #stopRun} reaches whichever is alive.
     */
    private void withBeforeLaunch(RunConfiguration cfg, Runnable then) {
        Path cwd = beforeLaunchDir(cfg);
        Path routing = routingFor(host, cfg);
        Path project = onLaunchHost(routing == null ? ops.projectRoot() : ops.javaProjectRoot(routing));
        String jdkHome = project == null ? effectiveMavenJdk(null, cfg) : jdkHomeFor(project, cfg);
        beforeLaunch.run(
                host,
                cfg,
                cwd,
                project == null
                        ? JdkToolchain.environment(jdkHome, processPath())
                        : javaEnvironment(project, jdkHome, null),
                new BeforeLaunchStep.Console() {
                    @Override
                    public void started(String commandLine) {
                        consoleDir = cwd; // so a compiler error's file link in the build output resolves
                        consoleUsed = true;
                        panel().started(commandLine);
                        runtimeGeneration++; // the build step is not the program: no runtime chip for it
                        panel().setRuntime(null);
                    }

                    @Override
                    public void output(String line, boolean stderr) {
                        panel().appendOutput(line, stderr);
                    }

                    @Override
                    public void ended(int code, String launchError) {
                        if (launchError != null) {
                            panel().failed(launchError);
                        } else {
                            panel().finished(code);
                        }
                        ops.onRunStateChanged();
                    }
                },
                then);
        ops.onRunStateChanged(); // the step is running now: the toolbar's Stop button applies to it
    }

    /** Where a before-launch command runs: the configuration's working directory, else the project root. */
    Path beforeLaunchDir(RunConfiguration cfg) {
        if (cfg.workingDir().isBlank() && ScriptRunCommand.PYTHON.equals(cfg.type()) && configRoot() != null) {
            return configRoot(); // the script runs in its own folder; a build/setup step stays at the root
        }
        // A Java configuration's step is a build (mvn compile, gradle classes): it belongs in the project root
        // whatever folder the program runs in — in a working directory of "sub" Maven found no pom (review A12).
        if (cfg.isJava()) {
            Path routing = routingFor(host, cfg);
            Path project = routing == null ? null : ops.javaProjectRoot(routing);
            if (project == null) {
                project = ops.projectRoot();
            }
            if (project != null) {
                return onLaunchHost(project);
            }
        }
        Path configured = RunConfigWorkingDirectory.resolve(cfg, configRoot());
        if (configured != null) {
            return configured;
        }
        Path routing = routingFor(host, cfg);
        Path root = routing == null ? null : ops.javaProjectRoot(routing);
        return root != null ? root : Path.of(System.getProperty("user.dir"));
    }

    /** The Java half of {@link #runConfig}, after any before-launch step has succeeded. */
    private void runJavaConfig(RunConfiguration cfg) {
        // Mirrors runScriptConfig's missing-target check. Without it the blank main class reaches jdtls and
        // comes back as an internal NPE from its search engine — see RunConfiguration.missingMainClass.
        if (cfg.missingMainClass()) {
            reportIncomplete(cfg);
            return;
        }
        // A file name where a class name belongs resolves to an EMPTY classpath with no error, which the
        // launch would otherwise report as "the project hasn't finished importing" — blaming a healthy
        // language server for a typo. Say what is actually wrong and open the field that fixes it.
        if (cfg.mainClassLooksLikeAFile()) {
            host.setStatus(tr("status.run.mainClassIsAFile", cfg.mainClass()));
            ops.editConfiguration(cfg.name());
            return;
        }
        // A named configuration is independent of whatever is on screen: any open Java file in its project
        // can route the classpath resolution, so this no longer refuses just because the active tab is a
        // README. Null means no Java file is open at all, which is the only genuinely unresolvable case.
        Path routing = routingFor(host, cfg);
        if (routing == null) {
            host.setStatus(tr("status.run.configNeedsJavaFile"));
            return;
        }
        Path root = ops.javaProjectRoot(routing);
        if (root == null) {
            host.setStatus(tr("status.run.noProject"));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.isDirty() && RemoteExec.runsFor(b.getPath()) && !ops.saveBuffer(b)) {
            return; // save whatever the user was editing before launching, as before
        }
        // One plan for Run and Debug (review D9): working directory, JDK, environment and arguments.
        com.editora.run.JavaLaunchPlan.Result planned =
                com.editora.run.JavaLaunchPlan.forConfiguration(cfg, javaLaunchInputs(root));
        if (!planned.ok()) {
            reportPlanProblem(cfg, planned.problem());
            return;
        }
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        Path cwd = plan.cwd();
        List<String> vm = plan.vmArgs();
        List<String> args = plan.args();
        String jdkHome = plan.jdkHome();
        java.util.Map<String, String> launchEnv = plan.environment();
        boolean onHost = !com.editora.process.ExecTargets.runsHere(root);
        String javaOverride = plan.javaExecutable();
        String label = shortName(cfg.mainClass());
        if (ops.javaLaunchAvailable()) {
            // Ask jdtls to enumerate its own main classes first and use the entry it returns, exactly as the
            // Debug path and the gutter Run marker already do.
            //
            // Hand-building the option instead is what made a saved configuration fail where the identical
            // gutter click worked: resolveClasspath is passed (mainClass, projectName), a saved configuration
            // usually carries a BLANK projectName, and jdtls answers an unmatched project with an EMPTY
            // classpath and NO error — surfacing as "make sure the Java project has finished importing" for a
            // project that had imported perfectly. jdtls's own entry carries the real project name (and the
            // file that actually declares the class, rather than whichever file happened to route the call).
            //
            // Falls back to the configuration's own values when the enumeration comes back empty, so a
            // project jdtls cannot enumerate still gets its previous behaviour rather than nothing.
            ops.resolveJavaMainClasses(routing, options -> {
                JavaMainClass mc = options.stream()
                        .filter(o -> cfg.mainClass().equals(o.className()))
                        .findFirst()
                        .orElseGet(() -> new JavaMainClass(cfg.mainClass(), cfg.projectName(), routing.toString()));
                ops.resolveJavaLaunch(routing, mc, info -> {
                    if (info == null || !info.ok()) {
                        host.setStatus(info == null ? tr("status.run.resolveFailed") : info.error());
                        return;
                    }
                    streamRun(
                            label,
                            cwd,
                            JavaRunCommand.build(
                                    javaOverride.isBlank() ? info.javaExec() : javaOverride,
                                    info.modulePaths(),
                                    info.classPaths(),
                                    cfg.mainClass(),
                                    info.vmArgs(vm),
                                    args),
                            launchEnv);
                });
            });
        } else if (ops.mavenProjectAt(root)) {
            host.setStatus(tr("status.run.resolvingClasspath"));
            ops.resolveMavenClasspath(root, jdkHome, cp -> {
                if (cp == null || cp.isEmpty()) {
                    host.setStatus(tr("status.run.resolveFailed"));
                    return;
                }
                streamRun(
                        label,
                        cwd,
                        JavaRunCommand.build(
                                javaOverride.isBlank() && onHost ? "java" : javaOverride,
                                List.of(),
                                cp,
                                cfg.mainClass(),
                                vm,
                                args),
                        launchEnv);
            });
        } else {
            host.setStatus(tr("status.run.javaUnavailable"));
        }
    }

    /** What a {@link com.editora.run.JavaLaunchPlan} of a configuration in {@code root}'s project needs to know. */
    com.editora.run.JavaLaunchPlan.Inputs javaLaunchInputs(Path root) {
        return com.editora.run.JavaLaunchPlan.Inputs.of(
                root, host.settings().getMavenJdkHome(), ops.mavenProjectAt(root));
    }

    /** Says which field of {@code cfg} a plan could not read, and opens its form there. */
    void reportPlanProblem(RunConfiguration cfg, com.editora.run.JavaLaunchPlan.Problem problem) {
        String quote = String.valueOf(problem.quote());
        host.setStatus(
                problem.field() == com.editora.run.JavaLaunchPlan.Field.ENV
                        ? tr("status.run.envUnclosedQuote", cfg.name(), quote)
                        : tr("status.run.argsUnclosedQuote", quote));
        ops.editConfiguration(cfg.name());
    }

    private void startMainClassRun(String targetFqn) {
        EditorBuffer b = host.activeBuffer();
        if (b == null
                || b.getPath() == null
                || !RemoteExec.runsFor(b.getPath()) // a remote project's class runs on its host
                || !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.run.needJavaFile"));
            return;
        }
        if (!beginRunRequest()) {
            return;
        }
        Path routing = b.getPath();
        Path root = ops.javaProjectRoot(routing);
        if (root == null) {
            host.setStatus(tr("status.run.noProject"));
            return;
        }
        if (b.isDirty() && !ops.saveBuffer(b)) {
            return;
        }
        // A saved configuration is the user's stated intent for how this project runs: the before-launch
        // build, the program and VM arguments, the environment, the working directory. The ad-hoc path below
        // has none of that — which is exactly why a freshly generated project's gutter ▶ launched against an
        // empty target/classes. So in a Maven/Gradle project, run the configuration instead.
        RunConfiguration configured = configurationForGutterRun(targetFqn, root);
        if (configured != null) {
            runConfig(configured);
            return;
        }
        if (ops.javaLaunchAvailable()) {
            runViaJdtls(routing, root, targetFqn); // fast + project-wide enumeration
        } else if (ops.mavenProjectAt(root)) {
            runViaMaven(b, root, targetFqn); // fallback: resolve the classpath with mvn, run `java -cp`
        } else if (ops.gradleProjectAt(root)) {
            ops.runGradleRunTask(root); // Gradle has no clean classpath dump — delegate to run/bootRun
            host.setStatus(tr("status.run.gradleFallback"));
        } else {
            host.setStatus(tr("status.run.javaUnavailable"));
        }
    }

    /**
     * The saved configuration the gutter ▶ should run for {@code targetFqn}, or null to fall back to the
     * ad-hoc main-class run.
     *
     * <p>Preference order, and why:
     *
     * <ol>
     *   <li>a configuration that launches <b>this</b> class — never surprising, and the case a generated
     *       project lands in;
     *   <li>otherwise the toolbar's selected configuration, so the ▶ is as capable as the Run button.
     * </ol>
     *
     * <p>Only in a Maven/Gradle project: elsewhere there is no build for a before-launch step to run and the
     * ad-hoc path is the whole story. A compact source file never reaches here — {@code EditorBuffer} routes
     * those to their own handler.
     */
    /**
     * The configuration a gutter ▶ should launch, or null to keep the ad-hoc main-class run.
     *
     * <p>In a Maven/Gradle project the configuration is the better launch: it carries the before-launch build,
     * program/VM arguments, working dir and env that a bare "run this main class" has none of — so a generated
     * project whose {@code target/classes} was empty launched and failed. The configuration naming the clicked
     * class wins; only if none does do we fall back to whatever the toolbar has selected. Outside a build tool
     * there is nothing for a before-launch step to do, so the plain path stays.
     */
    private RunConfiguration configurationForGutterRun(String targetFqn, Path root) {
        if (root == null || !(ops.mavenProjectAt(root) || ops.gradleProjectAt(root))) {
            return null;
        }
        List<RunConfiguration> all = ops.runConfigurations();
        if (all == null || all.isEmpty()) {
            return null;
        }
        if (targetFqn != null && !targetFqn.isBlank()) {
            for (RunConfiguration c : all) {
                if (c.isJava() && targetFqn.equals(c.mainClass())) {
                    return c;
                }
            }
        }
        String selected = ops.selectedRunConfigName();
        if (selected == null || selected.isBlank()) {
            return null;
        }
        return all.stream()
                .filter(c -> c.isJava() && selected.equals(c.name()))
                .findFirst()
                .orElse(null);
    }

    private void runViaJdtls(Path routing, Path root, String targetFqn) {
        ops.resolveJavaMainClasses(routing, list -> {
            if (list.isEmpty()) {
                host.setStatus(tr("status.run.noMainClass"));
                return;
            }
            if (targetFqn != null) {
                JavaMainClass match = list.stream()
                        .filter(mc -> targetFqn.equals(mc.className()))
                        .findFirst()
                        .orElse(null);
                if (match == null) {
                    host.setStatus(tr("status.run.noMainClass"));
                    return;
                }
                runResolvedMainClass(routing, root, match);
            } else if (list.size() == 1) {
                runResolvedMainClass(routing, root, list.get(0));
            } else {
                pickMainClass(list, mc -> runResolvedMainClass(routing, root, mc));
            }
        });
    }

    /**
     * Build-tool fallback (no jdtls): the main class comes from a source scan of the active file (jdtls's
     * project-wide enumeration isn't available), and the classpath from {@code mvn compile
     * dependency:build-classpath} + {@code target/classes}. File-scoped: it runs a {@code main} in the active
     * buffer, not any project class.
     */
    private void runViaMaven(EditorBuffer b, Path root, String targetFqn) {
        List<com.editora.run.MainMethodScanner.MainMethod> mains =
                com.editora.run.MainMethodScanner.scan(b.getContent());
        com.editora.run.MainMethodScanner.MainMethod chosen = null;
        if (targetFqn != null) {
            chosen = mains.stream()
                    .filter(m -> targetFqn.equals(m.fqn()))
                    .findFirst()
                    .orElse(null);
        } else if (mains.size() == 1) {
            chosen = mains.get(0);
        } else if (mains.size() > 1) {
            pickMainMethod(mains, m -> resolveMavenAndRun(b, root, m));
            return;
        }
        if (chosen == null) {
            host.setStatus(tr("status.run.noMainInFile"));
            return;
        }
        resolveMavenAndRun(b, root, chosen);
    }

    private void resolveMavenAndRun(EditorBuffer b, Path root, com.editora.run.MainMethodScanner.MainMethod m) {
        host.setStatus(tr("status.run.resolvingClasspath"));
        String jdkHome = jdkHomeFor(root, null);
        ops.resolveMavenClasspath(root, jdkHome, cp -> {
            if (cp == null || cp.isEmpty()) {
                host.setStatus(tr("status.run.resolveFailed"));
                return;
            }
            List<String> args = programArgs(ops.programArgs(b.getPath()), null);
            if (args == null) {
                return;
            }
            String java = !com.editora.process.ExecTargets.runsHere(root)
                    ? hostJava(jdkHome, null)
                    : JdkToolchain.javaExecutable(jdkHome);
            List<String> command = JavaRunCommand.build(java, List.of(), cp, m.fqn(), List.of(), args);
            streamRun(shortName(m.fqn()), root, command, javaEnvironment(root, jdkHome, null));
        });
    }

    private void pickMainMethod(
            List<com.editora.run.MainMethodScanner.MainMethod> options,
            Consumer<com.editora.run.MainMethodScanner.MainMethod> chosen) {
        QuickOpen<com.editora.run.MainMethodScanner.MainMethod> picker = new QuickOpen<>(
                tr("run.pickMainTitle"),
                tr("run.pickMainPrompt"),
                () -> options,
                com.editora.run.MainMethodScanner.MainMethod::fqn,
                m -> "",
                chosen);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    private void runResolvedMainClass(Path routing, Path root, JavaMainClass mc) {
        if (mc == null) {
            return;
        }
        ops.resolveJavaLaunch(routing, mc, info -> {
            if (info == null || !info.ok()) {
                host.setStatus(info == null ? tr("status.run.resolveFailed") : info.error());
                return;
            }
            List<String> args = programArgs(programArgsForMain(mc), null);
            if (args == null) {
                return;
            }
            List<String> command = JavaRunCommand.build(
                    javaExecutableFor(root, info.javaExec()),
                    info.modulePaths(),
                    info.classPaths(),
                    mc.fqn(),
                    info.vmArgs(List.of()),
                    args);
            String jdkHome = jdkHomeFor(root, null);
            streamRun(shortName(mc.className()), root, command, javaEnvironment(root, jdkHome, null));
        });
    }

    /** Global Maven JDK, with a saved configuration's project override taking precedence. */
    private String effectiveMavenJdk(Path root, RunConfiguration cfg) {
        if (root == null || !ops.mavenProjectAt(root)) {
            return "";
        }
        return JdkToolchain.effectiveHome(
                cfg == null ? "" : cfg.jdkHome(), host.settings().getMavenJdkHome());
    }

    private String javaExecutableFor(Path root, String resolved) {
        if (!com.editora.process.ExecTargets.runsHere(root)) {
            return hostJava(remoteJdkHome(root), resolved);
        }
        String configured = JdkToolchain.javaExecutable(effectiveMavenJdk(root, null));
        return configured.isBlank() ? resolved : configured;
    }

    /**
     * The JDK a Java project's program runs with: for a project on a remote host the host's ({@code jdk.home}
     * in its tools, or blank for its own java), never this machine's setting, which names a folder here.
     */
    private String jdkHomeFor(Path root, RunConfiguration cfg) {
        return !com.editora.process.ExecTargets.runsHere(root) ? remoteJdkHome(root) : effectiveMavenJdk(root, cfg);
    }

    /** The environment for a Java program or build of {@code root}: on a host, its JDK only — not this PATH. */
    private static java.util.Map<String, String> javaEnvironment(
            Path root, String jdkHome, java.util.Map<String, String> configured) {
        if (com.editora.process.ExecTargets.runsHere(root)) {
            return launchEnvironment(jdkHome, configured);
        }
        java.util.LinkedHashMap<String, String> env = new java.util.LinkedHashMap<>(remoteJdkEnvironment(jdkHome));
        if (configured != null) {
            env.putAll(configured);
        }
        return java.util.Map.copyOf(env);
    }

    /** {@code java} on a host: under its configured JDK, else what jdtls resolved there, else its PATH's. */
    static String hostJava(String jdkHome, String resolved) {
        if (jdkHome != null && !jdkHome.isBlank()) {
            return jdkHome.strip().replaceAll("/+$", "") + "/bin/java";
        }
        return resolved == null || resolved.isBlank() ? "java" : resolved;
    }

    private static java.util.Map<String, String> launchEnvironment(
            String jdkHome, java.util.Map<String, String> configured) {
        java.util.LinkedHashMap<String, String> env =
                new java.util.LinkedHashMap<>(JdkToolchain.environment(jdkHome, processPath()));
        if (configured != null) {
            env.putAll(configured);
        }
        return java.util.Map.copyOf(env);
    }

    private static String processPath() {
        return com.editora.process.ProcessRunner.augmentedPath();
    }

    private void pickMainClass(List<JavaMainClass> options, Consumer<JavaMainClass> chosen) {
        QuickOpen<JavaMainClass> picker = new QuickOpen<>(
                tr("run.pickMainTitle"),
                tr("run.pickMainPrompt"),
                () -> options,
                JavaMainClass::fqn,
                mc -> mc.projectName() == null ? "" : mc.projectName(),
                chosen);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Per-main-class program args, reusing the per-file store keyed by the main class's own source file. */
    private String programArgsForMain(JavaMainClass mc) {
        if (mc.filePath() == null || mc.filePath().isBlank()) {
            return "";
        }
        try {
            // jdtls names the file as its machine does: on a remote project, a path on the host.
            Path project = ops.projectRoot();
            Path file = project != null && !com.editora.process.ExecTargets.runsHere(project)
                    ? com.editora.process.ExecTargets.forPath(project).toPath(mc.filePath())
                    : Path.of(mc.filePath());
            return ops.programArgs(file);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String shortName(String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /**
     * Runs a single Makefile target ({@code make -f <file> [target]}) in the Makefile's directory, saving
     * the buffer first so the on-disk targets/recipes are current. A blank/null {@code target} runs the
     * default goal (bare {@code make}). Backs the per-target gutter ▶ and the generic "Run File" command.
     */
    void runMakeTarget(EditorBuffer buffer, String target) {
        if (buffer == null) {
            return;
        }
        if (!beginRunRequest()) {
            return;
        }
        if ((buffer.isDirty() || buffer.getPath() == null) && !ops.saveBuffer(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run against stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        List<String> command = new ArrayList<>();
        command.add("make");
        // -f the buffer's own file name (basename; RunService runs in its dir): make otherwise reads the
        // dir's default Makefile, so a `build.mk` / `Makefile.inc` buffer would run the wrong file.
        command.add("-f");
        command.add(path.getFileName().toString());
        if (target != null && !target.isBlank()) {
            command.add(target);
        }
        launchRun(path, command);
    }

    private void runActiveFile(boolean promptArgs, boolean trace) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer != null) {
            buffer.settleRunScan(); // the load's scan runs off FX; the answer is needed now
        }
        if (buffer != null && buffer.isHttpFile()) {
            // A request file is "run" by sending its requests — the generic path launched `java requests.http`.
            if (!ops.runHttpFile(buffer)) {
                host.setStatus(tr("status.run.notCompact"));
            }
            return;
        }
        if (trace && buffer != null && !buffer.isShell()) {
            host.setStatus(tr("status.run.traceNotShell"));
            return;
        }
        if (buffer == null || !buffer.isRunnable()) {
            host.setStatus(tr("status.run.notRunnable"));
            return;
        }
        if (trace && !traceable(buffer)) {
            return;
        }
        if (buffer.isMakefile()) {
            runMakeTarget(buffer, null); // "Run File" on a Makefile ⇒ the default goal
            return;
        }
        if (buffer.isShell()) {
            // Windows line endings or a byte-order mark break a script in ways bash reports confusingly
            // ("$'\r': command not found", "unexpected end of file"), so say so before running it.
            java.util.Set<ShellRunWarning.Hazard> hazards =
                    ShellRunWarning.hazards(buffer.getLineEnding(), buffer.getEffectiveCharset());
            if (!hazards.isEmpty()) {
                ShellRunWarning.ask(host.window(), buffer, hazards, () -> startFileRun(buffer, promptArgs, trace));
                return;
            }
        }
        startFileRun(buffer, promptArgs, trace);
    }

    /** The editor right-click items Run adds for {@code buffer}: Run with Trace on a runnable shell script. */
    List<javafx.scene.control.MenuItem> editorMenuItems(EditorBuffer buffer) {
        if (buffer == null || !buffer.isShell() || !buffer.isRunnable()) {
            return List.of();
        }
        javafx.scene.control.MenuItem trace = new javafx.scene.control.MenuItem(tr("editmenu.runWithTrace"));
        trace.setOnAction(e -> runActiveFileWithTrace());
        return List.of(trace);
    }

    /** Whether Run with Trace can trace {@code buffer}; says why not when it cannot. */
    private boolean traceable(EditorBuffer buffer) {
        if (!buffer.isShell()) {
            host.setStatus(tr("status.run.traceNotShell"));
            return false;
        }
        String name = buffer.getPath() != null ? buffer.getPath().getFileName().toString() : buffer.getTitle();
        ShellCommand.Launch shell = ShellCommand.launch(firstLine(buffer), name, "", "", null);
        if (!ShellCommand.canTrace(shell)) {
            host.setStatus(tr("status.run.traceUnsupported", name, ShellCommand.interpreterName(shell)));
            return false;
        }
        return true;
    }

    /** Runs {@code buffer} (already known to be runnable): save, pick the launcher, maybe ask for arguments. */
    private void startFileRun(EditorBuffer buffer, boolean promptArgs, boolean trace) {
        if (!beginRunRequest()) {
            return;
        }
        if ((buffer.isDirty() || buffer.getPath() == null) && !ops.saveBuffer(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        // A Groovy (and later Kotlin, Scala, Clojure) file runs through its own tool, on the same JDK as Java.
        boolean jvmScript = com.editora.run.JvmScriptRun.supports(buffer.getLanguage());
        boolean javaSource = !buffer.isPython()
                && !buffer.isShell()
                && !com.editora.run.NodeRunPlan.isNodeLanguage(buffer.getLanguage())
                && !jvmScript;
        boolean onJdk = javaSource || jvmScript;
        // On a remote host the program runs with the host's own java and environment: this machine's JDK
        // setting is a path here, and its PATH means nothing there. A host can name its JDK (jdk.home).
        boolean remote = !com.editora.process.ExecTargets.runsHere(path);
        String jdkHome =
                !onJdk ? "" : remote ? remoteJdkHome(path) : host.settings().getMavenJdkHome();
        String selectedJava = JdkToolchain.javaExecutable(jdkHome);
        String javaExecutable = selectedJava.isBlank() ? "java" : selectedJava;
        java.util.Map<String, String> launchEnv = !onJdk
                ? java.util.Map.of()
                : remote ? remoteJdkEnvironment(jdkHome) : JdkToolchain.environment(jdkHome, processPath());
        if (jvmScript && !remote && jvmTool(buffer) == null) {
            // Said here, not as the shell's "groovy: command not found" in the Run console.
            host.setStatus(tr(com.editora.run.JvmScriptRun.missingToolKey(buffer.getLanguage())));
            return;
        }
        Runnable proceed = () -> {
            String stored = ops.programArgs(path);
            if (promptArgs) {
                host.promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                    ops.setProgramArgs(path, args == null ? "" : args.strip());
                    launchFile(buffer, path, javaExecutable, launchEnv, trace);
                });
            } else {
                launchFile(buffer, path, javaExecutable, launchEnv, trace);
            }
        };
        if (javaSource) {
            // Probe exactly the launcher we will use; a selected JDK may differ from PATH. The
            // extensionless shebang can request a source release newer than the compact-source minimum.
            Integer sourceRelease = buffer.getShebangJavaSource();
            if (sourceRelease != null && sourceRelease < 25) {
                host.setStatus(tr("status.run.needSource25", sourceRelease));
                return;
            }
            service.detectJavaMajor(javaExecutable, remote ? path.getParent() : null, major -> {
                if (major < 0) {
                    host.setStatus(tr("status.run.javaVersionUnreadable", javaExecutable));
                    return;
                }
                int required = Math.max(25, sourceRelease == null ? 25 : sourceRelease);
                if (major > 0 && major < required) {
                    host.setStatus(
                            required == 25
                                    ? tr("status.run.needJdk25", major)
                                    : tr("status.run.needJdkVersion", required, major));
                    return;
                }
                proceed.run();
            });
        } else {
            proceed.run();
        }
    }

    /** Builds and launches the file's command with its remembered arguments. */
    private void launchFile(
            EditorBuffer buffer,
            Path path,
            String javaExecutable,
            java.util.Map<String, String> launchEnv,
            boolean trace) {
        List<String> args = programArgs(ops.programArgs(path), null);
        if (args == null) {
            return;
        }
        if (buffer.isPython() && ops.runPythonTestFile(path, buffer.getContent())) {
            return; // test_*.py: pytest runs its tests (a plain `python test_x.py` ran none and exited 0)
        }
        if (com.editora.run.NodeRunPlan.isNodeLanguage(buffer.getLanguage())) {
            launchNode(path, args);
            return;
        }
        if (buffer.isPython()) {
            Path dir = path.getParent();
            String target = com.editora.process.ExecTargets.forPath(path).toTargetPath(path);
            PythonRunLauncher.plan(
                    com.editora.run.PythonRunPlan.Request.file(
                            path,
                            ops.projectRoot(),
                            target,
                            args,
                            firstLine(buffer),
                            ops.pythonInterpreterChoice(),
                            PythonRunLauncher.setting(path, host.settings())),
                    dir,
                    plan -> streamPython(path.getFileName().toString(), dir, plan, path));
            return;
        }
        java.util.Map<String, String> env = launchEnv;
        if ("kotlin".equals(buffer.getLanguage())
                && com.editora.run.KotlinRun.shape(path.getFileName().toString())
                        == com.editora.run.KotlinRun.Shape.PROGRAM) {
            String kotlinc = jvmTool(buffer);
            if (!com.editora.process.ExecTargets.runsHere(path) || kotlinc == null) {
                host.setStatus(tr(
                        kotlinc == null
                                ? com.editora.run.JvmScriptRun.missingToolKey("kotlin")
                                : "status.run.kotlinProgramRemote"));
                return;
            }
            runKotlinProgram(
                    path.getFileName().toString(),
                    path,
                    path.getParent(),
                    javaExecutable,
                    List.of(),
                    args,
                    env,
                    kotlinc);
            return;
        }
        ShellCommand.Launch shell = null;
        if (buffer.isShell()) {
            boolean remote = !com.editora.process.ExecTargets.runsHere(path);
            // The shebang decides the interpreter and its flags, as running ./script would.
            shell = ShellCommand.launch(firstLine(buffer), path.getFileName().toString(), "", "", existsHere(remote));
            if (trace) {
                shell = ShellCommand.withTrace(shell);
            }
            env = shellEnvironment(shell, java.util.Map.of(), remote);
        }
        Path dir = shell == null
                ? path.getParent()
                : shebangDir(shell, path.getParent(), com.editora.vfs.Vfs.isRemote(path));
        if (dir == null) {
            return;
        }
        streamRun(
                path.getFileName().toString(),
                dir,
                buildRunCommand(buffer, path, javaExecutable, args, shell),
                env,
                path);
    }

    /**
     * Run File on a Node.js script: a test file's ▶ runs its tests into Test Results ({@code node --test}), any
     * other file runs under the Node {@link com.editora.run.NodeRuntimes} picks, from its package root.
     */
    private void launchNode(Path path, List<String> args) {
        Path root = ops.projectRoot();
        boolean test = com.editora.run.NodeRunPlan.isTestFile(path, root);
        if (test && ops.runNodeTestFile(path)) {
            return;
        }
        NodeRunLauncher.plan(
                new NodeRunLauncher.Spec(
                        path,
                        root,
                        "",
                        host.settings().getNodePath(),
                        args,
                        List.of(),
                        false,
                        java.util.Map.of(),
                        null),
                ready -> streamNode(path.getFileName().toString(), ready, path));
    }

    /** Streams a planned Node launch, or says why it cannot run. */
    void streamNode(String label, NodeRunLauncher.Ready ready, Path script) {
        com.editora.run.NodeRunPlan plan = ready.plan();
        if (plan.error() != null) {
            host.setStatus(tr(plan.error()));
            panel().failed(tr(plan.error()));
            return;
        }
        Path dir = chdir(plan.chdir(), ready.dir(), com.editora.vfs.Vfs.isRemote(ready.dir()));
        if (dir != null) {
            streamRun(label, dir, plan.argv(), plan.env(), script);
        }
    }

    /** The buffer's first line (at most 512 characters), where a shebang would be. */
    private static String firstLine(EditorBuffer buffer) {
        var area = buffer.getArea();
        String head = area.getText(0, Math.min(area.getLength(), 512));
        int nl = head.indexOf('\n');
        return nl >= 0 ? head.substring(0, nl) : head;
    }

    /**
     * The tool that runs {@code buffer}'s JVM language ({@code groovy}): this machine's install for a local file,
     * the bare name (the host's PATH) for a remote one; null when it is not installed here.
     */
    private static String jvmTool(EditorBuffer buffer) {
        String tool = com.editora.run.JvmScriptRun.tool(buffer.getLanguage());
        if (tool == null) {
            return null;
        }
        Path path = buffer.getPath();
        if (path != null && !com.editora.process.ExecTargets.runsHere(path)) {
            return tool;
        }
        return com.editora.run.JvmTools.locate(tool);
    }

    /** The launcher argv for the buffer's language: interpreter + file + {@code args}. */
    private List<String> buildRunCommand(
            EditorBuffer buffer, Path path, String javaExecutable, List<String> args, ShellCommand.Launch shell) {
        if (shell != null) {
            return ShellCommand.argv(
                    shell, com.editora.process.ExecTargets.forPath(path).toTargetPath(path), args);
        }
        if (com.editora.run.JvmScriptRun.supports(buffer.getLanguage())) {
            String tool = jvmTool(buffer);
            return com.editora.run.JvmScriptRun.argv(
                    buffer.getLanguage(),
                    tool == null ? com.editora.run.JvmScriptRun.tool(buffer.getLanguage()) : tool,
                    com.editora.process.ExecTargets.forPath(path).toTargetPath(path),
                    args);
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable); // (Python is planned by PythonRunLauncher)
        Integer javaSource = buffer.getShebangJavaSource();
        if (javaSource != null) {
            // An extensionless `java --source N` shebang file: the source launcher needs the flag
            // (a plain `java <file>` only works when the name ends in .java).
            command.add("--source");
            command.add(String.valueOf(javaSource));
        }
        command.add(com.editora.process.ExecTargets.forPath(path).toTargetPath(path)); // its name where it runs
        command.addAll(args);
        return command;
    }

    /** The JDK configured for {@code path}'s host ({@code jdk.home} in its tools), or "" for its own java. */
    private static String remoteJdkHome(Path path) {
        String home = com.editora.process.ExecTargets.remoteTool(path, "jdk.home");
        return home == null ? "" : home;
    }

    /** {@code JAVA_HOME} for a host's configured JDK; nothing else, and nothing at all for its own java. */
    static java.util.Map<String, String> remoteJdkEnvironment(String jdkHome) {
        return jdkHome == null || jdkHome.isBlank() ? java.util.Map.of() : java.util.Map.of("JAVA_HOME", jdkHome);
    }

    /**
     * Streams a planned Python launch; says so when the file's shebang asked for an interpreter that is not
     * installed here and the project's ran instead.
     */
    private void streamPython(String label, Path dir, com.editora.run.PythonRunPlan plan, Path script) {
        streamRun(label, dir, plan.argv(), plan.env(), script);
        if (plan.missingShebang() != null) {
            String note = tr(
                    "status.run.pythonShebangMissing",
                    plan.missingShebang(),
                    com.editora.run.PythonLaunch.shortName(plan.interpreter().executable()));
            host.setStatus(note);
            panel().showCommandLine(note); // under the command line: the status line is gone once the run ends
        }
    }

    private void launchRun(Path path, List<String> command, java.util.Map<String, String> env) {
        streamRun(path.getFileName().toString(), path.getParent(), command, env, path);
    }

    private void launchRun(Path path, List<String> command) {
        launchRun(path, command, java.util.Map.of());
    }

    /**
     * Brings the Run console forward for every valid Run request. A second request while a process is alive
     * is therefore useful rather than a silent no-op: it returns the user to the process that already owns
     * the console, then reports that another launch cannot start yet.
     */
    private boolean beginRunRequest() {
        terminalRun = false; // only a configuration that asks for it, and only its own launch
        launchRoot = null; // and only a deployed configuration's launch resolves on its host
        deployed = null;
        ops.openToolWindow();
        if (!consoleRunning()) { // a before-launch build counts: a second Run must not start a second build
            return true;
        }
        host.setStatus(tr("status.run.busy"));
        return false;
    }

    /** Runs {@code command} in {@code workingDir} (the project root for a main class, else a file's folder),
     *  streaming into the Run console and remembering it for {@code run.rerun}. */
    private void streamRun(String label, Path workingDir, List<String> command) {
        streamRun(label, workingDir, command, java.util.Map.of());
    }

    /** As above, plus {@code env} — a saved run configuration's environment variables. */
    private void streamRun(String label, Path workingDir, List<String> command, java.util.Map<String, String> env) {
        streamRun(label, workingDir, command, env, null);
    }

    /** What chose the next launch's package manager ({@code pnpm-lock.yaml}), for a not-found message; else "". */
    private String launchReason = "";

    /** Set by a configuration whose program runs in a Terminal tab; its launch, built later, goes there. */
    private boolean terminalRun;

    /**
     * The command whose runtime the next run's chip shows instead of the run's own: {@code kotlinc} for a Kotlin
     * program run as {@code java -jar}, which reports both its Kotlin and the JDK the program runs on.
     */
    private List<String> runtimeCommand;

    /** As above; {@code script} is the file being run (saved before a Rerun), null for a Java launch. */
    private void streamRun(
            String label, Path workingDir, List<String> command, java.util.Map<String, String> env, Path script) {
        if (terminalRun) {
            terminalRun = false;
            runtimeCommand = null;
            pendingDeploySummary = null; // the console's extras do not apply to a Terminal tab
            pendingOutputWatcher = null;
            pendingRerun = null;
            runInTerminal(label, workingDir, command, env);
            return;
        }
        lastRunScript = script;
        String reason = launchReason;
        launchReason = "";
        TestStream tests = pendingTestStream;
        pendingTestStream = null;
        java.util.function.BiConsumer<String, Boolean> watcher = pendingOutputWatcher;
        pendingOutputWatcher = null;
        String deploySummary = pendingDeploySummary;
        pendingDeploySummary = null;
        lastConfigRerun = pendingRerun;
        pendingRerun = null;
        com.editora.deploy.DeployPaths deployedPaths = deployed == null ? null : deployed.paths();
        lastTestRerun = null;
        stopRequested = false;
        lastRunDir = workingDir;
        // A deployed program's files are the project's: its console links open those, not the host's copies.
        Path localDir = deployedPaths == null ? null : deployedPaths.toLocal(workingDir);
        consoleDir = localDir != null ? localDir : workingDir;
        lastRunLabel = label;
        lastRunCommand = command;
        lastRunEnv = env;
        consoleUsed = true;
        ops.openToolWindow();
        panel().started(label);
        if (deploySummary != null) {
            panel().showCommandLine(deploySummary);
        }
        List<String> runtime = runtimeCommand != null ? runtimeCommand : command;
        runtimeCommand = null;
        showRuntime(runtime, workingDir, env);
        host.setStatus(tr("status.run.started", label));
        service.runInDir(workingDir, command, env, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                panel().started(commandLine);
                if (deploySummary != null) {
                    panel().showCommandLine(deploySummary); // started() cleared it: the deploy comes first
                }
                if (script != null || tests != null) {
                    // Which interpreter ran, and how: the first thing to check when a script behaves oddly.
                    panel().showCommandLine(commandLine);
                }
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                panel().appendOutput(line, stderr);
                if (tests != null) {
                    tests.line(line, stderr);
                }
                if (watcher != null) {
                    watcher.accept(line, stderr);
                }
            }

            @Override
            public void onPartialOutput(String text, boolean stderr) {
                panel().appendPartialOutput(text, stderr);
            }

            @Override
            public void onExit(int code) {
                closeDebugTunnel();
                if (tests != null) {
                    tests.exit(stopRequested ? -1 : code);
                }
                if (stopRequested) {
                    // A stopped run's exit code is the signal's (143 for TERM): report the Stop, not a "finish".
                    stopRequested = false;
                    panel().stopped();
                    host.setStatus(tr("status.run.stopped"));
                } else {
                    panel().finished(code);
                    host.setStatus(code == 0 ? tr("status.run.ok") : tr("status.run.exit", code));
                }
                ops.onRunStateChanged();
            }

            @Override
            public void onError(String message) {
                closeDebugTunnel();
                if (tests != null) {
                    tests.exit(-1);
                }
                // "node was not found — install it or set it in Settings", not the JDK's ENOENT text.
                String friendly = NpmRunSupport.missingMessage(command.get(0), message, reason);
                runtimeGeneration++; // nothing ran: no runtime chip (an npm probe would name the Node beside it)
                panel().setRuntime(null);
                panel().failed(friendly != null ? friendly : message);
                host.setStatus(friendly != null ? friendly : tr("status.run.failed", message));
                ops.onRunStateChanged();
            }
        });
        // After the call, not inside onStart: RunService publishes the process before it notifies, so by here
        // isRunning() is true whether the launch succeeded or failed back through onError above.
        ops.onRunStateChanged();
    }

    /**
     * Runs a configuration's program in a Terminal tab. The console's crutches stay behind: the {@code read -p}
     * prompt shim (a terminal is a tty, so bash prompts by itself). Rerun is left to the tab: it does not replay
     * an older console run.
     */
    private void runInTerminal(String label, Path workingDir, List<String> command, java.util.Map<String, String> env) {
        java.util.Map<String, String> own = new java.util.LinkedHashMap<>(env);
        ReadPromptShim.environment(System.getenv()).forEach((k, v) -> own.remove(k, v));
        lastRunCommand = null;
        try {
            com.editora.terminal.ProgramRun[] started = new com.editora.terminal.ProgramRun[1];
            started[0] = ops.runInTerminal(label, workingDir, command, own, code -> {
                // A stopped program's exit code is the signal's: report the Stop, as the console does.
                boolean stopped = code < 0 || (terminalStopRequested && terminalProgram == started[0]);
                host.setStatus(
                        code == 0 && !stopped
                                ? tr("status.run.ok")
                                : stopped ? tr("status.run.stopped") : tr("status.run.exit", code));
                if (terminalProgram == started[0]) {
                    terminalProgram = null;
                    terminalStopRequested = false;
                }
                ops.onRunStateChanged();
            });
            terminalProgram = started[0];
            terminalStopRequested = false;
            host.setStatus(tr("status.run.inTerminal", label));
            ops.onRunStateChanged();
        } catch (java.io.IOException | RuntimeException e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            host.setStatus(tr("status.run.failed", message));
        }
    }

    /**
     * Clears the Run window's runtime chip, then fills it in with what {@code command}'s runtime reports, run
     * the way the launch runs it (its folder, so its host, and its environment). An answer that arrives after
     * a newer run started is dropped.
     */
    private void showRuntime(List<String> command, Path workingDir, java.util.Map<String, String> env) {
        long generation = ++runtimeGeneration;
        panel().setRuntime(null);
        com.editora.run.RuntimeProbe.probe(command, workingDir, env, version -> {
            if (generation == runtimeGeneration) {
                panel().setRuntime(version);
            }
        });
    }

    /** Stops the currently running program (Run tool window Stop button / {@code run.stop} command). */
    void stopRun() {
        if (deploying) {
            ops.cancelDeploy(); // after the file being sent; the launch it gated never happens
            host.setStatus(tr("status.deploy.stopping"));
            return;
        }
        if (beforeLaunch.isActive()) {
            beforeLaunch.stop(); // its exit reports "stopped" and the launch it gated never happens
            return;
        }
        if (service.isRunning()) {
            stopRequested = true;
            service.stop();
            host.setStatus(tr("status.run.stopped"));
        } else if (terminalProgramRunning()) {
            terminalStopRequested = true;
            terminalProgram.stop(); // its tab stays; its end reports the stop
        }
    }

    /** {@code run.endInput}: ends the running program's standard input (end of file). */
    void endInput() {
        if (service.isRunning()) {
            panel().endInput();
        } else {
            host.setStatus(tr("status.run.noInputToEnd"));
        }
    }

    void clearConsole() {
        panel().clearConsole();
    }

    void shutdown() {
        service.shutdown();
    }
}
