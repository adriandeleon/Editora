package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.RunConfiguration;
import com.editora.editor.EditorBuffer;
import com.editora.editor.MenuChords;
import com.editora.run.BuildBeforeLaunch;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.JavaRunCommand;
import com.editora.run.JdkToolchain;
import com.editora.run.ProgramArgs;
import com.editora.run.ReadPromptShim;
import com.editora.run.RunConfigRouting;
import com.editora.run.RunConfigWorkingDirectory;
import com.editora.run.RunService;
import com.editora.run.RunTrust;
import com.editora.run.ScriptRunCommand;
import com.editora.run.ShellCommand;
import com.editora.run.StackTraceLinks;
import org.jspecify.annotations.Nullable;

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
                @Nullable String title,
                @Nullable Path dir,
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

        /** A test stand-in's save (dirty → write, untitled → Save-As), answered at once; the app uses saveThen. */
        default boolean saveBuffer(EditorBuffer buffer) {
            return false;
        }

        /**
         * Saves {@code buffer} as {@link #saveBuffer} does, without waiting: {@code then} hears on the FX thread
         * whether it was saved. The app writes off the FX thread and never waits in a nested loop (PERF-01).
         */
        default void saveThen(EditorBuffer buffer, Consumer<Boolean> then) {
            then.accept(saveBuffer(buffer));
        }

        /** The remembered program-arguments string for {@code path} ("" when none). */
        String programArgs(@Nullable Path path);

        /** Persists the program-arguments string for {@code path} (workspace state + durable save). */
        void setProgramArgs(Path path, String args);

        /** A stack-trace location double-clicked in the console: resolve + jump (shared resolver). */
        void openLink(StackTraceLinks.Link link);

        /** An HTTP(S) URL single-clicked in program output: open it in the system browser. */
        default void openUrl(String url) {}

        /** The nearest Maven/Gradle project root above {@code file}, or {@code null} if there is none. */
        @Nullable
        Path javaProjectRoot(Path file);

        /** This window's open project root, or {@code null} when it has no project. */
        @Nullable
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

        /** Looks, on this (a plan's) thread, at what {@link #buildExecutable} asks of the disk for {@code root}. */
        default void warmBuildExecutable(com.editora.build.BuildTool tool, Path root) {}

        /**
         * What {@link #buildExecutable} asks of the disk for {@code tool} at {@code root}, asked and nothing else:
         * looked at off the FX thread, checked inside {@code Looked.allKnown} on it.
         */
        default void buildExecutableFacts(com.editora.build.BuildTool tool, Path root) {}

        /** Resolves the Maven module's launch classpath off-thread ({@code mvn compile
         *  dependency:build-classpath} + {@code target/classes}); delivers null/empty on failure. FX thread. */
        void resolveMavenClasspath(Path root, Consumer<List<String>> cb);

        /** Same resolution under a selected JDK. The default preserves test/alternate hosts. */
        default void resolveMavenClasspath(Path root, String jdkHome, Consumer<List<String>> cb) {
            resolveMavenClasspath(root, cb);
        }

        /**
         * Same resolution with {@code executable} (the tasks tree's argv prefix for Maven) and the launch's
         * {@code scope} — a project elsewhere (a host) resolves this way; here the Run console runs the
         * resolution as a streamed step instead.
         */
        default void resolveMavenClasspath(
                Path root,
                String jdkHome,
                List<String> executable,
                com.editora.maven.MavenClasspath.Scope scope,
                Consumer<List<String>> cb) {
            resolveMavenClasspath(root, jdkHome, cb);
        }

        /**
         * The Maven tasks tree's active toggle args (profiles, offline, the reactor switch) for its detected
         * root, as it would add them to a task; empty with no tree. A launch's Maven commands carry the
         * profiles and flags among them (review D13).
         */
        default List<String> mavenToggleArgs() {
            return List.of();
        }

        /**
         * The argv prefix a launch may start {@code tool} with from {@code root}: what the tasks tree would
         * run (the project's wrapper, the command override, the PATH command), after the tasks tree's trust
         * gate — or null when the user declined to trust the project's wrapper, in which case the gate has
         * said so and nothing may run. A run that loads a project without going through the tool's window (a
         * Clojure file in a Leiningen project, a Scala CLI project, an npm script) asks it the same way
         * ({@link RunTrust}).
         */
        default @Nullable List<String> buildExecutable(com.editora.build.BuildTool tool, @Nullable Path root) {
            return List.of(tool.commandExample());
        }

        /**
         * Whether a build may run the code of {@code root} — a CMake or Make project, which has no build tool of
         * its own here — after asking, as a build tool's first build does ({@code items}: what will run, one line
         * each); false when the user declined. As {@code DebugCoordinator.Ops#trustBuild}.
         */
        default boolean trustBuild(Path root, List<String> items) {
            return true;
        }

        /** Runs the Gradle Run task via the build tool (streams to Output) — the no-jdtls Gradle
         *  fallback. Runs {@code bootRun} for a Spring Boot project, else {@code run}; {@code root} locates the
         *  build script. */
        void runGradleRunTask(Path root);

        /**
         * Runs {@code taskArgs} with build tool {@code tool} in {@code root} through its tool window (its
         * console, its workspace-trust check for a repository's own launcher). False when that tool is off.
         */
        default boolean runBuildTask(com.editora.build.BuildTool tool, @Nullable Path root, List<String> taskArgs) {
            return false;
        }

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
        default void attachJavaDebugger(@Nullable Path anchor, int port, Runnable stopOwner) {}
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final RunService service = new RunService();
    private final BeforeLaunchStep beforeLaunch = new BeforeLaunchStep(service, true); // the console can answer it
    /** The Run console, built on its first use (a run, or the tool window's first open). */
    private @Nullable RunPanel panel;
    /** The console font to apply when the panel is built (set by every settings apply, panel or not). */
    private @Nullable String outputFontFamily;

    private int outputFontSize;

    /**
     * The most recent launch, for {@code run.rerun}. Assigned only by {@link #streamRun}, all four together:
     * the before-launch console once set {@code lastRunDir} on its own, and Rerun then replayed the previous
     * program in the before-launch step's directory.
     */
    private @Nullable Path lastRunDir;

    /** Where the console's current output came from — a run or a before-launch step — for its file links. */
    private @Nullable Path consoleDir;

    private java.util.Map<String, String> lastRunEnv = java.util.Map.of();
    private @Nullable String lastRunLabel;
    private @Nullable List<String> lastRunCommand;
    /** The script the last run ran (a file, or a script configuration's target); null for a Java launch. */
    private @Nullable Path lastRunScript;
    /** Stop was pressed for the running program, so its exit is reported as stopped, not as an exit code. */
    private boolean stopRequested;

    /**
     * The project root a configuration's launch resolves against while it runs on a deployment: the
     * deployment's folder on its host. Null for every launch on this machine (each one sets it).
     */
    private @Nullable Path launchRoot;

    /** The deployment the current configuration launch runs on, or null. */
    private DeploymentCoordinator.@Nullable Deployed deployed;

    /** A deploy is running before a launch: Stop ends it, and a second Run waits. */
    private boolean deploying;

    /** What the deploy before this launch did: the first line of its console, once. */
    private @Nullable String pendingDeploySummary;

    /** Watches the next program's output (a deployed program's JDWP banner); null for most runs. */
    private java.util.function.@Nullable BiConsumer<String, Boolean> pendingOutputWatcher;

    /**
     * What Rerun repeats for the next program, null for most: a deployed configuration (deploy, then run), a Kotlin
     * program (compile an edited file again).
     */
    private @Nullable Runnable pendingRerun;

    /** {@link #pendingRerun} of the last program. */
    private @Nullable Runnable lastConfigRerun;

    /** The tunnel to a deployed program's debug port, closed when the program ends. */
    private com.editora.process.@Nullable PortForward debugTunnel;

    /** What a test run streamed in this console reports to: its output lines, then its exit. */
    interface TestStream {
        void line(String text, boolean stderr);

        /**
         * How the Run console shows a line: as stderr ({@code true}), as stdout ({@code false}), or not at all
         * ({@code null}) — a node:test run reads TAP on stdout and shows the readable spec report from stderr.
         */
        default @Nullable Boolean console(String text, boolean stderr) {
            return stderr;
        }

        /** The run ended: its exit code, or {@code -1} when it was stopped or could not start. */
        void exit(int code);
    }

    /** The hooks the next {@link #streamRun} hands its output to (set by {@link #runTests}). */
    private @Nullable TestStream pendingTestStream;

    /** How Rerun repeats the last run when it was a test run (which feeds Test Results too); else null. */
    private @Nullable Runnable lastTestRerun;

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
            panel.setUiColours(ConsoleColours.needed(host.settings()));
        }
        return panel;
    }

    /** Re-reads the UI and editor themes for the console's colours (each settings apply; review 3, C205). */
    void refreshConsoleColours() {
        if (panel != null) {
            panel.setUiColours(ConsoleColours.needed(host.settings()));
        }
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
    @Nullable
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
        if (consoleRunning() || deploying) {
            beginRunRequest(); // says the Run is busy
            return;
        }
        // Saved off the FX thread; the rerun goes on when the writes have landed (PERF-01, Pauta 6.0.1).
        saveThen(scriptAndActive(lastRunScript), () -> {
            if (beginRunRequest()) {
                rerunLastSaved();
            }
        });
    }

    private void rerunLastSaved() {
        if (lastRunCommand == null || lastRunDir == null) {
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
        RunTrust.Gate gate = lastRunGate;
        @Nullable String label = lastRunLabel;
        Path dir = java.util.Objects.requireNonNull(lastRunDir); // checked above
        List<String> command = java.util.Objects.requireNonNull(lastRunCommand);
        java.util.Map<String, String> env = lastRunEnv;
        @Nullable Path script = lastRunScript;
        whenTrusted(
                gate,
                () -> { // trust revoked since: the gate asks again, and "no" runs nothing
                    pendingTrustGate = gate;
                    streamRun(label, dir, command, env, script);
                });
    }

    /**
     * Off the FX thread, in a launch's plan: looks at what {@link #trusted} then asks of {@code gate} (the build
     * tool's wrapper and the trust question's summary), so the FX thread answers from memory (PERF-01, Pauta 6.0.1).
     */
    private void warmGate(RunTrust.@Nullable Gate gate) {
        if (gate != null) {
            ops.warmBuildExecutable(gate.tool(), gate.root());
        }
    }

    /**
     * Whether a launch that loads {@code gate}'s project may start: that tool's tasks tree asks for workspace trust
     * as it would before its own build ({@link Ops#buildExecutable}) — a Clojure file's {@code project.clj} or
     * {@code deps.edn}, a Scala CLI project folder, an npm script's hooks run the repository's code, not just the
     * file. True when there is no gate. Declining runs nothing; the gate has said why.
     */
    private boolean trusted(RunTrust.@Nullable Gate gate) {
        return gate == null || ops.buildExecutable(gate.tool(), gate.root()) != null;
    }

    /**
     * Whether everything {@code asks} asks of the disk is known now; if not, it is looked at off the FX thread
     * (the Run busy meanwhile, so a second press starts nothing) and {@code again} runs on the FX thread when it
     * lands. A command never guesses a build fact and never looks at one on the FX thread (PERF-01, Pauta 6.0.1).
     */
    private boolean factsKnown(String name, Runnable asks, Runnable again) {
        if (com.editora.perf.Looked.allKnown(asks)) {
            return true;
        }
        offFx(
                name,
                () -> {
                    asks.run();
                    return Boolean.TRUE;
                },
                done -> again.run());
        return false;
    }

    /** What {@link #trusted} asks of the disk for {@code gate}: its tool's wrapper, trust summary and executable. */
    private Runnable gateFacts(RunTrust.@Nullable Gate gate) {
        return () -> {
            if (gate != null) {
                ops.buildExecutableFacts(gate.tool(), gate.root());
            }
        };
    }

    /** What {@code ops.buildExecutable(tool, root)} asks of the disk. */
    private Runnable executableFacts(com.editora.build.BuildTool tool, @Nullable Path root) {
        return () -> {
            if (root != null) {
                ops.buildExecutableFacts(tool, root);
            }
        };
    }

    /** {@code then} with {@code ops.buildExecutable(tool, root)} once what it asks of the disk is known. */
    private void withExecutable(com.editora.build.BuildTool tool, Path root, Consumer<@Nullable List<String>> then) {
        if (factsKnown("run-executable-look", executableFacts(tool, root), () -> withExecutable(tool, root, then))) {
            then.accept(ops.buildExecutable(tool, root));
        }
    }

    /** {@code then} once {@code gate} is known and trusted ({@link #factsKnown}, then {@link #trusted}). */
    private void whenTrusted(RunTrust.@Nullable Gate gate, Runnable then) {
        if (factsKnown("run-trust-look", gateFacts(gate), () -> whenTrusted(gate, then)) && trusted(gate)) {
            then.run();
        }
    }

    /**
     * Runs a test command (pytest, unittest) in this console: its lines and exit also go to {@code hooks}, and
     * Rerun repeats it through {@code rerun}; {@code started} runs once it has. The script and the tab in front are
     * saved first, off the FX thread (PERF-01, Pauta 6.0.1). Nothing runs, having said why, when another program is
     * running or a save was refused.
     */
    void runTests(
            @Nullable String label,
            @Nullable Path dir,
            List<String> argv,
            java.util.Map<String, String> env,
            @Nullable Path script,
            TestStream hooks,
            Runnable rerun,
            Runnable started) {
        if (consoleRunning() || deploying) {
            beginRunRequest(); // says the Run is busy
            return;
        }
        saveThen(scriptAndActive(script), () -> {
            if (!beginRunRequest()) {
                return;
            }
            pendingTestStream = hooks;
            streamRun(label, dir, argv, env, script);
            lastTestRerun = rerun;
            started.run();
        });
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
    static @Nullable Path routingFor(CoordinatorHost host, RunConfiguration cfg) {
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
        Path root = configRoot();
        Path projectRoot = ops.projectRoot();
        // What the configuration names on the disk is looked at off the FX thread (PERF-01, Pauta 6.0.1): the
        // script, the folder, an env @file, the shebang, the interpreter.
        offFx(
                "run-config-plan",
                () -> {
                    ScriptConfigPlan planned = ScriptConfigPlan.of(cfg, root, projectRoot, cwd, remote, elsewhere);
                    if (planned.npm() != null && cwd != null) {
                        warmGate(RunTrust.npm(cwd)); // the npm gate's question, looked at here
                    }
                    return planned;
                },
                plan -> runScriptConfigPlanned(cfg, args, cwd, remote, elsewhere, plan));
    }

    /** {@link #runScriptConfig} once its plan has been read off the FX thread. */
    private void runScriptConfigPlanned(
            RunConfiguration cfg,
            List<String> args,
            Path cwd,
            boolean remote,
            boolean elsewhere,
            ScriptConfigPlan plan) {
        Path script = plan.script();
        if (plan.dirMissing()) {
            // Checked before the launch, so the message names what is missing: a missing directory used to
            // surface as "Cannot run program /usr/bin/bash … No such file or directory", blaming bash.
            host.setStatus(tr("status.run.workingDirMissing", cfg.name(), cwd.toString()));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (plan.scriptMissing() && script != null) {
            host.setStatus(tr("status.run.scriptMissing", cfg.name(), script.toString()));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (plan.envError() instanceof ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.envUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (plan.envError() instanceof com.editora.run.EnvVars.EnvFileException e) {
            host.setStatus(
                    tr("status.run.envFileUnreadable", cfg.name(), e.file().toString()));
            ops.editConfiguration(cfg.name());
            return;
        }
        java.util.Map<String, String> env = plan.env();
        if (ScriptRunCommand.isPython(cfg.type())) {
            runPythonConfig(cfg, cwd, script, args, env, remote, plan.firstLine());
            return;
        }
        if (ScriptRunCommand.isJvmScript(cfg.type())) {
            runJvmScriptConfig(cfg, cwd, script, args, env, elsewhere);
            return;
        }
        if (ScriptRunCommand.isCompiled(cfg.type())) {
            runCompiledConfig(cfg, cwd, script, args, env, remote || elsewhere);
            return;
        }
        if (ScriptRunCommand.NODE.equals(cfg.type())) {
            if (script == null) {
                reportIncomplete(cfg, "status.run.configNeedsTarget"); // a target that is no valid path
                return;
            }
            String missing = plan.missingInterpreter();
            if (missing != null) {
                // The configuration's own Interpreter names a file that is not there: say so and open the form,
                // rather than "Node.js was not found (node) — set it in Settings" (review C104).
                host.setStatus(tr("status.run.configInterpreterMissing", cfg.name(), missing));
                ops.editConfiguration(cfg.name());
                return;
            }
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
            com.editora.build.NpmPackageManager.Detection detected = plan.npm();
            if (detected == null) {
                return; // not reached: an npm configuration's plan detects its manager
            }
            String override = host.settings().getNpmCommand();
            List<String> manager = com.editora.build.BuildExecutable.resolve(
                    List.of(),
                    com.editora.build.NpmPackageManager.overrideApplies(override, detected.manager()) ? override : "",
                    detected.manager());
            // A package script runs its pre/post hooks under the project's own package manager: npm's trust gate.
            RunTrust.Gate gate = RunTrust.npm(cwd);
            // On the Node.js Run File would pick here, as the Package Scripts window runs it (review C101).
            whenTrusted(
                    gate,
                    () -> NpmRunSupport.prepare(
                            cwd,
                            ops.projectRoot(),
                            host.settings().getNodePath(),
                            ScriptRunCommand.npm(manager, cfg.target(), args),
                            npm -> {
                                java.util.Map<String, String> npmEnv = new java.util.LinkedHashMap<>(npm.env());
                                if (env.containsKey("NO_COLOR")) {
                                    npmEnv.remove("FORCE_COLOR"); // the configuration asks for no colour
                                }
                                npmEnv.putAll(env);
                                launchReason = detected.reason(); // "pnpm-lock.yaml": what a not-found message names
                                runtimeCommand = npm.probe();
                                pendingRuntimeSource = NodeRunLauncher.source(npm.node());
                                addConsoleNotice(NodeRunLauncher.pinNotice(npm.node()));
                                pendingCommandLine = true; // which manager ran: pnpm, yarn, the npm beside the Node
                                pendingTrustGate = gate;
                                streamRun(cfg.name(), cwd, npm.argv(), npmEnv, script);
                            }));
            return;
        }
        if (!ScriptRunCommand.SHELL.equals(cfg.type())) {
            streamRun(cfg.name(), cwd, ScriptRunCommand.build(cfg.type(), cfg.target(), args), env, script);
            return;
        }
        // A shell script runs under what its shebang asks for, unless the configuration names an interpreter; the
        // plan read its first line and looked for the interpreter and the shebang's folder.
        {
            if (plan.shellError() instanceof ProgramArgs.UnclosedQuoteException e) {
                host.setStatus(tr("status.run.optionsUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
                ops.editConfiguration(cfg.name());
                return;
            }
            ShellCommand.Launch shell = plan.shell();
            if (shell == null) {
                return; // not reached: a shell configuration's plan has its launch or its error
            }
            Path dir = plan.shebangDir();
            if (dir == null) {
                host.setStatus(tr("status.run.shebangDirMissing", String.valueOf(plan.missingShebangDir())));
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
        }
    }

    /**
     * A Python script or module configuration: the interpreter is planned like Run File's (its Interpreter and
     * Interpreter options, else the project's), with the configuration's variables on top.
     */
    private void runPythonConfig(
            RunConfiguration cfg,
            Path cwd,
            @Nullable Path script,
            List<String> args,
            java.util.Map<String, String> env,
            boolean remote,
            @Nullable String firstLine) {
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
                module || remote ? null : firstLine,
                cfg.interpreter(),
                options,
                ops.pythonInterpreterChoice(),
                PythonRunLauncher.setting(cwd, host.settings()),
                env);
        PythonRunLauncher.plan(request, cwd, plan -> streamPython(cfg.name(), cwd, plan, script));
    }

    /** The first line of {@code script} (at most 512 bytes), or null when it cannot be read. */
    static @Nullable String firstLine(@Nullable Path script) {
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
    private static java.util.function.@Nullable Predicate<String> existsHere(boolean remote) {
        return remote ? null : p -> java.nio.file.Files.isExecutable(Path.of(p));
    }

    /**
     * Runs a Groovy, Kotlin, Clojure or Scala script configuration: its tool on the configuration's JDK (else
     * the Settings one), with the configuration's VM arguments where the tool hands them to its JVM
     * ({@code JAVA_OPTS} for Groovy and Kotlin, {@code -J} arguments for the Clojure CLI, {@code JVM_OPTS} for
     * Leiningen, {@code --java-opt} for Scala CLI). A Kotlin program ({@code .kt}) is compiled and run by
     * {@code java} instead, its VM arguments on that command. A Clojure file runs in its project's folder when the
     * configuration names none.
     */
    private void runJvmScriptConfig(
            RunConfiguration cfg,
            Path cwd,
            @Nullable Path script,
            List<String> args,
            java.util.Map<String, String> env,
            boolean elsewhere) {
        String language = cfg.type();
        String jdkHome = !cfg.jdkHome().isBlank()
                ? cfg.jdkHome()
                : elsewhere ? remoteJdkHome(cwd) : host.projectScope().jdkHome(host.settings());
        // The script's head (for the trust question), the tool and the project the launch is planned by are read
        // off the FX thread (PERF-01, Pauta 6.0.1); the question and the launch are made back on it.
        offFx(
                "run-jvm-script-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("JvmScriptRun.plan");
                    // Leiningen, the Clojure CLI and Babashka load the project in the folder they run in: asked
                    // first, before anything runs, so "no" runs nothing at all.
                    RunTrust.Gate gate = com.editora.run.JvmScriptRun.trustGate(
                            language, script, cfg.workingDir().isBlank() && script != null ? null : cwd);
                    warmGate(gate);
                    String toolName = script == null
                            ? com.editora.run.JvmScriptRun.tool(language)
                            : com.editora.run.JvmScriptRun.tool(language, script);
                    String tool = elsewhere || toolName == null ? toolName : com.editora.run.JvmTools.locate(toolName);
                    java.util.Map<String, String> launch = new java.util.LinkedHashMap<>(
                            elsewhere
                                    ? remoteJdkEnvironment(jdkHome)
                                    : JdkToolchain.environment(jdkHome, processPath()));
                    launch.putAll(env);
                    String target = script != null
                            ? com.editora.process.ExecTargets.forPath(script).toTargetPath(script)
                            : cfg.target().strip();
                    boolean kotlinProgram =
                            com.editora.run.KotlinRun.shape(target) == com.editora.run.KotlinRun.Shape.PROGRAM
                                    && ScriptRunCommand.KOTLIN.equals(language);
                    com.editora.run.JvmScriptRun.Launch how = tool == null || kotlinProgram
                            ? null
                            : com.editora.run.JvmScriptRun.launch(
                                    language,
                                    toolName,
                                    tool,
                                    script,
                                    target,
                                    args,
                                    cfg.vmArgs(),
                                    false,
                                    launch,
                                    elsewhere ? java.util.Map.of() : System.getenv());
                    return new JvmScriptPlan(gate, toolName, tool, kotlinProgram, how, launch);
                },
                plan -> whenTrusted(plan.gate(), () -> {
                    String tool = plan.tool();
                    if (tool == null) {
                        host.setStatus(tr(com.editora.run.JvmScriptRun.missingToolKeyFor(plan.toolName())));
                        return;
                    }
                    if (plan.kotlinProgram()) {
                        if (elsewhere || script == null) {
                            host.setStatus(tr("status.run.kotlinProgramRemote"));
                            return;
                        }
                        java.util.Map<String, String> programEnv = plan.env(); // the JDK's, then the config's
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
                    com.editora.run.JvmScriptRun.Launch how = plan.how();
                    if (how == null) {
                        return; // not reached: a tool that is there has its launch
                    }
                    Path dir = cfg.workingDir().isBlank() && how.directory() != null ? how.directory() : cwd;
                    pendingTrustGate = plan.gate();
                    streamRun(cfg.name(), dir, how.argv(), how.env(), script);
                }));
    }

    /** What a JVM-script configuration's Run read off the FX thread: its trust gate, its tool and its launch. */
    private record JvmScriptPlan(
            RunTrust.@Nullable Gate gate,
            @Nullable String toolName,
            @Nullable String tool,
            boolean kotlinProgram,
            com.editora.run.JvmScriptRun.@Nullable Launch how,
            java.util.Map<String, String> env) {}

    /**
     * Runs Kotlin program {@code source}: compiled by {@code kotlinc} into a jar cached by its content (in the Run
     * console, as a before-launch step the user can watch and stop), then {@code java -jar} — or straight to
     * {@code java -jar} when this content's jar is already there. The runtime chip shows the compiler's Kotlin
     * and the JDK both run on.
     */
    private void runKotlinProgram(
            String label,
            Path source,
            @Nullable Path cwd,
            String javaExecutable,
            List<String> jvmOptions,
            List<String> args,
            java.util.Map<String, String> env,
            String kotlinc) {
        // The source is read and hashed, and the jar cache looked at, off the FX thread (PERF-01, Pauta 6.0.1).
        Path configDir = host.configDir();
        offFx(
                "kotlin-run-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("KotlinBuild.plan");
                    return com.editora.run.KotlinBuild.plan(source, kotlinc, configDir);
                },
                plan -> runKotlinPlan(label, source, cwd, javaExecutable, jvmOptions, args, env, kotlinc, plan));
    }

    /** {@link #runKotlinProgram} once its plan is known: compiles when it must, then runs the jar. */
    private void runKotlinPlan(
            String label,
            Path source,
            @Nullable Path cwd,
            String javaExecutable,
            List<String> jvmOptions,
            List<String> args,
            java.util.Map<String, String> env,
            String kotlinc,
            com.editora.run.KotlinBuild.Plan plan) {
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
                compileConsole(source.getParent(), true), // kotlinc's error lines name the file: links resolve
                () -> offFx(
                        "kotlin-run-finish",
                        () -> {
                            com.editora.perf.BlockingSites.enter("KotlinBuild.finish");
                            com.editora.run.KotlinBuild.finish(plan);
                            return plan;
                        },
                        done -> launch.run()));
        ops.onRunStateChanged(); // the compile is running now: the toolbar's Stop button applies to it
    }

    /**
     * Runs {@code work} — the disk work a Run plans its program by — on a virtual thread called {@code thread}
     * (PERF-01, Pauta 6.0.1), then {@code then} with its answer on the FX thread. Meanwhile the console counts as
     * running (a second Run is refused, Stop drops the answer); a failure is said in the status line, as a failed
     * plan was.
     */
    private <T> void offFx(String thread, java.util.concurrent.Callable<T> work, Consumer<T> then) {
        locatingProgram = true;
        long generation = ++locateGeneration;
        ops.onRunStateChanged();
        planner.accept(thread, () -> {
            Object[] result = new Object[2];
            com.editora.perf.Looked.asOffFxWork(() -> {
                try {
                    result[0] = work.call();
                } catch (Exception e) {
                    result[1] = e;
                }
            });
            @SuppressWarnings("unchecked")
            T answer = (T) result[0];
            Exception failed = (Exception) result[1];
            Runnable land = () -> {
                if (generation != locateGeneration || !locatingProgram) {
                    return; // stopped meanwhile
                }
                locatingProgram = false;
                ops.onRunStateChanged();
                if (failed != null || answer == null) {
                    host.setStatus(tr("status.run.failed", failed == null ? "" : String.valueOf(failed.getMessage())));
                    return;
                }
                then.accept(answer);
            };
            onFxThread(land);
        });
    }

    /** {@code action} now on the FX thread (a plan made in place, a test's planner), else later on it. */
    private static void onFxThread(Runnable action) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            action.run();
        } else {
            javafx.application.Platform.runLater(action);
        }
    }

    /**
     * Where a Run's plan is read: a virtual thread named for it. TEST SEAM — a test that drives the coordinator
     * directly may plan in place ({@code (name, task) -> task.run()}), and then the plan lands at once.
     */
    java.util.function.BiConsumer<String, Runnable> planner =
            (name, task) -> Thread.ofVirtual().name(name).start(task);

    /**
     * The Run console as a compile before a program shows itself in it: its command line and output, and how it
     * ended only when it failed — the program follows a compile that worked. {@code dir} is where its error lines'
     * file names resolve; not {@code first}: a later step of the same build, which keeps what the one before wrote.
     */
    private BeforeLaunchStep.Console compileConsole(@Nullable Path dir, boolean first) {
        return new BeforeLaunchStep.Console() {
            @Override
            public void started(String commandLine) {
                consoleDir = dir;
                consoleUsed = true;
                ops.openToolWindow();
                if (first) {
                    panel().started(commandLine);
                } else {
                    panel().appendOutput("$ " + commandLine, false);
                }
                runtimeGeneration++; // the compile is not the program: no runtime chip for it
                panel().setRuntime(null);
            }

            @Override
            public void output(String line, boolean stderr) {
                panel().appendOutput(line, stderr);
            }

            @Override
            public void ended(int code, @Nullable String launchError) {
                if (launchError != null) {
                    panel().failed(launchError);
                } else if (code != 0) {
                    panel().finished(code);
                }
                ops.onRunStateChanged();
            }
        };
    }

    /**
     * A C, C++, Go or Rust configuration's launch.
     *
     * @param label the configuration's name
     * @param source its source file
     * @param args program arguments
     * @param env the configuration's variables, for the program
     * @param dir its own working directory, or null for where Debug File runs the program
     * @param again the whole launch once more: Rerun builds an edited file again
     */
    private record CompiledRun(
            String label,
            Path source,
            List<String> args,
            java.util.Map<String, String> env,
            @Nullable Path dir,
            Runnable again) {}

    /** A built program found for a {@link CompiledRun}, or why there is none ({@code problem}, said as it is). */
    private record Built(
            @Nullable Path program,
            List<String> firstArgs,
            @Nullable String problem) {
        static Built of(@Nullable Path program, String missing) {
            return new Built(program, List.of(), program == null ? missing : null);
        }
    }

    /**
     * Runs a C, C++, Go or Rust configuration: what Debug File builds for its source file is built in the Run
     * console — a C or C++ file's CMake or Make project, or the file alone; a Go file's package, or the file alone;
     * a Rust file's Cargo package — and the program that makes runs there with the configuration's arguments and
     * variables, in its working directory when it names one. Run had no way to start these: only Debug did. On
     * this machine only: the program is built into this machine's cache.
     */
    private void runCompiledConfig(
            RunConfiguration cfg,
            Path cwd,
            @Nullable Path source,
            List<String> args,
            java.util.Map<String, String> env,
            boolean elsewhere) {
        if (source == null) {
            reportIncomplete(cfg, "status.run.configNeedsTarget"); // a target that is no valid path
            return;
        }
        if (elsewhere) {
            host.setStatus(tr("status.run.compiledRemote", cfg.name()));
            return;
        }
        boolean inTerminal = terminalRun;
        CompiledRun run = new CompiledRun(
                cfg.name(), source.toAbsolutePath(), args, env, cfg.workingDir().isBlank() ? null : cwd, () -> {
                    terminalRun = inTerminal;
                    runCompiledConfig(cfg, cwd, source, args, env, false);
                });
        if (ScriptRunCommand.GO.equals(cfg.type())) {
            runGo(run);
        } else if (ScriptRunCommand.RUST.equals(cfg.type())) {
            runRust(run);
        } else {
            runNative(run, ScriptRunCommand.CPP.equals(cfg.type()));
        }
    }

    /** A C or C++ file: {@link com.editora.dap.NativeBuild}'s plan for it, then the program that made. */
    private void runNative(CompiledRun run, boolean cpp) {
        Path source = run.source();
        Path programs = builtProgramsDir();
        // The project's build files are looked for off the FX thread (PERF-01, Pauta 6.0.1).
        offFx(
                "run-native-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("NativeBuild.plan");
                    Path cmake = com.editora.dap.NativeBuild.cmakeRoot(source);
                    Path make = cmake == null ? com.editora.dap.NativeBuild.makeRoot(source) : null;
                    return java.util.Optional.ofNullable(
                            com.editora.dap.NativeBuild.plan(source, cpp, cmake, make, programs));
                },
                planned -> runNativePlanned(run, source, planned.orElse(null)));
    }

    private void runNativePlanned(CompiledRun run, Path source, com.editora.dap.NativeBuild.@Nullable Plan plan) {
        if (plan == null) {
            host.setStatus(tr("status.run.compiledHeaderAlone", source.getFileName()));
            return;
        }
        if (plan.tool() != com.editora.dap.NativeBuild.Tool.COMPILER) {
            // A CMake or Make project's build files are code: asked first, as Debug asks, so "no" builds nothing.
            boolean isCmake = plan.tool() == com.editora.dap.NativeBuild.Tool.CMAKE;
            String item = tr(
                    isCmake ? "dialog.trust.item.cmake" : "dialog.trust.item.make",
                    isCmake ? "CMakeLists.txt" : "Makefile");
            if (!ops.trustBuild(plan.root(), List.of(item))) {
                return;
            }
        }
        // Saved off the FX thread first; the build goes on once every write has landed (PERF-01).
        saveBuffersUnderThen(plan.root(), List.of("c", "cpp"), () -> {
            String missing = tr("status.run.compiledNoProgram", source.getFileName());
            offFx(
                    "run-native-prepare",
                    () -> {
                        com.editora.perf.BlockingSites.enter("NativeBuild.requestCodemodel");
                        if (plan.buildDir() != null) {
                            com.editora.dap.NativeBuild.requestCodemodel(plan.buildDir());
                        }
                        if (plan.output() != null && plan.output().getParent() != null) {
                            java.nio.file.Files.createDirectories(plan.output().getParent());
                        }
                        return plan;
                    },
                    ready -> buildThenRun(
                            run,
                            plan.root(),
                            plan.steps(),
                            "status.run.compiledBuild",
                            () -> Built.of(com.editora.dap.NativeBuild.executable(plan, source), missing),
                            plan.root()));
        });
    }

    /**
     * A Go file: {@code go} builds its package (in a module, after the module's trust as a Go task asks it), the
     * file alone outside one, or a test file's package tests ({@link com.editora.dap.GoLaunch#buildArgv}), and that
     * program runs in the file's folder. A file of another package than {@code main} has no program: {@code go
     * build -o} writes its archive and exits 0, so that is said here, before anything is built.
     */
    private void runGo(CompiledRun run) {
        Path source = run.source();
        Path folder = source.getParent();
        if (folder == null) {
            return; // not reached: the source is absolute
        }
        // The package clause and the module are read off the FX thread (PERF-01, Pauta 6.0.1).
        offFx(
                "run-go-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("GoLaunch.plan");
                    String declared = null;
                    if (!com.editora.dap.GoLaunch.isTestFile(source)) {
                        try {
                            // (On disk it is what is on screen: the launch saved the file's own tab first.)
                            declared = com.editora.dap.GoLaunch.packageName(java.nio.file.Files.readString(source));
                        } catch (java.io.IOException e) {
                            declared = null; // unreadable: go says so
                        }
                    }
                    Path module = com.editora.dap.GoLaunch.moduleRoot(source);
                    if (module != null) {
                        ops.buildExecutableFacts(com.editora.build.BuildTool.GO, module);
                    }
                    return new GoPlan(declared, module);
                },
                plan -> runGoPlanned(run, source, folder, plan));
    }

    /** What a Go Run read off the FX thread: the file's package clause and its module's root. */
    private record GoPlan(
            @Nullable String declared, @Nullable Path module) {}

    private void runGoPlanned(CompiledRun run, Path source, Path folder, GoPlan planned) {
        String declared = planned.declared();
        if (declared != null && !"main".equals(declared)) {
            host.setStatus(tr("status.run.goNotMain", source.getFileName(), declared));
            ops.editConfiguration(run.label()); // to the Source file field: another file is what would run
            return;
        }
        Path module = planned.module();
        if (module != null
                && !factsKnown(
                        "run-go-trust-look",
                        executableFacts(com.editora.build.BuildTool.GO, module),
                        () -> runGoPlanned(run, source, folder, planned))) {
            return;
        }
        List<String> go = module != null
                ? ops.buildExecutable(com.editora.build.BuildTool.GO, module)
                : com.editora.build.BuildExecutable.resolve(
                        List.of(), host.settings().getGoCommand(), "go");
        if (go == null) {
            return; // the module was not trusted: nothing is built
        }
        // Saved off the FX thread first; the build goes on once every write has landed (PERF-01).
        saveBuffersUnderThen(module != null ? module : folder, List.of("go"), () -> {
            Path programs = builtProgramsDir();
            Path output = com.editora.dap.NativeBuild.outputFor(source, programs);
            String missing = tr("status.run.compiledNoProgram", source.getFileName());
            offFx(
                    "run-go-prepare",
                    () -> {
                        com.editora.perf.BlockingSites.enter("GoLaunch.prepare");
                        java.nio.file.Files.createDirectories(programs);
                        java.nio.file.Files.deleteIfExists(output); // the last build's program is not this one's
                        return output;
                    },
                    ready -> buildThenRun(
                            run,
                            folder,
                            List.of(com.editora.dap.GoLaunch.buildArgv(go, source, module != null, output)),
                            "status.run.compiledBuild",
                            // A program, not a package's archive (what go writes for a package whose clause was not
                            // read).
                            () -> Built.of(
                                    java.nio.file.Files.isRegularFile(output)
                                                    && java.nio.file.Files.isExecutable(output)
                                            ? output
                                            : null,
                                    missing),
                            folder));
        });
    }

    /**
     * A Rust file: Cargo builds its package (after the package's trust, as a Cargo task asks it), Cargo's JSON
     * messages say which executable is the file's ({@link com.editora.dap.RustLaunch}), and that runs in the
     * package's folder — the program, or for a test file its test binary.
     */
    private void runRust(CompiledRun run) {
        Path source = run.source();
        // The package, then the file and the package's programs, are read off the FX thread (PERF-01, Pauta 6.0.1).
        offFx(
                "run-rust-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("RustLaunch.packageRoot");
                    Path found = com.editora.dap.RustLaunch.packageRoot(source);
                    if (found != null) {
                        ops.buildExecutableFacts(com.editora.build.BuildTool.CARGO, found);
                    }
                    return java.util.Optional.ofNullable(found);
                },
                found -> {
                    Path root = found.orElse(null);
                    if (root == null) {
                        host.setStatus(tr("status.run.rustNoPackage", source.getFileName()));
                        return;
                    }
                    withExecutable(com.editora.build.BuildTool.CARGO, root, cargo -> {
                        if (cargo != null) { // else the package was not trusted: nothing is built
                            runRustTrusted(run, source, root, cargo);
                        }
                    });
                });
    }

    private void runRustTrusted(CompiledRun run, Path source, Path root, List<String> cargo) {
        {
            // Saved off the FX thread first; the build goes on once every write has landed (PERF-01).
            saveBuffersUnderThen(root, List.of("rust"), () -> {
                offFx(
                        "run-rust-plan",
                        () -> {
                            com.editora.perf.BlockingSites.enter("RustLaunch.plan");
                            String text;
                            try {
                                text = java.nio.file.Files.readString(source);
                            } catch (java.io.IOException e) {
                                text = ""; // unreadable: Cargo says so
                            }
                            boolean hasProgram = java.nio.file.Files.isRegularFile(
                                            root.resolve("src").resolve("main.rs"))
                                    || java.nio.file.Files.isDirectory(
                                            root.resolve("src").resolve("bin"));
                            // No caret picks a test: a test file runs all its tests.
                            return com.editora.dap.RustLaunch.plan(root, source, text, -1, hasProgram);
                        },
                        plan -> runRustPlanned(run, source, root, cargo, plan));
            });
        }
    }

    private void runRustPlanned(
            CompiledRun run, Path source, Path root, List<String> cargo, com.editora.dap.RustLaunch.Plan plan) {
        List<String> build = new ArrayList<>(cargo);
        build.addAll(plan.buildArgs());
        List<String> json = new ArrayList<>(cargo);
        json.addAll(plan.jsonArgs());
        String missing = tr("status.run.compiledNoProgram", source.getFileName());
        buildThenRun(
                run,
                root,
                List.of(build),
                "status.run.cargoBuild",
                () -> {
                    // Nothing is left to build: Cargo only says where each executable went.
                    var listed = com.editora.dap.RustLaunch.listArtifacts(root, json, java.time.Duration.ofMinutes(2));
                    if (!listed.ok()) {
                        String why = listed.err().strip();
                        return new Built(
                                null,
                                List.of(),
                                tr(
                                        "status.debug.rustBuildFailed",
                                        why.isEmpty()
                                                ? "exit " + listed.exit()
                                                : why.lines()
                                                        .reduce((a, z) -> z)
                                                        .orElse(why)));
                    }
                    com.editora.dap.RustLaunch.Artifact artifact = com.editora.dap.RustLaunch.pick(
                            com.editora.dap.RustLaunch.artifacts(listed.out()), source, plan.mode());
                    return artifact == null
                            ? new Built(null, List.of(), missing)
                            : new Built(artifact.executable(), plan.testArgs(), null);
                },
                root);
    }

    /** Where a lone C, C++ or Go file's program is built for Run: the config folder's cache, else the temporary folder. */
    private Path builtProgramsDir() {
        Path config = host.configDir();
        return config != null
                ? config.resolve("cache").resolve("native-run")
                : Path.of(System.getProperty("java.io.tmpdir"), "editora-native-run");
    }

    /** The buffer a command just saved before calling itself again: it goes on even if the tab is still marked. */
    private @Nullable EditorBuffer justSaved;

    private @Nullable EditorBuffer takeJustSaved() {
        EditorBuffer saved = justSaved;
        justSaved = null;
        return saved;
    }

    /**
     * Saves those of {@code buffers} that are edited or untitled, one after another, then runs {@code then}: each
     * write runs off the FX thread and the command goes on when it has landed, never in a nested wait (PERF-01,
     * Pauta 6.0.1). Nothing more runs when a save is refused, cancelled or fails.
     */
    private void saveThen(List<EditorBuffer> buffers, Runnable then) {
        for (int i = 0; i < buffers.size(); i++) {
            EditorBuffer b = buffers.get(i);
            if (b.isDirty() || b.getPath() == null) {
                List<EditorBuffer> rest = List.copyOf(buffers.subList(i + 1, buffers.size()));
                ops.saveThen(b, saved -> {
                    if (saved) {
                        saveThen(rest, then);
                    }
                });
                return;
            }
        }
        then.run();
    }

    /**
     * Saves the edited buffers of {@code languages} under {@code root} (a build reads them from disk), then runs
     * {@code then}; nothing when a save is refused.
     */
    private void saveBuffersUnderThen(Path root, List<String> languages, Runnable then) {
        List<EditorBuffer> dirty = new ArrayList<>();
        host.forEachBuffer(b -> {
            Path p = b.getPath();
            if (b.isDirty()
                    && p != null
                    && languages.contains(b.getLanguage())
                    && p.toAbsolutePath().startsWith(root)) {
                dirty.add(b);
            }
        });
        saveThen(dirty, then);
    }

    /** Set while a built program is being looked for off the FX thread: the console is busy, and Stop ends it. */
    private boolean locatingProgram;

    /** Bumped by each search for a built program, and by Stop: a search that was stopped launches nothing. */
    private long locateGeneration;

    /**
     * Runs {@code steps} in {@code root} one after another in the Run console (a failed or stopped one ends it),
     * asks {@code find} off the FX thread for the program they made, and runs it — in the run's own folder, else
     * {@code defaultDir}.
     */
    private void buildThenRun(
            CompiledRun run,
            Path root,
            List<List<String>> steps,
            String statusKey,
            java.util.function.Supplier<Built> find,
            Path defaultDir) {
        runBuildSteps(run, root, steps, 0, statusKey, () -> {
            locatingProgram = true;
            long generation = ++locateGeneration;
            ops.onRunStateChanged();
            Thread.ofVirtual().name("run-built-program").start(() -> {
                Built found;
                try {
                    found = find.get();
                } catch (RuntimeException e) {
                    found = new Built(null, List.of(), tr("status.run.failed", String.valueOf(e.getMessage())));
                }
                Built built = found;
                javafx.application.Platform.runLater(() -> {
                    if (generation != locateGeneration || !locatingProgram) {
                        return; // stopped meanwhile
                    }
                    locatingProgram = false;
                    Path program = built.program();
                    if (program == null) {
                        host.setStatus(String.valueOf(built.problem()));
                        ops.onRunStateChanged();
                        return;
                    }
                    List<String> command = new ArrayList<>();
                    command.add(program.toString());
                    command.addAll(built.firstArgs());
                    command.addAll(run.args());
                    pendingRerun = run.again(); // Rerun builds an edited file again rather than replaying the program
                    streamRun(
                            run.label(), run.dir() != null ? run.dir() : defaultDir, command, run.env(), run.source());
                });
            });
        });
    }

    /** Runs build step {@code index} of {@code steps} and those after it, then {@code then}. */
    private void runBuildSteps(
            CompiledRun run, Path root, List<List<String>> steps, int index, String statusKey, Runnable then) {
        if (index >= steps.size()) {
            then.run();
            return;
        }
        beforeLaunch.run(
                host,
                new BeforeLaunchStep.Command(String.valueOf(run.source().getFileName()), steps.get(index), statusKey),
                root,
                java.util.Map.of(),
                compileConsole(root, index == 0),
                () -> runBuildSteps(run, root, steps, index + 1, statusKey, then));
        ops.onRunStateChanged(); // the build is running now: the toolbar's Stop button applies to it
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
    private @Nullable List<String> programArgs(String text, @Nullable RunConfiguration cfg) {
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
        return service.isRunning() || beforeLaunch.isActive() || locatingProgram;
    }

    /** The configuration's program running in a Terminal tab, or null; Run's Stop ends it. */
    private com.editora.terminal.@Nullable ProgramRun terminalProgram;

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
    private @Nullable Path configRoot() {
        return launchRoot != null ? launchRoot : ops.projectRoot();
    }

    /** {@code local} (a folder of the project) as the launch sees it: its copy on the host when deployed. */
    private Path onLaunchHost(Path local) {
        DeploymentCoordinator.Deployed d = deployed;
        if (d == null) {
            return local;
        }
        Path copy = d.paths().toHost(local);
        return copy != null ? copy : d.remoteRoot();
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
        if (cfg.isJava() || cfg.target().isBlank()) {
            runConfigSaved(cfg, null); // no script file to look for: nothing read
            return;
        }
        // The script's file is looked for off the FX thread (PERF-01, Pauta 6.0.1).
        Path root = configRoot();
        offFx(
                "run-config-script",
                () -> java.util.Optional.ofNullable(configScript(cfg, root)),
                found -> runConfigSaved(cfg, found.orElse(null)));
    }

    private void runConfigSaved(RunConfiguration cfg, @Nullable Path script) {
        // Saved off the FX thread; the configuration goes on when the writes have landed (PERF-01, Pauta 6.0.1).
        saveThen(scriptAndActive(script), () -> runConfigScriptSaved(cfg));
    }

    private void runConfigScriptSaved(RunConfiguration cfg) {
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
    private static @Nullable Path configScript(RunConfiguration cfg, @Nullable Path root) {
        com.editora.perf.BlockingSites.enter("RunCoordinator.configScript");
        Path cwd = RunConfigWorkingDirectory.resolve(cfg, root);
        return cwd == null ? null : RunConfigWorkingDirectory.script(cfg, root, cwd);
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
        if (ScriptRunCommand.isCompiled(given.type())) {
            host.setStatus(tr("status.run.compiledRemote", given.name())); // said before anything is deployed
            return;
        }
        // A deploy sends what is on disk: the project's edited files are saved first, off the FX thread (PERF-01).
        saveThen(projectBuffers(localRoot), () -> deploySaved(given, debug, localRoot));
    }

    private void deploySaved(RunConfiguration given, boolean debug, Path localRoot) {
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

    /** Every open, edited file of the project at {@code root}. */
    private List<EditorBuffer> projectBuffers(Path root) {
        List<EditorBuffer> dirty = new java.util.ArrayList<>();
        host.forEachBuffer(b -> {
            if (b.isDirty()
                    && b.getPath() != null
                    && com.editora.deploy.DeployPaths.relative(root, b.getPath()) != null) {
                dirty.add(b);
            }
        });
        return dirty;
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
            public void ended(boolean ok, @Nullable String message) {
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
        DeploymentCoordinator.Deployed d = deployed;
        if (d == null) {
            return; // a Run asked for during the before-launch step reset the launch (beginRunRequest)
        }
        Path localRoot = d.localRoot();
        Path routing = routingFor(host, cfg);
        if (debug && routing == null) {
            host.setStatus(tr("status.run.configNeedsJavaFile")); // the debugger is attached through it
            return;
        }
        // The module, its build files, the launch plan (an env file's read included) and the Gradle build root are
        // looked for off the FX thread (PERF-01, Pauta 6.0.1).
        offFx(
                "deploy-java-plan",
                () -> {
                    Path module = routing == null ? null : ops.javaProjectRoot(routing);
                    if (module == null || com.editora.deploy.DeployPaths.relative(localRoot, module) == null) {
                        module = localRoot;
                    }
                    boolean isMaven = ops.mavenProjectAt(module);
                    boolean isGradle = !isMaven && ops.gradleProjectAt(module);
                    Path onHost = hostPath(d, module);
                    var result = !isMaven && !isGradle
                            ? null
                            : com.editora.run.JavaLaunchPlan.forConfiguration(
                                    cfg, com.editora.run.JavaLaunchPlan.Inputs.of(onHost, "", isMaven));
                    Path gradleAt = isGradle ? hostPath(d, gradleBuildRoot(localRoot, module)) : null;
                    return new DeployedPlan(module, isMaven, isGradle, onHost, result, gradleAt);
                },
                p -> runDeployedJava(cfg, debug, d, routing, p));
    }

    /** What {@link #runDeployedJava} read off the FX thread. */
    private record DeployedPlan(
            Path localModule,
            boolean maven,
            boolean gradle,
            Path hostModule,
            com.editora.run.JavaLaunchPlan.@Nullable Result planned,
            @Nullable Path gradleRoot) {}

    /** {@code local} where the deployment {@code d} put it on its host: {@link #onLaunchHost}, for another thread. */
    private static Path hostPath(DeploymentCoordinator.Deployed d, Path local) {
        Path copy = d.paths().toHost(local);
        return copy != null ? copy : d.remoteRoot();
    }

    private void runDeployedJava(
            RunConfiguration cfg,
            boolean debug,
            DeploymentCoordinator.Deployed d,
            @Nullable Path routing,
            DeployedPlan p) {
        Path localModule = p.localModule();
        boolean maven = p.maven();
        boolean gradle = p.gradle();
        if (!maven && !gradle) {
            host.setStatus(tr("status.deploy.javaNeedsBuild", cfg.name()));
            return;
        }
        Path hostModule = p.hostModule();
        com.editora.run.JavaLaunchPlan.Result planned = java.util.Objects.requireNonNull(p.planned());
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        if (plan == null) {
            reportPlanProblem(cfg, planned.problem());
            return;
        }
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
        host.setStatus(tr("status.deploy.building", d.deployment().name()));
        if (maven) {
            // The host's Maven build streams into the Run console like a local one (review D10); the scope
            // is the local copy's (the routing file's tree), the module the host's.
            resolveMavenClasspath(
                    hostModule,
                    com.editora.maven.MavenClasspath.Scope.forSource(routing, localModule),
                    plan.jdkHome(),
                    label,
                    launch);
            return;
        }
        Path gradleRoot = p.gradleRoot();
        if (gradleRoot == null) {
            return; // not reached: a Gradle module always has its build root looked for
        }
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
    private java.util.function.BiConsumer<String, Boolean> debugAttacher(@Nullable Path anchor, @Nullable Path onHost) {
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
    private List<EditorBuffer> scriptAndActive(@Nullable Path script) {
        List<EditorBuffer> out = new ArrayList<>();
        if (script != null) {
            host.forEachBuffer(b -> {
                if (out.isEmpty()
                        && b.getPath() != null
                        && com.editora.config.PathKeys.samePath(b.getPath(), script)
                        && b.isDirty()) {
                    out.add(b);
                }
            });
        }
        EditorBuffer active = host.activeBuffer();
        if (active != null && active.getPath() != null && active.isDirty() && !out.contains(active)) {
            out.add(active);
        }
        return out;
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
    private @Nullable Path shebangDir(ShellCommand.Launch shell, @Nullable Path cwd, boolean remote) {
        return chdir(shell.chdir(), cwd, remote);
    }

    /** {@code cwd}, or {@code chdir} (a shebang's {@code env -C DIR}) resolved against it; null when missing. */
    private @Nullable Path chdir(@Nullable String chdir, @Nullable Path cwd, boolean remote) {
        if (chdir == null || chdir.isBlank() || cwd == null) {
            return cwd; // no folder to resolve against: every caller passes one
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

    /**
     * Runs {@code cfg}'s before-launch command in the Run console, then {@code then} — see
     * {@link BeforeLaunchStep}. On this coordinator's own {@link RunService}, so the step and a program
     * exclude each other and {@link #stopRun} reaches whichever is alive.
     */
    private void withBeforeLaunch(RunConfiguration cfg, Runnable then) {
        Path routing = routingFor(host, cfg);
        if (!factsKnown(
                "run-before-launch-look",
                () -> beforeLaunchFacts(cfg, routing, beforeLaunchDir(cfg, routing)),
                () -> withBeforeLaunch(cfg, then))) {
            return;
        }
        Path cwd = beforeLaunchDir(cfg, routing);
        Path local = routing == null ? ops.projectRoot() : ops.javaProjectRoot(routing);
        Path project = local == null ? null : onLaunchHost(local);
        String jdkHome = project == null ? effectiveMavenJdk(null, cfg) : jdkHomeFor(project, cfg);
        Path toolRoot = project != null ? project : cwd;
        beforeLaunch.run(
                host,
                cfg,
                tool -> ops.buildExecutable(tool, toolRoot), // a symbolic step (maven:compile) runs the tree's Maven
                cwd,
                project == null
                        ? JdkToolchain.environment(jdkHome, processPath())
                        : javaEnvironment(project, jdkHome, null),
                stepConsole(cwd),
                then);
        ops.onRunStateChanged(); // the step is running now: the toolbar's Stop button applies to it
    }

    /**
     * What {@link #withBeforeLaunch} asks of the disk once {@code routing} and {@code cwd} are known (worked out on
     * the FX thread): the Java project, its build files and the symbolic step's build tool there. No other effect.
     */
    private void beforeLaunchFacts(RunConfiguration cfg, @Nullable Path routing, @Nullable Path cwd) {
        Path local = routing == null ? ops.projectRoot() : ops.javaProjectRoot(routing);
        Path project = local == null ? null : onLaunchHost(local);
        if (project == null) {
            effectiveMavenJdk(null, cfg);
        } else {
            jdkHomeFor(project, cfg);
        }
        Path toolRoot = project != null ? project : cwd;
        BuildBeforeLaunch.Symbolic symbolic = BuildBeforeLaunch.symbolic(cfg.beforeLaunch());
        if (symbolic != null && toolRoot != null) {
            ops.buildExecutableFacts(symbolic.tool(), toolRoot);
        }
    }

    /** The Run console as a before-launch step shows itself in it: a build, or the Maven classpath resolution. */
    private BeforeLaunchStep.Console stepConsole(@Nullable Path cwd) {
        return new BeforeLaunchStep.Console() {
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
            public void ended(int code, @Nullable String launchError) {
                if (launchError != null) {
                    panel().failed(launchError);
                } else {
                    panel().finished(code);
                }
                ops.onRunStateChanged();
            }
        };
    }

    /**
     * The build before a jdtls launch (review D2): in a Maven or Gradle project on this machine, with "Build
     * before Run and Debug" on and no before-launch step of the configuration's own ({@code configuredStep}),
     * compiles with the build tool — Maven {@code compile}, or {@code test-compile} for a class declared under
     * {@code src/test}; Gradle {@code compileJava} / {@code compileTestJava} — in the Run console, on the
     * project's JDK, through the executable the tasks tree uses, and aborts the launch when it fails. Then
     * {@code then} with whether a build ran. jdtls runs with autobuild off, so without this the launch ran
     * nothing after a clone and the previous binary after every edit.
     */
    private void buildBeforeLaunch(
            Path root, Path source, String configuredStep, String label, String jdkHome, Consumer<Boolean> then) {
        boolean buildBeforeRun = host.settings().isBuildBeforeRun();
        Path project = ops.projectRoot();
        // Which build tool, its reactor and whether the source is a test are read off the FX thread (PERF-01,
        // Pauta 6.0.1); the trust question and the step are back on it.
        offFx(
                "run-build-before-launch-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("BuildBeforeLaunch.plan");
                    com.editora.build.BuildTool tool = null;
                    if (com.editora.process.ExecTargets.runsHere(root)) {
                        tool = ops.mavenProjectAt(root)
                                ? com.editora.build.BuildTool.MAVEN
                                : ops.gradleProjectAt(root) ? com.editora.build.BuildTool.GRADLE : null;
                    }
                    if (tool == null || !BuildBeforeLaunch.wanted(buildBeforeRun, configuredStep, tool)) {
                        return new BuildFirstPlan(null, null, false);
                    }
                    ops.buildExecutableFacts(tool, root); // its wrapper and trust, for the FX thread next
                    return new BuildFirstPlan(
                            tool,
                            tool == com.editora.build.BuildTool.MAVEN
                                    ? com.editora.maven.MavenReactor.describeOnDisk(root, project)
                                    : null,
                            BuildBeforeLaunch.isTestSource(source, root));
                },
                plan -> buildBeforeLaunchPlanned(root, label, jdkHome, then, plan));
    }

    /** {@link #buildBeforeLaunch} once its plan has been read off the FX thread. */
    private void buildBeforeLaunchPlanned(
            Path root, String label, String jdkHome, Consumer<Boolean> then, BuildFirstPlan plan) {
        {
            com.editora.build.BuildTool tool = plan.tool();
            if (tool == null) {
                then.accept(false);
                return;
            }
            if (!factsKnown(
                    "run-build-before-look",
                    executableFacts(tool, root),
                    () -> buildBeforeLaunchPlanned(root, label, jdkHome, then, plan))) {
                return;
            }
            List<String> executable = ops.buildExecutable(tool, root);
            if (executable == null) {
                return; // the trust gate said why
            }
            // Maven: from the module's reactor when one lists it (its uninstalled siblings compile first),
            // with the tasks tree's profiles and flags (reviews D5, D13).
            List<String> extra = tool == com.editora.build.BuildTool.MAVEN
                    ? BuildBeforeLaunch.mavenExtraArgs(plan.reactor(), null, ops.mavenToggleArgs())
                    : List.of();
            List<String> argv = BuildBeforeLaunch.compileArgv(tool, executable, plan.test(), extra);
            beforeLaunch.run(
                    host,
                    new BeforeLaunchStep.Command(label, argv, "status.run.buildBeforeLaunch"),
                    root,
                    javaEnvironment(root, jdkHome, null),
                    stepConsole(root),
                    () -> then.accept(true));
            ops.onRunStateChanged();
        }
    }

    /** What the build before a launch read off the FX thread: the tool (null: no build), the reactor, a test. */
    private record BuildFirstPlan(
            com.editora.build.@Nullable BuildTool tool,
            com.editora.maven.MavenReactor.@Nullable Reactor reactor,
            boolean test) {}

    /**
     * Resolves the Maven classpath of {@code root} for a launch of the class declared in {@code source}, then
     * {@code then} with it. The resolution runs in the Run console as a before-launch step — its output
     * streams, Stop ends it, a second Run is refused while it runs, the window's close kills it (reviews D4,
     * D8) — with the executable the tasks tree uses (review D3) and its profiles (review D13), and a failure's
     * status quotes Maven's first {@code [ERROR]} line. A module its reactor lists builds from the reactor
     * root; when Maven then answers that its reactor does not hold the module, the module is built alone
     * instead, and the console says which it was (review D5). A project on a host resolves the same way,
     * with Maven on the host and its build streamed here (review D10).
     */
    private void resolveMavenClasspath(
            Path root, @Nullable Path source, String jdkHome, String label, Consumer<List<String>> then) {
        resolveMavenClasspath(
                root,
                source == null
                        ? com.editora.maven.MavenClasspath.Scope.RUNTIME
                        : com.editora.maven.MavenClasspath.Scope.forSource(source, root),
                jdkHome,
                label,
                then);
    }

    /** As {@link #resolveMavenClasspath(Path, Path, String, String, Consumer)} for a known {@code scope}. */
    private void resolveMavenClasspath(
            Path root,
            com.editora.maven.MavenClasspath.Scope scope,
            String jdkHome,
            String label,
            Consumer<List<String>> then) {
        if (!factsKnown(
                "run-maven-classpath-look",
                executableFacts(com.editora.build.BuildTool.MAVEN, root),
                () -> resolveMavenClasspath(root, scope, jdkHome, label, then))) {
            return;
        }
        List<String> executable = ops.buildExecutable(com.editora.build.BuildTool.MAVEN, root);
        if (executable == null) {
            return; // the trust gate said why
        }
        if (consoleRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        List<String> toggles = ops.mavenToggleArgs();
        Path boundary = ops.projectRoot();
        // Planning reads the reactor's poms and makes the classpath file (on a host it asks whether its temp folder
        // is in view): off the FX thread, here as there (PERF-01, Pauta 6.0.1).
        if (!com.editora.process.ExecTargets.runsHere(root)) {
            host.setStatus(tr("status.run.resolveClasspath", label)); // a host can take a while to answer
        }
        planner.accept("editora-maven-plan", () -> {
            com.editora.perf.BlockingSites.enter("MavenClasspathResolver.plan");
            com.editora.maven.MavenClasspathResolver.Plan plan;
            try {
                plan = com.editora.maven.MavenClasspathResolver.plan(root, boundary, executable, toggles, scope);
            } catch (java.io.IOException | RuntimeException e) {
                onFxThread(() ->
                        host.setStatus(tr("status.run.resolveClasspathFailed", label, String.valueOf(e.getMessage()))));
                return;
            }
            onFxThread(() -> runResolution(plan, root, jdkHome, label, then, false));
        });
    }

    /** Deletes what {@code plan} made, off the FX thread. */
    private static void discardLater(com.editora.maven.MavenClasspathResolver.Plan plan) {
        Thread.ofVirtual().name("editora-maven-discard").start(() -> {
            com.editora.perf.BlockingSites.enter("MavenClasspathResolver.discard");
            com.editora.maven.MavenClasspathResolver.discard(plan);
        });
    }

    /**
     * Runs {@code plan} as a step of the Run console, then {@code then} with the classpath it wrote.
     * {@code retried}: this is the single-module retry after Maven refused the reactor selection.
     */
    private void runResolution(
            com.editora.maven.MavenClasspathResolver.Plan plan,
            @Nullable Path root,
            String jdkHome,
            String label,
            Consumer<List<String>> then,
            boolean retried) {
        if (consoleRunning()) {
            discardLater(plan);
            host.setStatus(tr("status.run.busy"));
            return;
        }
        BeforeLaunchStep.Console console = stepConsole(plan.workingDirectory());
        boolean[] notInReactor = {false};
        beforeLaunch.run(
                host,
                new BeforeLaunchStep.Command(label, plan.argv(), "status.run.resolveClasspath"),
                plan.workingDirectory(),
                javaEnvironment(root, jdkHome, null),
                new BeforeLaunchStep.Console() {
                    @Override
                    public void started(String commandLine) {
                        console.started(commandLine); // clears the console: the notices follow it
                        if (retried && root != null) { // a reactor retry has a root
                            panel().appendNotice(tr("run.console.mavenReactorRetry", selectorOf(root)));
                        }
                        com.editora.maven.MavenReactor.Reactor reactor = plan.reactor(); // null: not multi-module
                        panel().appendNotice(
                                        reactor != null
                                                ? tr(
                                                        "run.console.mavenReactor",
                                                        reactor.rootName(),
                                                        reactor.moduleSelector())
                                                : tr("run.console.mavenSingleModule"));
                    }

                    @Override
                    public void output(String line, boolean stderr) {
                        console.output(line, stderr);
                        if (plan.multiModule() && com.editora.maven.MavenReactor.isNotInReactor(line)) {
                            notInReactor[0] = true;
                        }
                    }

                    @Override
                    public void ended(int code, @Nullable String launchError) {
                        console.ended(code, launchError);
                        if (code == 0 && launchError == null) {
                            return;
                        }
                        if (notInReactor[0] && launchError == null && !beforeLaunch.wasStopped()) {
                            // Maven's reactor is the authority: the module it does not hold builds alone.
                            javafx.application.Platform.runLater(
                                    () -> runResolution(plan.singleModule(), root, jdkHome, label, then, true));
                            return;
                        }
                        discardLater(plan); // the step said why
                    }
                },
                () -> offFx(
                        "editora-maven-classpath",
                        () -> {
                            com.editora.perf.BlockingSites.enter("MavenClasspathResolver.finish");
                            List<String> cp = com.editora.maven.MavenClasspathResolver.finish(plan);
                            return cp == null ? List.<String>of() : cp;
                        },
                        cp -> {
                            if (cp.isEmpty()) {
                                host.setStatus(tr(
                                        "status.run.resolveClasspathFailed",
                                        label,
                                        tr("status.run.noClasspathWritten")));
                                return;
                            }
                            then.accept(cp);
                        }));
        ops.onRunStateChanged();
    }

    /** The module's folder name, for a notice. */
    private static String selectorOf(Path root) {
        Path name = root.getFileName();
        return name == null ? root.toString() : name.toString();
    }

    /**
     * Watches a program's first lines for the JVM not finding its main class and appends the hint to build
     * (review D15) — for a launch that ran without the build before it ("Build before Run and Debug" off).
     */
    private java.util.function.BiConsumer<String, Boolean> missingClassHint(@Nullable String mainClass) {
        int[] seen = {0};
        boolean[] said = {false};
        return (line, stderr) -> {
            if (said[0] || seen[0]++ >= BuildBeforeLaunch.HINT_WINDOW) {
                return;
            }
            if (mainClass != null && BuildBeforeLaunch.missingClass(line, mainClass)) {
                said[0] = true;
                panel().appendNotice(tr("run.console.buildHint"));
            }
        };
    }

    /** The file jdtls says declares {@code mc}, else the launch's routing file: what decides the build's scope. */
    private static Path sourceOf(JavaMainClass mc, Path routing) {
        if (mc != null && mc.filePath() != null && !mc.filePath().isBlank()) {
            try {
                return Path.of(mc.filePath());
            } catch (RuntimeException notAPath) {
                return routing;
            }
        }
        return routing;
    }

    /** Where a before-launch command runs: the configuration's working directory, else the project root. */
    @Nullable
    Path beforeLaunchDir(RunConfiguration cfg) {
        return beforeLaunchDir(cfg, routingFor(host, cfg));
    }

    /** {@link #beforeLaunchDir(RunConfiguration)} with the configuration's routing file already worked out. */
    private @Nullable Path beforeLaunchDir(RunConfiguration cfg, @Nullable Path routing) {
        if (cfg.workingDir().isBlank() && ScriptRunCommand.PYTHON.equals(cfg.type()) && configRoot() != null) {
            return configRoot(); // the script runs in its own folder; a build/setup step stays at the root
        }
        // A Java configuration's step is a build (mvn compile, gradle classes): it belongs in the project root
        // whatever folder the program runs in — in a working directory of "sub" Maven found no pom (review A12).
        if (cfg.isJava()) {
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
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.isDirty() && RemoteExec.runsFor(b.getPath()) && takeJustSaved() != b) {
            // Save whatever the user was editing before launching, as before: off the FX thread, then again here.
            saveThen(List.of(b), () -> {
                justSaved = b;
                runJavaConfig(cfg);
            });
            return;
        }
        justSaved = null;
        // The project, its build files, an env @file and the Maven module are read off the FX thread (PERF-01,
        // Pauta 6.0.1): one plan for Run and Debug (review D9), working directory, JDK, environment and arguments.
        String settingsJdk = host.projectScope().jdkHome(host.settings());
        Path project = ops.projectRoot();
        offFx(
                "run-java-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("JavaLaunchPlan.plan");
                    Path root = ops.javaProjectRoot(routing);
                    if (root == null) {
                        return new JavaConfigPlan(null, false, null, null, "");
                    }
                    boolean maven = ops.mavenProjectAt(root);
                    var planned = com.editora.run.JavaLaunchPlan.forConfiguration(
                            cfg, com.editora.run.JavaLaunchPlan.Inputs.of(root, settingsJdk, maven));
                    Path module = maven ? mavenModuleOf(root, project, cfg) : null;
                    String inherited = maven ? JdkToolchain.inheritedJavaExecutable(System.getenv()) : "";
                    return new JavaConfigPlan(root, maven, planned, module, inherited);
                },
                ready -> runJavaConfigPlanned(cfg, routing, ready));
    }

    /** What a Java configuration's Run read off the FX thread. {@code root} null: the file is in no project. */
    private record JavaConfigPlan(
            @Nullable Path root,
            boolean maven,
            com.editora.run.JavaLaunchPlan.@Nullable Result planned,
            @Nullable Path module,
            String inheritedJava) {}

    private void runJavaConfigPlanned(RunConfiguration cfg, Path routing, JavaConfigPlan ready) {
        Path root = ready.root();
        com.editora.run.JavaLaunchPlan.Result planned = ready.planned();
        if (root == null || planned == null) {
            host.setStatus(tr("status.run.noProject"));
            return;
        }
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        if (plan == null) {
            reportPlanProblem(cfg, planned.problem());
            return;
        }
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
                // The build first (review D2): the classpath jdtls resolves is right, the class files in it
                // are whatever the last build left — nothing, or the previous code.
                buildBeforeLaunch(root, sourceOf(mc, routing), cfg.beforeLaunch(), label, jdkHome, built -> {
                    ops.resolveJavaLaunch(routing, mc, info -> {
                        if (info == null || !info.ok()) {
                            host.setStatus(info == null ? tr("status.run.resolveFailed") : info.error());
                            return;
                        }
                        if (!built) {
                            pendingOutputWatcher = missingClassHint(cfg.mainClass());
                        }
                        streamRun(
                                label,
                                cwd,
                                JavaRunCommand.build(
                                        javaOverride.isBlank()
                                                ? java.util.Objects.requireNonNullElse(info.javaExec(), "")
                                                : javaOverride,
                                        info.modulePaths(),
                                        info.classPaths(),
                                        cfg.mainClass(),
                                        info.vmArgs(vm),
                                        args),
                                launchEnv);
                    });
                });
            });
        } else if (ready.maven() && ready.module() != null) {
            // The configuration's module, not the routing file's (review D12); with no JDK selected, the java
            // of the JAVA_HOME Maven compiled with (review D18).
            Path module = ready.module();
            String java = !javaOverride.isBlank() ? javaOverride : onHost ? "java" : ready.inheritedJava();
            resolveMavenClasspath(
                    module,
                    module.equals(root) ? routing : module,
                    jdkHome,
                    label,
                    cp -> streamRun(
                            label,
                            module.equals(root) || !cfg.workingDir().isBlank() ? cwd : module,
                            JavaRunCommand.build(java, List.of(), cp, cfg.mainClass(), vm, args),
                            launchEnv));
        } else {
            host.setStatus(tr("status.run.javaUnavailable"));
        }
    }

    /**
     * The Maven module a saved configuration's class lives in (review D12): the configuration's
     * {@code projectName} (jdtls's name for a module = its artifactId) or its main class's source file, looked
     * for among the modules of the reactor around {@code root} (the open project when it is a Maven root,
     * else the reactor {@code root} belongs to, else {@code root} alone); {@code root} — the routing file's
     * module — when nothing matches.
     */
    private Path mavenModuleOf(Path root, @Nullable Path project, RunConfiguration cfg) {
        if (!com.editora.process.ExecTargets.runsHere(root)) {
            return root;
        }
        Path start = project != null && ops.mavenProjectAt(project) && root.startsWith(project) ? project : null;
        if (start == null) {
            com.editora.maven.MavenReactor.Reactor reactor =
                    com.editora.maven.MavenReactor.describeOnDisk(root, project);
            start = reactor != null ? reactor.root() : root;
        }
        Path module = com.editora.maven.MavenReactor.locateModuleOnDisk(start, cfg.projectName(), cfg.mainClass());
        return module != null && ops.mavenProjectAt(module) ? module : root;
    }

    /** What a {@link com.editora.run.JavaLaunchPlan} of a configuration in {@code root}'s project needs to know. */
    com.editora.run.JavaLaunchPlan.Inputs javaLaunchInputs(Path root) {
        return com.editora.run.JavaLaunchPlan.Inputs.of(
                root, host.projectScope().jdkHome(host.settings()), ops.mavenProjectAt(root));
    }

    /** Says which field of {@code cfg} a plan could not read, and opens its form there. */
    void reportPlanProblem(RunConfiguration cfg, com.editora.run.JavaLaunchPlan.@Nullable Problem problem) {
        if (problem != null) { // a plan that failed always names its problem
            String quote = String.valueOf(problem.quote());
            host.setStatus(
                    !problem.envFile().isEmpty()
                            ? tr("status.run.envFileUnreadable", cfg.name(), problem.envFile())
                            : problem.field() == com.editora.run.JavaLaunchPlan.Field.ENV
                                    ? tr("status.run.envUnclosedQuote", cfg.name(), quote)
                                    : tr("status.run.argsUnclosedQuote", quote));
        }
        ops.editConfiguration(cfg.name());
    }

    private void startMainClassRun(@Nullable String targetFqn) {
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
        // The project and its build files are looked for off the FX thread (PERF-01, Pauta 6.0.1).
        offFx(
                "run-main-class-plan",
                () -> {
                    com.editora.perf.BlockingSites.enter("JavaProjectRoot.find");
                    Path found = ops.javaProjectRoot(routing);
                    if (found != null) {
                        ops.mavenProjectAt(found); // looked at here: the FX thread then answers from it
                        ops.gradleProjectAt(found);
                    }
                    return java.util.Optional.ofNullable(found);
                },
                found -> startMainClassRunPlanned(b, routing, found.orElse(null), targetFqn));
    }

    private void startMainClassRunPlanned(
            EditorBuffer b, Path routing, @Nullable Path root, @Nullable String targetFqn) {
        if (root == null) {
            host.setStatus(tr("status.run.noProject"));
            return;
        }
        if (!factsKnown(
                "run-main-class-look",
                () -> {
                    ops.mavenProjectAt(root);
                    ops.gradleProjectAt(root);
                },
                () -> startMainClassRunPlanned(b, routing, root, targetFqn))) {
            return;
        }
        if (b.isDirty() && takeJustSaved() != b) {
            // Saved off the FX thread, then planned again from here: the facts above are known by then (PERF-01).
            saveThen(List.of(b), () -> {
                justSaved = b;
                startMainClassRunPlanned(b, routing, root, targetFqn);
            });
            return;
        }
        justSaved = null;
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
     * The configuration a gutter ▶ should launch, or null to keep the ad-hoc main-class run.
     *
     * <p>In a Maven/Gradle project the configuration is the better launch: it carries the before-launch build,
     * program/VM arguments, working dir and env that a bare "run this main class" has none of — so a generated
     * project whose {@code target/classes} was empty launched and failed. The configuration naming the clicked
     * class wins; only if none does do we fall back to whatever the toolbar has selected. Outside a build tool
     * there is nothing for a before-launch step to do, so the plain path stays.
     */
    private @Nullable RunConfiguration configurationForGutterRun(@Nullable String targetFqn, Path root) {
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

    private void runViaJdtls(Path routing, Path root, @Nullable String targetFqn) {
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
    private void runViaMaven(EditorBuffer b, Path root, @Nullable String targetFqn) {
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
        String jdkHome = jdkHomeFor(root, null);
        resolveMavenClasspath(root, b.getPath(), jdkHome, shortName(m.fqn()), cp -> {
            List<String> args = programArgs(ops.programArgs(b.getPath()), null);
            if (args == null) {
                return;
            }
            // With no JDK selected, the java of the JAVA_HOME Maven just compiled with (review D18).
            String java = !com.editora.process.ExecTargets.runsHere(root)
                    ? hostJava(jdkHome, null)
                    : JdkToolchain.javaExecutableOrInherited(jdkHome);
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
        String jdkHome = jdkHomeFor(root, null);
        String label = shortName(mc.className());
        // The arguments are asked for before the build: a prompt after a minute of compiling is a surprise.
        List<String> args = programArgs(programArgsForMain(mc), null);
        if (args == null) {
            return;
        }
        buildBeforeLaunch(
                root,
                sourceOf(mc, routing),
                "",
                label,
                jdkHome,
                built -> ops.resolveJavaLaunch(routing, mc, info -> {
                    if (info == null || !info.ok()) {
                        host.setStatus(info == null ? tr("status.run.resolveFailed") : info.error());
                        return;
                    }
                    List<String> command = JavaRunCommand.build(
                            java.util.Objects.requireNonNullElse(javaExecutableFor(root, info.javaExec()), ""),
                            info.modulePaths(),
                            info.classPaths(),
                            mc.fqn(),
                            info.vmArgs(List.of()),
                            args);
                    if (!built) {
                        pendingOutputWatcher = missingClassHint(mc.className());
                    }
                    streamRun(label, root, command, javaEnvironment(root, jdkHome, null));
                }));
    }

    /** Global Maven JDK, with a saved configuration's project override taking precedence. */
    private String effectiveMavenJdk(@Nullable Path root, @Nullable RunConfiguration cfg) {
        if (root == null || !ops.mavenProjectAt(root)) {
            return "";
        }
        return JdkToolchain.effectiveHome(
                cfg == null ? "" : cfg.jdkHome(), host.projectScope().jdkHome(host.settings()));
    }

    private @Nullable String javaExecutableFor(Path root, @Nullable String resolved) {
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
    private String jdkHomeFor(Path root, @Nullable RunConfiguration cfg) {
        return !com.editora.process.ExecTargets.runsHere(root) ? remoteJdkHome(root) : effectiveMavenJdk(root, cfg);
    }

    /** The environment for a Java program or build of {@code root}: on a host, its JDK only — not this PATH. */
    private static java.util.Map<String, String> javaEnvironment(
            @Nullable Path root, String jdkHome, java.util.@Nullable Map<String, String> configured) {
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
    static String hostJava(String jdkHome, @Nullable String resolved) {
        if (jdkHome != null && !jdkHome.isBlank()) {
            return jdkHome.strip().replaceAll("/+$", "") + "/bin/java";
        }
        return resolved == null || resolved.isBlank() ? "java" : resolved;
    }

    private static java.util.Map<String, String> launchEnvironment(
            String jdkHome, java.util.@Nullable Map<String, String> configured) {
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

    private static String shortName(@Nullable String fqn) {
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
    void runMakeTarget(EditorBuffer buffer, @Nullable String target) {
        if (buffer == null) {
            return;
        }
        if (buffer.isDirty() || buffer.getPath() == null) {
            // Saved off the FX thread first; a cancelled Save As or a failed save runs nothing (PERF-01).
            saveThen(List.of(buffer), () -> runMakeTargetSaved(buffer, target));
        } else {
            runMakeTargetSaved(buffer, target);
        }
    }

    private void runMakeTargetSaved(EditorBuffer buffer, @Nullable String target) {
        if (!beginRunRequest()) {
            return;
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
            // The load's (or an edit's) scan runs off FX: the command decides once it has landed.
            buffer.whenRunScanned(() -> runActiveFileScanned(buffer, promptArgs, trace));
        } else {
            runActiveFileScanned(null, promptArgs, trace);
        }
    }

    private void runActiveFileScanned(@Nullable EditorBuffer buffer, boolean promptArgs, boolean trace) {
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
        MenuChords.tag(trace, "run.trace");
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
        Path early = buffer.getPath();
        if ("scala".equals(buffer.getLanguage()) && early != null && com.editora.process.ExecTargets.runsHere(early)) {
            // Which build owns the file is a folder walk: off the FX thread (PERF-01, Pauta 6.0.1).
            Path project = ops.projectRoot();
            planner.accept("run-file-scala-owner", () -> {
                com.editora.perf.BlockingSites.enter("ScalaRun.ownerOf");
                boolean[] owned = {false};
                com.editora.perf.Looked.asOffFxWork(() -> owned[0] = scalaBuildOwner(early, project));
                boolean built = owned[0];
                onFxThread(() -> {
                    if (!built) {
                        startFileRunNow(buffer, promptArgs, trace);
                        return;
                    }
                    // A file of an sbt or Mill build runs as part of it, in that tool's window (not the Run console).
                    saveThen(List.of(buffer), () -> runScalaThroughBuild(buffer, early, promptArgs));
                });
            });
            return;
        }
        startFileRunNow(buffer, promptArgs, trace);
    }

    /**
     * What Run on a file reads before it starts, off the FX thread (PERF-01, Pauta 6.0.1): the trust question's
     * project files, the JVM language's tool, the shebang's launch with its interpreter and folder, Scala CLI's
     * inputs.
     */
    private record FilePlan(
            RunTrust.@Nullable Gate gate,
            @Nullable String jvmTool,
            ShellCommand.@Nullable Launch shell,
            @Nullable Path shellDir,
            @Nullable Path missingShellDir,
            @Nullable List<String> scalaInputs,
            @Nullable String jvmToolName,
            com.editora.run.ClojureRun.@Nullable Plan clojure) {}

    private FilePlan planFile(
            String language,
            Path path,
            String content,
            String firstLine,
            boolean shellFile,
            boolean trace,
            @Nullable Path project) {
        com.editora.perf.BlockingSites.enter("RunCoordinator.planFile");
        RunTrust.Gate gate = "scala".equals(language) && !com.editora.process.ExecTargets.runsHere(path)
                ? null // Scala CLI is given a remote file alone (scalaCliInputs)
                : RunTrust.forFile(language, path, content, project, java.nio.file.Files::isRegularFile);
        warmGate(gate); // the trust question's wrapper and summary, for the FX thread next
        String tool = jvmTool(language, path);
        ShellCommand.Launch shell = null;
        Path dir = path.getParent();
        Path missing = null;
        if (shellFile) {
            boolean remote = !com.editora.process.ExecTargets.runsHere(path);
            // The shebang decides the interpreter and its flags, as running ./script would.
            shell = ShellCommand.launch(firstLine, path.getFileName().toString(), "", "", existsHere(remote));
            if (trace) {
                shell = ShellCommand.withTrace(shell);
            }
            String chdir = shell.chdir();
            if (chdir != null && !chdir.isBlank() && dir != null) {
                Path to = dir.resolve(chdir).normalize();
                if (!com.editora.vfs.Vfs.isRemote(path) && !java.nio.file.Files.isDirectory(to)) {
                    dir = null;
                    missing = to;
                } else {
                    dir = to;
                }
            }
        }
        List<String> scala = "scala".equals(language)
                ? scalaCliInputs(
                        path, com.editora.process.ExecTargets.forPath(path).toTargetPath(path), content, project)
                : null;
        String toolName = com.editora.run.JvmScriptRun.supports(language)
                ? com.editora.run.JvmScriptRun.tool(language, path) // a Clojure file's head: read here
                : null;
        return new FilePlan(
                gate,
                tool,
                shell,
                dir,
                missing,
                scala,
                toolName,
                com.editora.run.JvmScriptRun.clojurePlan(language, path));
    }

    private void startFileRunNow(EditorBuffer buffer, boolean promptArgs, boolean trace) {
        if (consoleRunning() || deploying) {
            beginRunRequest(); // says the Run is busy
            return;
        }
        // Saved first, off the FX thread: a cancelled Save As or a failed save runs nothing (stale/missing content).
        saveThen(List.of(buffer), () -> {
            if (beginRunRequest()) {
                startFileRunSaved(buffer, promptArgs, trace);
            }
        });
    }

    private void startFileRunSaved(EditorBuffer buffer, boolean promptArgs, boolean trace) {
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        String language = buffer.getLanguage();
        String content = buffer.getContent();
        String firstLine = firstLine(buffer);
        boolean shellFile = buffer.isShell();
        Path project = ops.projectRoot();
        offFx(
                "run-file-plan",
                () -> planFile(language, path, content, firstLine, shellFile, trace, project),
                plan -> startFileRunPlanned(buffer, path, promptArgs, trace, plan));
    }

    private void startFileRunPlanned(EditorBuffer buffer, Path path, boolean promptArgs, boolean trace, FilePlan plan) {
        // A Clojure file of a Leiningen, deps.edn or bb.edn project, a file of a Scala CLI project: its tool loads
        // the project, not just the file — the workspace-trust gate first, so "no" runs nothing.
        if (!factsKnown(
                        "run-file-trust-look",
                        gateFacts(plan.gate()),
                        () -> startFileRunPlanned(buffer, path, promptArgs, trace, plan))
                || !trusted(plan.gate())) {
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
                !onJdk ? "" : remote ? remoteJdkHome(path) : host.projectScope().jdkHome(host.settings());
        String selectedJava = JdkToolchain.javaExecutable(jdkHome);
        String javaExecutable = selectedJava.isBlank() ? "java" : selectedJava;
        java.util.Map<String, String> launchEnv = !onJdk
                ? java.util.Map.of()
                : remote ? remoteJdkEnvironment(jdkHome) : JdkToolchain.environment(jdkHome, processPath());
        if (jvmScript && !remote && plan.jvmTool() == null) {
            // Said here, not as the shell's "groovy: command not found" in the Run console.
            // The tool's name was worked out with the plan, off the FX thread (PERF-01, Pauta 6.0.1).
            host.setStatus(tr(com.editora.run.JvmScriptRun.missingToolKeyFor(plan.jvmToolName())));
            return;
        }
        Runnable proceed = () -> {
            String stored = ops.programArgs(path);
            if (promptArgs) {
                host.promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                    ops.setProgramArgs(path, args == null ? "" : args.strip());
                    launchFile(buffer, path, javaExecutable, launchEnv, trace, plan);
                });
            } else {
                launchFile(buffer, path, javaExecutable, launchEnv, trace, plan);
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
            boolean trace,
            FilePlan plan) {
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
                    python -> streamPython(path.getFileName().toString(), dir, python, path));
            return;
        }
        java.util.Map<String, String> env = launchEnv;
        if ("kotlin".equals(buffer.getLanguage())
                && com.editora.run.KotlinRun.shape(path.getFileName().toString())
                        == com.editora.run.KotlinRun.Shape.PROGRAM) {
            String kotlinc = plan.jvmTool();
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
        if (buffer.isShell() && plan.shell() != null) {
            boolean remote = !com.editora.process.ExecTargets.runsHere(path);
            shell = plan.shell(); // the shebang's, read with the plan
            env = shellEnvironment(shell, java.util.Map.of(), remote);
        }
        if ("scala".equals(buffer.getLanguage())) {
            // Scala CLI runs the file, or its project folder with the file's main class.
            String tool = plan.jvmTool();
            List<String> inputs = plan.scalaInputs();
            List<String> argv = com.editora.run.JvmScriptRun.scalaCli(
                    tool == null ? com.editora.run.JvmScriptRun.tool("scala") : tool,
                    inputs == null ? List.of() : inputs,
                    List.of(),
                    args);
            pendingTrustGate = plan.gate();
            streamRun(path.getFileName().toString(), path.getParent(), argv, env, path);
            return;
        }
        if (com.editora.run.JvmScriptRun.supports(buffer.getLanguage())) {
            // The tool decides the folder too: a Clojure file runs in its project's, where deps.edn is read.
            String toolName = plan.jvmToolName(); // read with the plan, off the FX thread (PERF-01)
            String tool = plan.jvmTool();
            String executable = tool == null ? toolName : tool;
            if (executable == null) {
                return; // not reached: every language JvmScriptRun supports names its tool
            }
            com.editora.run.JvmScriptRun.Launch how = com.editora.run.JvmScriptRun.launch(
                    buffer.getLanguage(),
                    toolName,
                    executable,
                    path,
                    com.editora.process.ExecTargets.forPath(path).toTargetPath(path),
                    args,
                    "",
                    false,
                    env,
                    com.editora.process.ExecTargets.runsHere(path) ? System.getenv() : java.util.Map.of(),
                    plan.clojure());
            Path where = how.directory() != null ? how.directory() : path.getParent();
            pendingTrustGate = plan.gate();
            streamRun(path.getFileName().toString(), where, how.argv(), how.env(), path);
            return;
        }
        Path dir = shell == null ? path.getParent() : plan.shellDir();
        if (dir == null) {
            if (plan.missingShellDir() != null) {
                host.setStatus(tr("status.run.shebangDirMissing", String.valueOf(plan.missingShellDir())));
            }
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
    void streamNode(String label, NodeRunLauncher.Ready ready, @Nullable Path script) {
        com.editora.run.NodeRunPlan plan = ready.plan();
        if (plan.error() != null) {
            host.setStatus(tr(plan.error()));
            panel().failed(tr(plan.error()));
            return;
        }
        String chdir = plan.chdir();
        Path cwd = ready.dir();
        if (chdir != null && !chdir.isBlank() && cwd != null && !com.editora.vfs.Vfs.isRemote(cwd)) {
            // The shebang's env -C folder is looked for off the FX thread (PERF-01, Pauta 6.0.1).
            Path wanted = cwd.resolve(chdir).normalize();
            com.editora.perf.OffFx.then("run-node-chdir", () -> java.nio.file.Files.isDirectory(wanted), a -> {
                if (!Boolean.TRUE.equals(a.value())) {
                    host.setStatus(tr("status.run.shebangDirMissing", wanted.toString()));
                    return;
                }
                streamNodeIn(label, ready, script, wanted);
            });
            return;
        }
        Path dir = chdir(chdir, cwd, cwd != null && com.editora.vfs.Vfs.isRemote(cwd));
        if (dir != null) {
            streamNodeIn(label, ready, script, dir);
        }
    }

    private void streamNodeIn(String label, NodeRunLauncher.Ready ready, @Nullable Path script, Path dir) {
        com.editora.run.NodeRunPlan plan = ready.plan();
        addConsoleNotice(ready.notice()); // "Running the compiled dist/index.js (built from src/index.ts)"
        pendingRuntimeSource = ready.source(); // the chip's tooltip says why this Node.js
        if (ready.route() == com.editora.run.TsRunRoute.Kind.PLAIN
                && com.editora.run.NodeRunPlan.isTypeScript(ready.target())) {
            pendingOutputWatcher = tsImportHint();
        }
        streamRun(label, dir, plan.argv(), plan.env(), script);
    }

    /**
     * Watches a TypeScript file run on plain Node for the {@code ERR_MODULE_NOT_FOUND} of a {@code ./x.js} import
     * that means {@code x.ts} — Node cannot map it — and says once how to run such a project (review C102).
     */
    private java.util.function.BiConsumer<String, Boolean> tsImportHint() {
        boolean[] said = {false};
        return (line, stderr) -> {
            if (!said[0]
                    && stderr
                    && com.editora.run.TsRunRoute.missingJsImport(line, java.nio.file.Files::isRegularFile)
                            .isPresent()) {
                said[0] = true;
                panel().appendNotice(tr("run.console.tsJsImportHint"));
                host.setStatus(tr("run.console.tsJsImportHint"));
            }
        };
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
    private static @Nullable String jvmTool(String language, @Nullable Path path) {
        String tool = path == null
                ? com.editora.run.JvmScriptRun.tool(language)
                : com.editora.run.JvmScriptRun.tool(language, path);
        if (tool == null) {
            return null;
        }
        if (path != null && !com.editora.process.ExecTargets.runsHere(path)) {
            return tool;
        }
        return com.editora.run.JvmTools.locate(tool);
    }

    /**
     * What Scala CLI runs for {@code path} ({@code target} as its host names it): the file, or its Scala CLI
     * project's folder with the file's main class.
     */
    private static List<String> scalaCliInputs(Path path, String target, String text, @Nullable Path project) {
        if (!com.editora.process.ExecTargets.runsHere(path)) {
            return List.of(target); // a remote file: no folder walk over the connection
        }
        com.editora.run.ScalaRun.Owner owner =
                com.editora.run.ScalaRun.ownerOf(path, project, java.nio.file.Files::isRegularFile);
        String folder = owner.root() == null
                ? null
                : com.editora.process.ExecTargets.forPath(owner.root()).toTargetPath(owner.root());
        return com.editora.run.ScalaRun.cliInputs(target, owner, folder, com.editora.run.ScalaRun.mainClass(text));
    }

    /** Whether {@code path} belongs to an sbt, Mill, Gradle or Maven build, which runs it (not Scala CLI). */
    private static boolean scalaBuildOwner(Path path, @Nullable Path project) {
        if (!com.editora.process.ExecTargets.runsHere(path)) {
            return false; // a remote file runs through Scala CLI on its host
        }
        com.editora.run.ScalaRun.Build build = com.editora.run.ScalaRun.ownerOf(
                        path, project, java.nio.file.Files::isRegularFile)
                .build();
        return build == com.editora.run.ScalaRun.Build.SBT
                || build == com.editora.run.ScalaRun.Build.MILL
                || build == com.editora.run.ScalaRun.Build.OTHER;
    }

    /**
     * Runs a Scala file of an sbt or Mill build through that build's tool window — {@code sbt "runMain
     * demo.hello"}, {@code ./mill core.runMain demo.hello} — since only the build knows its classpath; says
     * where to run a file of a Gradle or Maven build instead.
     */
    private void runScalaThroughBuild(EditorBuffer buffer, Path path, boolean promptArgs) {
        com.editora.run.ScalaRun.Owner owner =
                com.editora.run.ScalaRun.ownerOf(path, ops.projectRoot(), java.nio.file.Files::isRegularFile);
        com.editora.build.BuildTool tool = switch (owner.build()) {
            case SBT -> com.editora.build.BuildTool.SBT;
            case MILL -> com.editora.build.BuildTool.MILL;
            default -> null;
        };
        if (tool == null) {
            host.setStatus(tr("status.run.scalaOtherBuild", path.getFileName().toString()));
            return;
        }
        String mainClass = com.editora.run.ScalaRun.mainClass(buffer.getContent());
        Path root = owner.root();
        java.util.function.Consumer<String> run = argText -> {
            List<String> args = programArgs(argText, null);
            if (args == null) {
                return;
            }
            List<String> task = tool == com.editora.build.BuildTool.SBT
                    ? com.editora.run.ScalaRun.sbtRunTask(mainClass, args)
                    : com.editora.run.ScalaRun.millRunTask(
                            com.editora.run.ScalaRun.millModule(root, path), mainClass, args);
            if (!ops.runBuildTask(tool, root, task)) {
                host.setStatus(tr("status.build.disabled", tool.displayName()));
            }
        };
        String stored = ops.programArgs(path);
        if (promptArgs) {
            host.promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                ops.setProgramArgs(path, args == null ? "" : args.strip());
                run.accept(args == null ? "" : args.strip());
            });
        } else {
            run.accept(stored);
        }
    }

    /** The launcher argv for the buffer's language: interpreter + file + {@code args}. */
    private List<String> buildRunCommand(
            EditorBuffer buffer,
            Path path,
            String javaExecutable,
            List<String> args,
            ShellCommand.@Nullable Launch shell) {
        if (shell != null) {
            return ShellCommand.argv(
                    shell, com.editora.process.ExecTargets.forPath(path).toTargetPath(path), args);
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
    static java.util.Map<String, String> remoteJdkEnvironment(@Nullable String jdkHome) {
        return jdkHome == null || jdkHome.isBlank() ? java.util.Map.of() : java.util.Map.of("JAVA_HOME", jdkHome);
    }

    /**
     * Streams a planned Python launch; says so when the file's shebang asked for an interpreter that is not
     * installed here and the project's ran instead.
     */
    private void streamPython(
            String label, @Nullable Path dir, com.editora.run.PythonRunPlan plan, @Nullable Path script) {
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
     * the console, then reports that another launch cannot start yet. A refused request leaves the launch under
     * way as it is: clearing its deployment while its before-launch step ran sent a deployed program to the local
     * project instead of the host's copy. A deploy counts too: a second one was refused only after it had marked
     * the first one ended, and Stop then no longer reached the upload.
     */
    private boolean beginRunRequest() {
        ops.openToolWindow();
        // A before-launch build counts: a second Run must not start a second build.
        if (consoleRunning() || deploying) {
            host.setStatus(tr("status.run.busy"));
            return false;
        }
        terminalRun = false; // only a configuration that asks for it, and only its own launch
        launchRoot = null; // and only a deployed configuration's launch resolves on its host
        deployed = null;
        return true;
    }

    /** Runs {@code command} in {@code workingDir} (the project root for a main class, else a file's folder),
     *  streaming into the Run console and remembering it for {@code run.rerun}. */
    private void streamRun(String label, Path workingDir, List<String> command) {
        streamRun(label, workingDir, command, java.util.Map.of());
    }

    /** As above, plus {@code env} — a saved run configuration's environment variables. */
    private void streamRun(
            String label, @Nullable Path workingDir, List<String> command, java.util.Map<String, String> env) {
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
    private @Nullable List<String> runtimeCommand;

    /** Set by an npm configuration: the next run's console starts with its command line, as a script's does. */
    private boolean pendingCommandLine;

    /** Adds {@code line} to what the next run's console shows first (after a deploy's summary), unless null. */
    private void addConsoleNotice(@Nullable String line) {
        if (line != null) {
            pendingDeploySummary = pendingDeploySummary == null ? line : pendingDeploySummary + "\n" + line;
        }
    }

    /** Why the next run's Node.js was chosen ({@code .nvmrc, via nvm}): shown on the runtime chip's tooltip. */
    private @Nullable String pendingRuntimeSource;

    /** The workspace-trust gate the next run passed ({@link RunTrust}), which its Rerun passes again; else null. */
    private RunTrust.@Nullable Gate pendingTrustGate;

    /** The gate the last run passed: Rerun asks it again, so a trust revoked meanwhile is not bypassed. */
    private RunTrust.@Nullable Gate lastRunGate;

    /** As above; {@code script} is the file being run (saved before a Rerun), null for a Java launch. */
    private void streamRun(
            @Nullable String label,
            @Nullable Path workingDir,
            List<String> command,
            java.util.Map<String, String> env,
            @Nullable Path script) {
        if (terminalRun) {
            terminalRun = false;
            runtimeCommand = null;
            pendingCommandLine = false;
            pendingRuntimeSource = null;
            pendingDeploySummary = null; // the console's extras do not apply to a Terminal tab
            pendingOutputWatcher = null;
            pendingRerun = null;
            pendingTrustGate = null;
            runInTerminal(label, workingDir, command, env);
            return;
        }
        lastRunScript = script;
        boolean commandLineFirst = pendingCommandLine;
        pendingCommandLine = false;
        String runtimeSource = pendingRuntimeSource;
        pendingRuntimeSource = null;
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
        lastRunGate = pendingTrustGate;
        pendingTrustGate = null;
        com.editora.deploy.DeployPaths deployedPaths = deployed == null ? null : deployed.paths();
        lastTestRerun = null;
        stopRequested = false;
        lastRunDir = workingDir;
        // A deployed program's files are the project's: its console links open those, not the host's copies.
        Path localDir = deployedPaths == null || workingDir == null ? null : deployedPaths.toLocal(workingDir);
        consoleDir = localDir != null ? localDir : workingDir;
        lastRunLabel = label;
        lastRunCommand = command;
        lastRunEnv = env;
        consoleUsed = true;
        ops.openToolWindow();
        panel().starting(label);
        if (deploySummary != null) {
            panel().showCommandLine(deploySummary);
        }
        List<String> runtime = runtimeCommand != null ? runtimeCommand : command;
        runtimeCommand = null;
        showRuntime(runtime, workingDir, env, runtimeSource);
        host.setStatus(tr("status.run.started", label));
        service.runInDir(workingDir, command, env, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                // The console was cleared, and the deploy's summary shown, when the run began above: the program
                // starts off the FX thread (PERF-01), and what was typed meanwhile stays.
                panel().runningAs(commandLine);
                if (script != null || tests != null || commandLineFirst) {
                    // Which interpreter ran, and how: the first thing to check when a script behaves oddly.
                    panel().showCommandLine(commandLine);
                }
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                Boolean shown = tests == null ? Boolean.valueOf(stderr) : tests.console(line, stderr);
                if (shown != null) {
                    panel().appendOutput(line, shown);
                }
                if (tests != null) {
                    tests.line(line, stderr);
                }
                if (watcher != null) {
                    watcher.accept(line, stderr);
                }
            }

            @Override
            public void onPartialOutput(String text, boolean stderr) {
                Boolean shown = tests == null ? Boolean.valueOf(stderr) : tests.console(text, stderr);
                if (shown != null) {
                    panel().appendPartialOutput(text, shown);
                }
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
    private void runInTerminal(
            @Nullable String label,
            @Nullable Path workingDir,
            List<String> command,
            java.util.Map<String, String> env) {
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
    private void showRuntime(
            List<String> command,
            @Nullable Path workingDir,
            java.util.Map<String, String> env,
            @Nullable String source) {
        long generation = ++runtimeGeneration;
        panel().setRuntime(null);
        com.editora.run.RuntimeProbe.probe(command, workingDir, env, version -> {
            if (generation == runtimeGeneration) {
                panel().setRuntime(NodeRunLauncher.withSource(version, source));
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
        if (locatingProgram) {
            locatingProgram = false; // the build is done; the program it made is not started
            locateGeneration++;
            host.setStatus(tr("status.run.stopped"));
            ops.onRunStateChanged();
            return;
        }
        if (service.isRunning()) {
            stopRequested = true;
            service.stop();
            host.setStatus(tr("status.run.stopped"));
        } else if (terminalProgram instanceof com.editora.terminal.ProgramRun program && program.isRunning()) {
            terminalStopRequested = true;
            program.stop(); // its tab stays; its end reports the stop
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
