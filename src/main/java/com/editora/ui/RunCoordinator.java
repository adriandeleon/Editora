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
import com.editora.run.RunConfigRouting;
import com.editora.run.RunConfigWorkingDirectory;
import com.editora.run.RunService;
import com.editora.run.ScriptRunCommand;
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
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final RunService service = new RunService();
    private final RunPanel panel;

    /** The most recent launch, for {@code run.rerun} and the shared stack-trace link resolver. */
    private Path lastRunDir;

    private java.util.Map<String, String> lastRunEnv = java.util.Map.of();
    private String lastRunLabel;
    private List<String> lastRunCommand;

    RunCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.panel = new RunPanel(this::stopRun);
        panel.setOnInput(service::sendInput); // stdin field → the running process
        panel.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
        panel.setOnUrl(ops::openUrl); // single-clicked HTTP(S) URL → browser
    }

    RunPanel panel() {
        return panel;
    }

    /** Working directory of the most recent run, or {@code null} — used by the shared link resolver. */
    Path lastRunDir() {
        return lastRunDir;
    }

    void runActiveFile() {
        runActiveFile(false);
    }

    /** Prompts for program arguments (pre-filled with the file's remembered ones), then runs. */
    void runActiveFileWithArgs() {
        runActiveFile(true);
    }

    /** Re-runs the most recent run (same file + argv) without touching the active tab. */
    void rerunLast() {
        if (lastRunCommand == null || lastRunDir == null) {
            host.setStatus(tr("status.run.noRerun"));
            return;
        }
        if (!beginRunRequest()) {
            return;
        }
        streamRun(lastRunLabel, lastRunDir, lastRunCommand, lastRunEnv);
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
            if (b.getPath() != null && host.isLocalBuffer(b) && "java".equals(b.getLanguage())) {
                open.add(b.getPath());
            }
        });
        EditorBuffer active = host.activeBuffer();
        Path activeJava = active != null
                        && active.getPath() != null
                        && host.isLocalBuffer(active)
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
        List<String> argv = ScriptRunCommand.build(cfg.type(), cfg.target(), ProgramArgs.tokenize(cfg.args()));
        if (argv.isEmpty()) {
            if (ScriptRunCommand.needsTarget(cfg.type())) {
                reportIncomplete(cfg, "status.run.configNeedsTarget");
            } else {
                // An unknown type is not something the form can put right by being opened at it.
                host.setStatus(tr("status.run.configBadType", cfg.name()));
            }
            return;
        }
        Path cwd = RunConfigWorkingDirectory.resolve(cfg, ops.projectRoot());
        if (cwd == null) {
            host.setStatus(tr("status.run.configNeedsWorkingDir", cfg.name()));
            return;
        }
        streamRun(cfg.name(), cwd, argv, com.editora.run.EnvVars.parse(cfg.env()));
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

    /** A before-launch step is usually a build, so it gets a generous ceiling rather than a quick-probe one. */
    private static final java.time.Duration BEFORE_LAUNCH_TIMEOUT = java.time.Duration.ofMinutes(10);

    /** Whether a program is currently running, so the toolbar Stop button can reflect it. */
    boolean isRunning() {
        return service.isRunning();
    }

    /** Runs a saved {@link RunConfiguration}: its main class with its own program/VM args + working dir. */
    void runConfig(RunConfiguration cfg) {
        if (!beginRunRequest()) {
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

    /** {@link #withBeforeLaunch(CoordinatorHost, RunConfiguration, Path, Runnable)} at this coordinator's own
     *  working directory. */
    private void withBeforeLaunch(RunConfiguration cfg, Runnable then) {
        Path cwd = beforeLaunchDir(cfg);
        Path routing = routingFor(host, cfg);
        Path project = routing == null ? ops.projectRoot() : ops.javaProjectRoot(routing);
        String jdkHome = effectiveMavenJdk(project, cfg);
        withBeforeLaunch(host, cfg, cwd, JdkToolchain.environment(jdkHome, processPath()), then);
    }

    /**
     * Runs {@code cfg}'s before-launch command, if it has one, then {@code then} — or reports the failure and
     * runs nothing.
     *
     * <p>The command runs <b>off the FX thread</b> (it is a build; it can take minutes) and {@code then} is
     * marshalled back on, so everything after it keeps the single-threaded UI assumption the rest of this
     * class is written against. With no before-launch step this is a straight call, not a thread hop, so the
     * common case is unchanged.
     *
     * <p>Static and package-visible so {@link DebugCoordinator} launches through the same gate. It used to be
     * private, and the debug path simply had no before-launch step — so a configuration whose build was
     * {@code mvn -q compile} compiled when you pressed Run and silently debugged the previous class files
     * when you pressed Debug, which is the exact failure the step exists to prevent, in the one mode where a
     * stale line number is most confusing. The caller supplies {@code cwd} because each coordinator resolves
     * the project root its own way.
     */
    static void withBeforeLaunch(CoordinatorHost host, RunConfiguration cfg, Path cwd, Runnable then) {
        withBeforeLaunch(host, cfg, cwd, java.util.Map.of(), then);
    }

    /** As above, with a selected toolchain environment for Maven/JDK-aware build steps. */
    static void withBeforeLaunch(
            CoordinatorHost host,
            RunConfiguration cfg,
            Path cwd,
            java.util.Map<String, String> environment,
            Runnable then) {
        String command = cfg.beforeLaunch();
        if (command == null || command.isBlank()) {
            then.run();
            return;
        }
        List<String> argv = ProgramArgs.tokenize(command);
        if (argv.isEmpty()) {
            then.run();
            return;
        }
        host.setStatus(tr("status.run.beforeLaunch", cfg.name()));
        Thread worker = new Thread(
                () -> {
                    com.editora.process.ProcessRunner.Result r =
                            com.editora.process.ProcessRunner.run(cwd, BEFORE_LAUNCH_TIMEOUT, argv, environment);
                    javafx.application.Platform.runLater(() -> {
                        if (r.ok()) {
                            then.run();
                        } else {
                            // Surface the tool's own output: "before-launch failed" alone tells the user
                            // nothing about which step or why.
                            host.setStatus(tr("status.run.beforeLaunchFailed", cfg.name(), firstLine(r)));
                        }
                    });
                },
                "run-before-launch");
        worker.setDaemon(true);
        worker.start();
    }

    /** Where a before-launch command runs: the configuration's working directory, else the project root. */
    private Path beforeLaunchDir(RunConfiguration cfg) {
        Path configured = RunConfigWorkingDirectory.resolve(cfg, ops.projectRoot());
        if (configured != null) {
            return configured;
        }
        Path routing = routingFor(host, cfg);
        Path root = routing == null ? null : ops.javaProjectRoot(routing);
        return root != null ? root : Path.of(System.getProperty("user.dir"));
    }

    /** The most useful single line of a failed command's output — stderr if it said anything, else stdout. */
    private static String firstLine(com.editora.process.ProcessRunner.Result r) {
        String text = r.err() == null || r.err().isBlank() ? r.out() : r.err();
        if (text == null || text.isBlank()) {
            return "exit " + r.exit();
        }
        String[] lines = text.strip().split("\\R");
        return lines[lines.length - 1]; // the last line: a build tool's summary, not its banner
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
        if (b != null && b.isDirty() && host.isLocalBuffer(b) && !ops.saveBuffer(b)) {
            return; // save whatever the user was editing before launching, as before
        }
        Path cwd = cfg.workingDir().isBlank() ? root : Path.of(cfg.workingDir());
        List<String> vm = ProgramArgs.tokenize(cfg.vmArgs());
        List<String> args = ProgramArgs.tokenize(cfg.args());
        java.util.Map<String, String> env = com.editora.run.EnvVars.parse(cfg.env());
        String jdkHome = effectiveMavenJdk(root, cfg);
        java.util.Map<String, String> launchEnv = launchEnvironment(jdkHome, env);
        String javaOverride = JdkToolchain.javaExecutable(jdkHome);
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
                        .filter(o -> cfg.mainClass().equals(o.fqn()))
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
                                    vm,
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
                        JavaRunCommand.build(javaOverride, List.of(), cp, cfg.mainClass(), vm, args),
                        launchEnv);
            });
        } else {
            host.setStatus(tr("status.run.javaUnavailable"));
        }
    }

    private void startMainClassRun(String targetFqn) {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || !host.isLocalBuffer(b) || !"java".equals(b.getLanguage())) {
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
                        .filter(mc -> targetFqn.equals(mc.fqn()))
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
        String jdkHome = effectiveMavenJdk(root, null);
        ops.resolveMavenClasspath(root, jdkHome, cp -> {
            if (cp == null || cp.isEmpty()) {
                host.setStatus(tr("status.run.resolveFailed"));
                return;
            }
            List<String> args = ProgramArgs.tokenize(ops.programArgs(b.getPath()));
            List<String> command =
                    JavaRunCommand.build(JdkToolchain.javaExecutable(jdkHome), List.of(), cp, m.fqn(), List.of(), args);
            streamRun(shortName(m.fqn()), root, command, JdkToolchain.environment(jdkHome, processPath()));
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
            List<String> args = ProgramArgs.tokenize(programArgsForMain(mc));
            List<String> command = JavaRunCommand.build(
                    javaExecutableFor(root, info.javaExec()),
                    info.modulePaths(),
                    info.classPaths(),
                    mc.fqn(),
                    List.of(),
                    args);
            String jdkHome = effectiveMavenJdk(root, null);
            streamRun(shortName(mc.fqn()), root, command, JdkToolchain.environment(jdkHome, processPath()));
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
        String configured = JdkToolchain.javaExecutable(effectiveMavenJdk(root, null));
        return configured.isBlank() ? resolved : configured;
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
            return ops.programArgs(Path.of(mc.filePath()));
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

    private void runActiveFile(boolean promptArgs) {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null || !buffer.isRunnable()) {
            host.setStatus(tr("status.run.notCompact"));
            return;
        }
        if (buffer.isMakefile()) {
            runMakeTarget(buffer, null); // "Run File" on a Makefile ⇒ the default goal
            return;
        }
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
        boolean javaSource = !buffer.isPython() && !buffer.isShell();
        String jdkHome = javaSource ? host.settings().getMavenJdkHome() : "";
        String selectedJava = JdkToolchain.javaExecutable(jdkHome);
        String javaExecutable = selectedJava.isBlank() ? "java" : selectedJava;
        java.util.Map<String, String> launchEnv =
                javaSource ? JdkToolchain.environment(jdkHome, processPath()) : java.util.Map.of();
        List<String> command = buildRunCommand(buffer, path, javaExecutable);
        Runnable proceed = () -> {
            String stored = ops.programArgs(path);
            if (promptArgs) {
                host.promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                    ops.setProgramArgs(path, args == null ? "" : args.strip());
                    launchRun(path, buildRunCommand(buffer, path, javaExecutable), launchEnv);
                });
            } else {
                launchRun(path, command, launchEnv);
            }
        };
        if (javaSource) {
            // Probe exactly the launcher we will use; a selected JDK may differ from PATH. The
            // extensionless shebang can request a source release newer than the compact-source minimum.
            service.detectJavaMajor(javaExecutable, major -> {
                int required = Math.max(25, buffer.getShebangJavaSource() == null ? 25 : buffer.getShebangJavaSource());
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

    /** The launcher argv for the buffer's language: interpreter + file + the remembered args. */
    private List<String> buildRunCommand(EditorBuffer buffer, Path path, String javaExecutable) {
        List<String> command = new ArrayList<>();
        if (buffer.isPython()) {
            command.add("python3");
        } else if (buffer.isShell()) {
            command.add("bash");
        } else {
            command.add(javaExecutable);
            Integer javaSource = buffer.getShebangJavaSource();
            if (javaSource != null) {
                // An extensionless `java --source N` shebang file: the source launcher needs the flag
                // (a plain `java <file>` only works when the name ends in .java).
                command.add("--source");
                command.add(String.valueOf(javaSource));
            }
        }
        command.add(path.toString());
        command.addAll(ProgramArgs.tokenize(ops.programArgs(path)));
        return command;
    }

    private void launchRun(Path path, List<String> command, java.util.Map<String, String> env) {
        streamRun(path.getFileName().toString(), path.getParent(), command, env);
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
        ops.openToolWindow();
        if (!service.isRunning()) {
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
        lastRunDir = workingDir;
        lastRunLabel = label;
        lastRunCommand = command;
        lastRunEnv = env;
        ops.openToolWindow();
        panel.started(label);
        host.setStatus(tr("status.run.started", label));
        service.runInDir(workingDir, command, env, new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {
                panel.started(commandLine);
            }

            @Override
            public void onOutput(String line, boolean stderr) {
                panel.appendOutput(line, stderr);
            }

            @Override
            public void onPartialOutput(String text, boolean stderr) {
                panel.appendPartialOutput(text, stderr);
            }

            @Override
            public void onExit(int code) {
                panel.finished(code);
                host.setStatus(code == 0 ? tr("status.run.ok") : tr("status.run.exit", code));
                ops.onRunStateChanged();
            }

            @Override
            public void onError(String message) {
                panel.failed(message);
                host.setStatus(tr("status.run.failed", message));
                ops.onRunStateChanged();
            }
        });
        // After the call, not inside onStart: RunService publishes the process before it notifies, so by here
        // isRunning() is true whether the launch succeeded or failed back through onError above.
        ops.onRunStateChanged();
    }

    /** Stops the currently running program (Run tool window Stop button / {@code run.stop} command). */
    void stopRun() {
        if (service.isRunning()) {
            service.stop();
            host.setStatus(tr("status.run.stopped"));
        }
    }

    void clearConsole() {
        panel.clearConsole();
    }

    void shutdown() {
        service.shutdown();
    }
}
