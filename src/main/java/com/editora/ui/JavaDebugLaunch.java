package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.RunConfiguration;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.run.BuildBeforeLaunch;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Java launches of {@link DebugCoordinator}, and the build each waits for: Debug Main Class (the project's main
 * classes, a picker when there are several), Debug of a saved run configuration (Java, and the Groovy, Clojure, Scala,
 * Kotlin, C, C++, Go, Rust and Dart ones), the build before a launch — the build tool's compile, or jdtls's — whose
 * compile errors are listed in the Debug console, and the JDK a launch runs on. Part of {@link DebugCoordinator}.
 */
final class JavaDebugLaunch {

    private final DebugCoordinator d; // its host and ops are read when used: this is made with the coordinator

    JavaDebugLaunch(DebugCoordinator d) {
        this.d = d;
    }

    /**
     * {@code home}, or "" — PATH's java — when it names a JDK that is no longer there: the Debug console and the
     * status bar say so and where to choose another, where java-debug's raw "Cannot run program" stood beside an
     * empty console (review 3, A206).
     */
    String presentJdk(String home) {
        if (!com.editora.run.JdkToolchain.isGone(home)) {
            return home;
        }
        String said = tr("debug.console.defaultJdkMissing", home.strip());
        d.panel().clearConsole(); // this launch's console: kept when its session starts
        d.panel().appendNotice(said);
        d.s().consoleStarted = true;
        d.host.setStatus(said);
        return "";
    }

    /** The {@code java} a file without a build tool runs on: {@code selected} (the Settings JDK's), else PATH's. */
    static String looseJava(String selected) {
        if (selected != null && !selected.isBlank()) {
            return selected;
        }
        List<String> onPath = com.editora.process.ProcessRunner.resolveExecutable(List.of("java"));
        return com.editora.dap.LooseJavaLaunch.launchJava(selected, onPath.isEmpty() ? null : onPath.get(0));
    }

    /**
     * {@link #buildThenLaunch} for a Java file with no Maven or Gradle build — a loose file or a plain-folder
     * project (review 2, A101). jdtls built that folder's classes once, at import, and nothing rebuilt them: every
     * later launch ran old code, and a file that did not compile ran as "Unresolved compilation problem". Its
     * source folder is corrected first (a packaged class in a folder jdtls guessed wrong would not build), then
     * jdtls builds it, and only a launch whose build succeeded may use jdtls's output
     * ({@link DapManager#setLooseClassesBuilt}); any other compiles the file with javac from its declared source
     * root, and javac's errors — only those the program reaches — stop it ({@link JavaLaunchPrep#afterBuild}).
     */
    void looseBuildThenLaunch(Path routing, Runnable launch) {
        d.lsp.ensureManaged(routing);
        Object token = new Object();
        d.s().pendingBuild = token;
        showPreparing(routing); // from the first moment: the source-folder step can wait seconds on jdtls (C203)
        DebugSession session = d.s();
        d.planOffFx( // the file's package clause is read off the FX thread (PERF-01)
                "debug-loose-package",
                () -> com.editora.dap.JavaSourceRootFix.declaredPackage(readQuietly(routing)),
                a -> com.editora.dap.JavaSourceRootFix.correct(
                        d.lspManager,
                        routing,
                        a.value() == null ? "" : a.value(),
                        () -> session.pendingBuild == token,
                        d.asThisSession(fixed -> {
                            if (d.s().pendingBuild == token) {
                                buildThenLaunch(routing, launch, true);
                            }
                        })));
    }

    /**
     * The build before {@code routing}'s launch has begun: the Debug window opens with "Building…", a Stop that cancels
     * it and the spinner, and F5 there says the launch is still starting (review 3, C203, C204).
     */
    private void showPreparing(Path routing) {
        boolean shown = d.s().pendingBuildFile != null && DebugCoordinator.samePath(d.s().pendingBuildFile, routing);
        d.s().pendingBuildFile = routing;
        d.ops.openToolWindow();
        d.panel().setPreparing(true);
        d.debugLoading(true);
        d.updateDebugAvailability();
        if (!shown) {
            d.host.setStatus(tr("status.debug.building", String.valueOf(routing.getFileName())));
        }
    }

    /** Drops the build before a launch, if one is running: it finishes on its own, but nothing is launched. */
    void dropPendingBuild() {
        if (d.s().pendingBuild == null) {
            return;
        }
        d.s().pendingBuild = null;
        d.s().pendingBuildFile = null;
        d.panel().setPreparing(false);
        d.debugLoading(false);
        d.updateDebugAvailability();
    }

    /**
     * Every unsaved Java file of {@code project} (the launched file's folder tree without one), saved before a Java
     * launch: the program is compiled from what is on disk, and a breakpoint in an unsaved file would sit on lines
     * the program does not have (review B3).
     */
    List<EditorBuffer> javaBuffers(Path launched, @Nullable Path project, boolean includeLaunched) {
        List<EditorBuffer> dirty = new ArrayList<>();
        d.host.forEachBuffer(buf -> {
            if (buf.isDirty()
                    && (includeLaunched || !DebugCoordinator.samePath(buf.getPath(), launched))
                    && JavaLaunchPrep.saveBeforeLaunch(buf.getPath(), buf.getLanguage(), launched, project)) {
                dirty.add(buf);
            }
        });
        return dirty;
    }

    /**
     * Builds {@code project} with jdtls ({@code java/buildWorkspace}, what VS Code's build before launch does)
     * and runs {@code launch} when it compiled. Editora runs jdtls with autobuild off, so nothing else writes
     * the class files a project launch runs: a fresh project ended in ClassNotFoundException and an edited one
     * ran its old code under breakpoints placed on the new lines (review A2). Compile errors in the project are
     * listed in the Debug console (each a link to its line) and nothing is launched. A build that cannot run
     * launches anyway; the launch then checks the class files itself (missing: refused; older: a warning).
     */
    void buildThenLaunch(Path routing, Runnable launch) {
        buildThenLaunch(routing, launch, false);
    }

    /** As above; {@code loose}: a file without a build tool, whose launch is told whether its build succeeded. */
    private void buildThenLaunch(Path routing, Runnable launch, boolean loose) {
        Object token = new Object();
        d.s().pendingBuild = token;
        d.lsp.ensureManaged(routing);
        showPreparing(routing);
        d.lspManager.buildJavaWorkspaceForLaunch(routing, d.asThisSession(result -> {
            if (d.s().pendingBuild != token) {
                return; // stopped, or replaced by another launch
            }
            d.s().pendingBuild = null;
            d.s().pendingBuildFile = null;
            d.panel().setPreparing(false);
            d.debugLoading(false);
            JavaLaunchPrep.AfterBuild next = JavaLaunchPrep.afterBuild(result.outcome(), loose);
            if (next == JavaLaunchPrep.AfterBuild.REFUSE) {
                showBuildErrors(routing, result.errors(), BUILD_ERROR_WAITS);
                return;
            }
            if (loose) { // without jdtls's output, javac compiles it from its declared source root (review 3, A201)
                d.dap().setLooseClassesBuilt(next == JavaLaunchPrep.AfterBuild.LAUNCH_BUILT);
            }
            launch.run();
        }));
    }

    /**
     * Lists a failed build's compile errors in the Debug console and says so; nothing is launched. jdtls's
     * workspace is this project. Its build reports the errors of files that are not open as it finds them; an
     * open file's come from the editor's copy (the Problems view has them, and the copy on disk is the same:
     * every Java file of the project was saved first) — and may still be on their way, so with none known yet
     * this looks again a few times before saying only that there are errors.
     */
    private void showBuildErrors(Path routing, Map<Path, List<com.editora.text.LspDiagnostic>> published, int waits) {
        if (!d.factsKnown(
                "debug-build-errors-look",
                () -> JavaProjectRoot.find(routing),
                () -> showBuildErrors(routing, published, waits))) {
            return;
        }
        Map<Path, List<com.editora.text.LspDiagnostic>> found = new java.util.TreeMap<>(d.lsp.problems());
        found.putAll(published);
        // Only this launch's project: the Problems window holds every open file's, of every language (review 2, A104).
        Path project = JavaLaunchPrep.projectOf(routing, JavaProjectRoot.find(routing), d.ops.projectRoot());
        java.util.function.Predicate<Path> scope = JavaLaunchPrep.errorScope(routing, project);
        int errors = JavaLaunchPrep.javaErrorCount(found, scope);
        List<String> importErrors = JavaLaunchPrep.importErrors(found, project);
        if (errors == 0 && importErrors.isEmpty() && waits > 0) {
            javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
            later.setOnFinished(e -> showBuildErrors(routing, published, waits - 1));
            later.play();
            return;
        }
        d.panel().clearConsole();
        d.s().consoleStarted = false;
        List<String> lines = JavaLaunchPrep.javaErrorLines(found, scope, d.ops.projectRoot(), MAX_BUILD_ERROR_LINES);
        for (String line : lines) {
            d.panel().appendOutput(line + "\n", "stderr");
        }
        if (errors > lines.size()) {
            d.panel().appendNotice(tr("debug.console.moreBuildErrors", errors - lines.size()));
        }
        // A failed Maven/Gradle import is no compile error: it says so, and where its JDK is chosen (review 2, A103).
        for (String message : importErrors) {
            d.panel().appendOutput(message + "\n", "stderr");
        }
        boolean importFailed = errors == 0 && !importErrors.isEmpty();
        d.panel().appendNotice(tr(importFailed ? "debug.console.importFailed" : "debug.console.buildFailed"));
        d.host.setStatus(
                importFailed
                        ? tr("status.debug.importFailed")
                        : errors > 0 ? tr("status.debug.buildErrors", errors) : tr("status.debug.buildErrorsUnlisted"));
        d.panel().setLaunchRefused(tr(importFailed ? "debugpanel.state.importFailed" : "debugpanel.state.buildFailed"));
        d.updateDebugAvailability();
    }

    private static String readQuietly(Path file) {
        return DebugPlanning.readQuietly(file);
    }

    /** How many times a failed build's errors are looked for again before the console says only that there are some. */
    private static final int BUILD_ERROR_WAITS = 8;

    /** How many compile errors the Debug console lists before saying how many more there are. */
    private static final int MAX_BUILD_ERROR_LINES = 50;

    /** Restart of a session started from {@code file}'s tab: repeat it there, or plainly if the tab is gone. */
    void relaunchFor(Path file, Consumer<EditorBuffer> launch) {
        EditorBuffer b = d.ops.bufferForPath(file);
        if (b != null) {
            launch.accept(b);
        } else {
            d.dap().restart();
        }
    }

    /**
     * {@code debug.mainClass}: debug an arbitrary main class in the active file's Maven/Gradle project (not
     * just the active file's own). Requires a Java file open in a Maven/Gradle project to route jdtls; jdtls
     * enumerates every project main class, the user picks one (when several), and it launches with the project
     * root as the working directory.
     */
    void debugMainClass() {
        startMainClassDebug(null);
    }

    /** Debugs a specific main class by fully-qualified name (the gutter ▶ / editor menu on a {@code main}). */
    void debugMainClassNamed(String fqn) {
        startMainClassDebug(fqn);
    }

    /**
     * Debugs a saved {@link RunConfiguration} — its main class, program args, VM args, environment and
     * working directory, and its before-launch step.
     *
     * <p>Java, Groovy, Kotlin, Clojure, Scala, Python, Node.js and npm, and C, C++, Go and Rust: the other script
     * types run but do not debug yet.
     */
    void debugConfig(RunConfiguration cfg) {
        if (cfg.isDeployed()) {
            d.ops.debugDeployed(cfg); // the program runs on the host; the debugger attaches through a tunnel
            return;
        }
        if (com.editora.run.ScriptRunCommand.isJvmScript(cfg.type())) {
            debugJvmScriptConfig(cfg);
            return;
        }
        if (com.editora.run.ScriptRunCommand.NODE.equals(cfg.type())
                || com.editora.run.ScriptRunCommand.NPM.equals(cfg.type())) {
            d.debugNodeConfig(cfg); // planned as Run plans it (NodeRunPlan / the package manager)
            return;
        }
        if (com.editora.run.ScriptRunCommand.isCompiled(cfg.type())) {
            debugCompiledConfig(cfg); // built and launched as Debug File does its source file
            return;
        }
        if (com.editora.run.ScriptRunCommand.isPython(cfg.type())) {
            d.python.debugConfig(cfg); // a script or module, planned as Run plans it
            return;
        }
        if (!cfg.isJava()) {
            // Script types run but do not debug yet. Say so plainly: falling through would resolve a Java
            // main class that a Python configuration does not have and report a confusing Java error.
            d.host.setStatus(
                    com.editora.run.ScriptRunCommand.SHELL.equals(cfg.type())
                            ? tr("status.debug.shellNotDebuggable")
                            : tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type." + cfg.type())));
            return;
        }
        // Same guard as the Run path: a blank main class is an internal NPE from jdtls, not an error.
        if (cfg.missingMainClass()) {
            d.host.setStatus(tr("status.run.configNeedsMainClass", cfg.name()));
            d.ops.editConfiguration(cfg.name()); // take them to the field that would make it run
            return;
        }
        if (cfg.mainClassLooksLikeAFile()) { // see the Run path: an empty classpath, not an error
            d.host.setStatus(tr("status.run.mainClassIsAFile", cfg.mainClass()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        // Same reasoning as RunCoordinator.runConfig: a named configuration must not depend on which tab is
        // in front, only on a Java file being open somewhere in its project.
        Path routing = RunCoordinator.routingFor(d.host, cfg);
        if (!d.debugEffectiveFor("java", routing)) { // a remote project is debugged on its host (review A9)
            d.host.setStatus(d.unavailableMessage("java", routing));
            return;
        }
        if (routing == null) {
            d.host.setStatus(tr("status.run.configNeedsJavaFile"));
            return;
        }
        Path window = d.ops.projectRoot();
        String jdk =
                d.host.projectScope().jdkHome(d.host.settings()); // the project and the plan are read off the FX thread
        d.planOffFx("debug-config-plan", () -> DebugPlanning.planJava(cfg, routing, window, jdk), a -> {
            if (a.value() != null) {
                debugJavaConfig(cfg, routing, a.value());
            }
        });
    }

    private void debugJavaConfig(RunConfiguration cfg, Path routing, DebugPlanning.JavaRead read) {
        Path buildRoot = read.buildRoot();
        Path root = read.root();
        com.editora.run.JavaLaunchPlan.Result planned = read.planned();
        if (root == null || planned == null) {
            d.host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (!d.factsKnown(
                "debug-java-build-look",
                d.facts.javaConfig(cfg, buildRoot, root),
                () -> debugJavaConfig(cfg, routing, read))) {
            return;
        }
        if (!d.savedOrThen(javaBuffers(routing, root, true), () -> debugJavaConfig(cfg, routing, read))) {
            return; // every unsaved Java file of the project is written first (review B3)
        }
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        if (plan == null) { // not ok: the plan names its problem
            if (planned.problem() instanceof com.editora.run.JavaLaunchPlan.Problem problem) {
                String quote = String.valueOf(problem.quote());
                d.host.setStatus(
                        !problem.envFile().isEmpty()
                                ? tr("status.run.envFileUnreadable", cfg.name(), problem.envFile())
                                : problem.field() == com.editora.run.JavaLaunchPlan.Field.ENV
                                        ? tr("status.run.envUnclosedQuote", cfg.name(), quote)
                                        : tr("status.run.argsUnclosedQuote", quote));
            }
            d.ops.editConfiguration(cfg.name());
            return;
        }
        // The same gate the Run path has always had: a failed build aborts the launch rather than debugging
        // the previous class files, where every breakpoint would sit on a stale line number.
        //
        // Placed after the guards above rather than around the whole method — unlike Run, which wraps its
        // dispatch — because those guards reject a configuration that cannot launch at all (a script type, a
        // blank main class), and spending a multi-minute build on one before saying so is worse than not
        // building.
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        // Restart reads the configuration again: an edit made since the first launch applies (review A14).
        String name = cfg.name();
        d.s().relaunch = () -> debugConfig(d.currentConfiguration(name, cfg));
        boolean plainFolder = buildRoot == null;
        Runnable launch = () -> {
            // routing may be a background tab whose server start was deferred; open it on jdtls first, or the
            // resolve below comes back "no language server for file" while jdtls is running perfectly.
            d.lsp.ensureManaged(routing);
            d.dap().resolveMainClasses(routing, options -> {
                DapManager.MainClassOption match =
                        DebugLaunchArgs.matchConfiguredClass(options, cfg.mainClass(), plainFolder);
                if (match == null) {
                    d.host.setStatus(
                            options.isEmpty()
                                    ? tr("status.debug.noMainClassYet")
                                    : tr("status.debug.configClassNotFound", cfg.name(), cfg.mainClass()));
                    return;
                }
                d.ops.openToolWindow();
                d.nameSession(shortName(match.mainClass()));
                d.dap().setProgramArgs(plan.args());
                d.dap().setVmArgs(plan.vmArgs());
                d.dap().setEnv(plan.environment());
                // On a remote host the plan names the host's configured JDK, as Run uses it (review D9).
                boolean onHost = !com.editora.process.ExecTargets.runsHere(routing);
                d.dap().setHostJavaExec(onHost ? plan.javaExecutable() : "");
                String java = onHost ? "" : plainFolder ? looseJava(plan.javaExecutable()) : plan.javaExecutable();
                d.withClosedBreakpoints(
                        () -> d.dap().startLaunchMainClass(routing, match, plan.cwd(), java, plainFolder));
            });
        };
        // A before-launch step builds the project the user's way, in the project root (review A12); without
        // one, jdtls builds it (review A2). A plain folder is built by jdtls after any step (review 2, A101).
        boolean hasStep = cfg.beforeLaunch() != null && !cfg.beforeLaunch().isBlank();
        Path toolRoot = buildRoot != null ? buildRoot : root;
        d.beforeLaunch()
                .run(
                        d.host,
                        cfg,
                        tool -> d.ops.buildExecutable(
                                tool, toolRoot), // a symbolic step (maven:compile) runs the tree's Maven
                        root,
                        plan.jdkEnvironment(),
                        d.beforeLaunchConsole(),
                        () -> {
                            if (plainFolder) {
                                looseBuildThenLaunch(routing, launch); // after its own step too: jdtls's output (A101)
                            } else if (!hasStep) {
                                buildToolThenLaunch(
                                        buildRoot, routing, shortName(cfg.mainClass()), plan.jdkEnvironment(), launch);
                            } else { // the user's step built it; a Gradle launch still runs jdtls's bin/main (A202)
                                afterToolBuild(
                                                buildRoot == null ? null : BuildBeforeLaunch.toolAt(buildRoot),
                                                routing,
                                                launch)
                                        .run();
                            }
                        });
    }

    /**
     * The build before a main-class launch in a Maven or Gradle project: with "Build before Run and Debug" on,
     * the build tool's compile ({@code test-compile} / {@code compileTestJava} for a class under
     * {@code src/test}) in the Debug console, through the executable the tasks tree uses, aborting the launch
     * when it fails (review D2) — the tool is the authority on how the project compiles, where jdtls's own
     * build knows nothing of annotation processors or generated sources; with the setting off, jdtls's
     * workspace build as before (review A2). {@code source} decides the scope; {@code env} is the JDK's.
     */
    private void buildToolThenLaunch(
            @Nullable Path buildRoot, Path source, String label, Map<String, String> env, Runnable launch) {
        if (buildRoot != null
                && !d.factsKnown(
                        "debug-build-before-look",
                        d.facts.buildRoot(buildRoot),
                        () -> buildToolThenLaunch(buildRoot, source, label, env, launch))) {
            return;
        }
        com.editora.build.BuildTool tool = buildRoot != null && com.editora.process.ExecTargets.runsHere(buildRoot)
                ? BuildBeforeLaunch.toolAt(buildRoot)
                : null;
        if (tool == null
                || buildRoot == null
                || !BuildBeforeLaunch.wanted(d.host.settings().isBuildBeforeRun(), "", tool)) {
            buildThenLaunch(source, launch);
            return;
        }
        List<String> executable = d.ops.buildExecutable(tool, buildRoot);
        if (executable == null) {
            return; // the trust gate said why
        }
        List<String> argv =
                BuildBeforeLaunch.compileArgv(tool, executable, BuildBeforeLaunch.isTestSource(source, buildRoot));
        d.beforeLaunch()
                .run(
                        d.host,
                        new BeforeLaunchStep.Command(label, argv, "status.run.buildBeforeLaunch"),
                        buildRoot,
                        env,
                        d.beforeLaunchConsole(),
                        afterToolBuild(tool, source, launch));
        d.updateDebugAvailability();
    }

    /** {@code launch}, after jdtls's build too when the launch runs jdtls's output, not the tool's (Gradle, A202). */
    private Runnable afterToolBuild(com.editora.build.@Nullable BuildTool tool, Path source, Runnable launch) {
        return BuildBeforeLaunch.jdtlsBuildsAfter(tool) ? () -> buildThenLaunch(source, launch) : launch;
    }

    /**
     * Debugs a Groovy, Clojure or Scala script configuration: its script, arguments, folder, environment, JDK
     * and VM arguments (where the tool hands them to its JVM, beside the debugger's agent: Scala CLI's as
     * {@code --java-opt}), after its before-launch step.
     * On this machine only: a configuration whose folder is on a remote host runs there but does not debug yet.
     */
    private void debugJvmScriptConfig(RunConfiguration cfg) {
        List<String> args = d.debugArgs(cfg.args());
        if (args == null) {
            return;
        }
        Path root = d.ops.projectRoot(); // its files are read off the FX thread (PERF-01)
        d.planOffFx("debug-config-plan", () -> DebugPlanning.readConfig(cfg, root, false, true), a -> {
            if (a.value() != null) {
                debugJvmScriptConfig(cfg, args, a.value());
            }
        });
    }

    private void debugJvmScriptConfig(RunConfiguration cfg, List<String> args, DebugPlanning.ConfigRead read) {
        Path cwd = read.cwd();
        Path script = read.script();
        if (cwd == null || script == null) {
            d.host.setStatus(tr("status.run.configNeedsTarget", cfg.name()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        if (!com.editora.process.ExecTargets.runsHere(script)) {
            d.host.setStatus(tr("debug.error.remoteLanguage"));
            return;
        }
        if (read.scriptMissing()) {
            d.host.setStatus(tr("status.run.scriptMissing", cfg.name(), script.toString()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        if (!d.debugEffectiveFor(cfg.type(), script)) {
            d.host.setStatus(d.unavailableMessage(cfg.type(), script));
            return;
        }
        if (DebugPlanning.envProblem(d.host, d.ops::editConfiguration, cfg, read.env(), read.envError())) {
            return;
        }
        java.util.Map<String, String> env = read.env();
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        if (!d.factsKnown(
                        "debug-jvm-script-look", d.facts.gate(read.gate()), () -> debugJvmScriptConfig(cfg, args, read))
                || !d.trusted(read.gate())) { // the project's trust first, as Run asks it, so "no" starts nothing
            return;
        }
        String jdkHome = cfg.jdkHome().isBlank() ? d.host.projectScope().jdkHome(d.host.settings()) : cfg.jdkHome();
        java.util.Map<String, String> launchEnv = new java.util.LinkedHashMap<>(
                com.editora.run.JdkToolchain.environment(jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        launchEnv.putAll(env);
        // A Kotlin program is started by `java` itself: its VM arguments go on that command line.
        boolean program = com.editora.run.ScriptRunCommand.KOTLIN.equals(cfg.type())
                && com.editora.run.KotlinRun.shape(script.getFileName().toString())
                        == com.editora.run.KotlinRun.Shape.PROGRAM;
        java.util.Map<String, String> programEnv = launchEnv;
        // A Clojure file runs in its project's folder unless the configuration names one.
        Path folder =
                cfg.workingDir().isBlank() && com.editora.run.ScriptRunCommand.CLOJURE.equals(cfg.type()) ? null : cwd;
        String name = cfg.name();
        d.s().relaunch = () -> debugConfig(d.currentConfiguration(name, cfg));
        d.beforeLaunch()
                .run(
                        d.host,
                        cfg,
                        cwd,
                        com.editora.run.JdkToolchain.environment(
                                jdkHome, com.editora.process.ProcessRunner.augmentedPath()),
                        d.beforeLaunchConsole(),
                        () -> {
                            d.ops.openToolWindow();
                            d.nameSession(cfg.name());
                            d.dap().setProgramArgs(args);
                            if (program || com.editora.run.JvmScriptRun.takesJvmOptionsInArgv(cfg.type())) {
                                // `java` (a Kotlin program) and Scala CLI take VM arguments on their command line.
                                d.dap().setVmArgs(com.editora.run.ProgramArgs.tokenize(cfg.vmArgs()));
                            } else {
                                d.dap()
                                        .setVmArgs(cfg.vmArgs()
                                                .strip()); // placed where the tool reads them (JvmScriptRun)
                            }
                            d.dap().setHostJavaExec("");
                            d.dap().setEnv(programEnv);
                            if (program) {
                                debugKotlinProgram(
                                        script, cwd, com.editora.run.JdkToolchain.javaExecutable(jdkHome), programEnv);
                            } else {
                                d.withClosedBreakpoints(() -> d.dap().startJvmScript(script, cfg.type(), folder));
                            }
                        });
    }

    /**
     * Debugs a C, C++, Go or Rust configuration: its source file exactly as Debug File debugs it — a C or C++ file's
     * CMake or Make project built, or the file alone compiled, and the program on CodeLLDB (or GDB); a Go file's
     * package, or the file alone, on Delve; a Rust file's Cargo package built and its program on CodeLLDB — with the
     * configuration's arguments, its variables in the program's environment, its working directory when it names
     * one (blank: where Debug File runs the program), after its before-launch step. No caret picks a test: a Go or
     * Rust test file debugs all its tests. On this machine only.
     */
    private void debugCompiledConfig(RunConfiguration cfg) {
        List<String> args = d.debugArgs(cfg.args());
        if (args == null) {
            return;
        }
        Path root = d.ops.projectRoot(); // its files are read off the FX thread (PERF-01)
        boolean text = com.editora.run.ScriptRunCommand.GO.equals(cfg.type())
                || com.editora.run.ScriptRunCommand.RUST.equals(cfg.type());
        d.planOffFx("debug-config-plan", () -> DebugPlanning.readConfig(cfg, root, text, false), a -> {
            if (a.value() != null) {
                debugCompiledConfig(cfg, args, a.value());
            }
        });
    }

    private void debugCompiledConfig(RunConfiguration cfg, List<String> args, DebugPlanning.ConfigRead read) {
        Path cwd = read.cwd();
        Path source = read.script();
        if (cwd == null || source == null) {
            d.host.setStatus(tr("status.run.configNeedsTarget", cfg.name()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        if (!com.editora.process.ExecTargets.runsHere(source)) {
            d.host.setStatus(tr("debug.error.remoteLanguage"));
            return;
        }
        if (read.scriptMissing()) {
            d.host.setStatus(tr("status.run.scriptMissing", cfg.name(), source.toString()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        boolean ownDir = !cfg.workingDir().isBlank();
        if (read.dirMissing()) {
            d.host.setStatus(tr("status.run.workingDirMissing", cfg.name(), cwd.toString()));
            d.ops.editConfiguration(cfg.name());
            return;
        }
        // C and C++ share one adapter (CodeLLDB, or GDB), registered as C++'s.
        String type = cfg.type();
        boolean nativeCode =
                com.editora.run.ScriptRunCommand.C.equals(type) || com.editora.run.ScriptRunCommand.CPP.equals(type);
        String language = nativeCode ? "cpp" : type;
        if (!d.debugEffectiveFor(language, source)) {
            d.host.setStatus(d.unavailableMessage(language, source));
            return;
        }
        if (DebugPlanning.envProblem(d.host, d.ops::editConfiguration, cfg, read.env(), read.envError())) {
            return;
        }
        java.util.Map<String, String> env = read.env();
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        String name = cfg.name();
        boolean hasStep = !cfg.beforeLaunch().isBlank();
        DebugCoordinator.LaunchExtras extras =
                new DebugCoordinator.LaunchExtras(env, ownDir ? cwd : null, hasStep, true);
        Runnable launch = () -> {
            d.ops.openToolWindow();
            d.s().announceStart = true;
            if (com.editora.run.ScriptRunCommand.GO.equals(type)) {
                d.debugGo(source, sourceText(source, read.text()), -1, args, extras);
            } else if (com.editora.run.ScriptRunCommand.RUST.equals(type)) {
                d.debugRust(source, sourceText(source, read.text()), -1, args, extras);
            } else if (com.editora.run.ScriptRunCommand.DART.equals(type)) {
                d.dart.debug(source, args, extras.env(), extras.cwd(), true);
            } else {
                d.debugNative(source, com.editora.run.ScriptRunCommand.CPP.equals(type), args, extras);
            }
            d.nameSession(name);
            // Restart reads the configuration again: an edit made since the first launch applies.
            d.s().relaunch = () -> debugConfig(d.currentConfiguration(name, cfg));
        };
        d.beforeLaunch()
                .run(
                        d.host,
                        cfg,
                        cwd,
                        java.util.Map.of(),
                        d.beforeLaunchConsole(),
                        // after the step has ended: the build that follows is not refused as "still running"
                        () -> d.later(launch));
    }

    /** The text of {@code file}: its open tab's (edits not saved yet count), else what is on disk, else none. */
    private String sourceText(Path file, String onDisk) {
        String[] open = new String[1];
        d.host.forEachBuffer(b -> {
            if (open[0] == null && b.getPath() != null && DebugCoordinator.samePath(b.getPath(), file)) {
                open[0] = b.getContent();
            }
        });
        return open[0] != null ? open[0] : onDisk; // what the plan read (empty: unreadable)
    }

    /**
     * Debugs Kotlin program {@code source}: compiled by {@code kotlinc} into its cached jar (in the Debug
     * console, as a step Stop can end) unless this content's jar is there, then run as {@code java -jar} with the
     * debugger's agent, in {@code cwd} (null: its folder), on {@code java}. The arguments, VM arguments and
     * environment are already set on the session.
     */
    void debugKotlinProgram(Path source, @Nullable Path cwd, String java, java.util.Map<String, String> env) {
        Path config = d.host.configDir(); // the compiler, the source's hash and the jar cache: off the FX thread
        d.planOffFx("debug-kotlin-plan", () -> DebugPlanning.readKotlin(source, config), a -> {
            var read = a.value();
            if (a.failure() != null || read == null) {
                d.host.setStatus(tr("debug.error.startFailed", a.why()));
            } else if (read.plan() == null) {
                d.host.setStatus(tr(com.editora.run.JvmScriptRun.missingToolKey("kotlin")));
            } else {
                debugKotlinPlanned(source, cwd, java, env, read.plan());
            }
        });
    }

    private void debugKotlinPlanned(
            Path source,
            @Nullable Path cwd,
            String java,
            java.util.Map<String, String> env,
            com.editora.run.KotlinBuild.Plan plan) {
        String javaExec = java == null || java.isBlank() ? "java" : java;
        Runnable start = () -> {
            d.ops.openToolWindow();
            d.withClosedBreakpoints(() -> d.dap().startJvmProgram(source, "kotlin", cwd, plan.jar(), javaExec));
        };
        if (plan.compile().isEmpty()) {
            start.run();
            return;
        }
        if (d.beforeLaunchActive() || d.s().pendingBuild != null) {
            d.host.setStatus(tr("status.run.busy"));
            return;
        }
        d.beforeLaunch()
                .run(
                        d.host,
                        new BeforeLaunchStep.Command(
                                source.getFileName().toString(), plan.compile(), "status.run.kotlinCompile"),
                        cwd != null ? cwd : source.getParent(),
                        env,
                        d.beforeLaunchConsole(),
                        () -> d.planOffFx(
                                "debug-kotlin-finish",
                                () -> {
                                    com.editora.run.KotlinBuild.finish(plan);
                                    return plan;
                                },
                                a -> {
                                    if (a.failure() != null) {
                                        d.host.setStatus(tr("debug.error.startFailed", a.why()));
                                    } else {
                                        start.run();
                                    }
                                }));
        d.updateDebugAvailability();
    }

    private void startMainClassDebug(@Nullable String targetFqn) {
        startMainClassDebug(d.host.activeBuffer(), targetFqn);
    }

    private void startMainClassDebug(@Nullable EditorBuffer b, @Nullable String targetFqn) {
        if (b != null && b.getLanguage() != null && !"java".equals(b.getLanguage())) {
            // Said first: "turn on LSP for Java" on a JavaScript file sent the user the wrong way (review D7).
            d.host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        if (!d.debugEffectiveFor("java", b == null ? null : b.getPath())) {
            d.host.setStatus(d.unavailableMessage("java", b == null ? null : b.getPath()));
            return;
        }
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            d.host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        Path routing = b.getPath();
        if (!d.factsKnown(
                "debug-main-class-look", d.facts.mainClass(routing), () -> startMainClassDebug(b, targetFqn))) {
            return;
        }
        Path buildRoot = JavaProjectRoot.find(routing);
        // A plain-folder project window has main classes too (review A16).
        Path root = JavaLaunchPrep.projectOf(routing, buildRoot, d.ops.projectRoot());
        if (root == null) {
            d.host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (!d.savedOrThen(javaBuffers(routing, root, true), () -> startMainClassDebug(b, targetFqn))) {
            return;
        }
        boolean plainFolder = buildRoot == null;
        d.dap().resolveMainClasses(routing, options -> {
            if (options.isEmpty()) {
                d.host.setStatus(tr("status.debug.noMainClass"));
                return;
            }
            Consumer<DapManager.@Nullable MainClassOption> launch = opt -> {
                if (opt == null) {
                    return;
                }
                // The configuration for this class is how the project runs it, as for Run ▶ (review D1).
                RunConfiguration configured = plainFolder || opt.className() == null
                        ? null
                        : com.editora.run.JavaLaunchPlan.configurationFor(opt.className(), d.ops.runConfigurations());
                if (configured != null) {
                    debugConfig(configured);
                    return;
                }
                List<String> mainArgs = d.debugArgs(programArgsForMain(opt, routing));
                if (mainArgs == null) {
                    return;
                }
                d.ops.openToolWindow();
                d.nameSession(shortName(opt.mainClass()));
                d.dap().setProgramArgs(mainArgs);
                d.dap().setVmArgs(""); // the gutter/command debug carries no VM args/env
                d.dap().setHostJavaExec("");
                d.dap().setEnv(configuredJdkEnvironment(buildRoot, null));
                d.lsp.ensureManaged(routing); // see above
                d.s().relaunch = () -> relaunchFor(routing, again -> startMainClassDebug(again, opt.className()));
                String javaExec = configuredJavaExecutable(buildRoot, null);
                Runnable go = () -> d.withClosedBreakpoints(
                        () -> d.dap().startLaunchMainClass(routing, opt, root, javaExec, plainFolder));
                if (plainFolder) {
                    looseBuildThenLaunch(routing, go); // built like a loose file (review 2, A101)
                } else {
                    Path source =
                            opt.filePath() == null || opt.filePath().isBlank() ? routing : Path.of(opt.filePath());
                    buildToolThenLaunch(
                            buildRoot,
                            source,
                            shortName(opt.mainClass()),
                            configuredJdkEnvironment(buildRoot, null),
                            go);
                }
            };
            if (targetFqn != null) {
                DapManager.MainClassOption match =
                        DebugLaunchArgs.matchConfiguredClass(options, targetFqn, plainFolder);
                if (match == null) {
                    d.host.setStatus(tr("status.debug.noMainClass"));
                    return;
                }
                launch.accept(match);
            } else if (options.size() == 1) {
                launch.accept(options.get(0));
            } else {
                pickMainClass(options, launch);
            }
        });
    }

    /** Per-main-class program args, reusing the per-file store keyed by the main class's own source file. */
    private String programArgsForMain(DapManager.MainClassOption opt, Path routing) {
        if (opt.filePath() == null || opt.filePath().isBlank()) {
            return "";
        }
        try {
            // jdtls names the file as the machine it runs on does (a remote host's, a container's path).
            return d.ops.programArgs(
                    com.editora.process.ExecTargets.forPath(routing).toPath(opt.filePath()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The default JDK for a loose file or Maven project; a saved configuration may override it. */
    String configuredJdkHome(@Nullable Path root, @Nullable RunConfiguration cfg) {
        if (root != null && !com.editora.process.ExecTargets.runsHere(root)) {
            return ""; // a JDK setting names a folder on this machine; the jdtls where it runs resolves its own
        }
        // A Gradle (or any other) project runs on its build's own JDK, unless it names one itself (.sdkmanrc, its
        // settings): its build then compiles with that JDK, and the program must run on it too.
        if (root != null
                && !RunOpsHost.isMavenProject(root) // the plan looked at it off the FX thread
                && !d.host.projectScope().projectJdk()) {
            return "";
        }
        return com.editora.run.JdkToolchain.effectiveHome(
                cfg == null ? "" : cfg.jdkHome(), d.host.projectScope().jdkHome(d.host.settings()));
    }

    private String configuredJavaExecutable(@Nullable Path root, @Nullable RunConfiguration cfg) {
        String selected = com.editora.run.JdkToolchain.javaExecutable(presentJdk(configuredJdkHome(root, cfg)));
        // No build tool (a plain folder): the Settings JDK, else PATH's java, as Run (review 2, B102).
        return root == null ? looseJava(selected) : selected;
    }

    private java.util.Map<String, String> configuredJdkEnvironment(
            @Nullable Path root, @Nullable RunConfiguration cfg) {
        String home = configuredJdkHome(root, cfg);
        return com.editora.run.JdkToolchain.environment(
                com.editora.run.JdkToolchain.isGone(home) ? "" : home,
                com.editora.process.ProcessRunner.augmentedPath());
    }

    /** The simple class name of a fully-qualified main class (for the session label). */
    private static String shortName(@Nullable String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** Main-class chooser (QuickOpen) when jdtls finds several. */
    void pickMainClass(
            List<DapManager.MainClassOption> options, Consumer<DapManager.@Nullable MainClassOption> chosen) {
        closeMainClassPicker();
        QuickOpen<DapManager.MainClassOption> picker = new QuickOpen<>(
                tr("debug.pickMainTitle"),
                tr("debug.pickMainPrompt"),
                () -> options,
                o -> o.mainClass() == null ? "" : o.mainClass(),
                o -> JavaLaunchPrep.projectLabel(o.projectName()),
                o -> chosen.accept(o));
        // Esc ends the launch it was asked for: it used to sit in "Starting…" with nothing left to start.
        picker.setOnCancelled(() -> chosen.accept(null));
        picker.setOverlayHost(d.host.overlayHost());
        mainClassPicker = picker;
        picker.show(d.host.window());
    }

    /** The main-class picker last shown, while it may still be open. */
    private @Nullable QuickOpen<DapManager.MainClassOption> mainClassPicker;

    /** Closes the main-class picker if it is still open; its answer goes to a launch that no longer waits. */
    void closeMainClassPicker() {
        if (!d.shown()) {
            return; // another session's: the editor shows the focused one's
        }
        QuickOpen<DapManager.MainClassOption> picker = mainClassPicker;
        mainClassPicker = null;
        if (picker != null && picker.isShown()) {
            picker.hide();
        }
    }

    /** The main-class picker, while it is open (tests). */
    @Nullable
    QuickOpen<DapManager.MainClassOption> openMainClassPicker() {
        QuickOpen<DapManager.MainClassOption> picker = mainClassPicker;
        return picker != null && picker.isShown() ? picker : null;
    }
}
