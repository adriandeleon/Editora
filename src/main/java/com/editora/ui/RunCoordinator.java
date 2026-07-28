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
import com.editora.run.ProgramArgs;
import com.editora.run.RunConfigRouting;
import com.editora.run.RunService;
import com.editora.run.StackTraceLinks;

import static com.editora.i18n.Messages.tr;

/**
 * Run-a-file feature (the gutter ▶ / "Run File" flow + the Run tool-window console), extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link RunService} + the
 * {@link RunPanel}; {@code MainController} keeps the {@code ToolWindow} (built with {@link #panel()}),
 * the run-feature gating (via the LSP feature), and the shared stack-trace link resolver
 * ({@code openRunLink}, also used by the Debug + External-Tool consoles) — which reuses {@link #lastRunDir()}.
 */
final class RunCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (Run tool window, buffer save, program args, link jump). */
    interface Ops {
        void openToolWindow();

        /** Saves {@code buffer} (dirty → write, untitled → Save-As); {@code false} if cancelled/failed. */
        boolean saveBuffer(EditorBuffer buffer);

        /** The remembered program-arguments string for {@code path} ("" when none). */
        String programArgs(Path path);

        /** Persists the program-arguments string for {@code path} (workspace state + durable save). */
        void setProgramArgs(Path path, String args);

        /** A stack-trace location double-clicked in the console: resolve + jump (shared resolver). */
        void openLink(StackTraceLinks.Link link);

        /** The nearest Maven/Gradle project root above {@code file}, or {@code null} if there is none. */
        Path javaProjectRoot(Path file);

        /** Whether a project main class can be resolved+run (jdtls + the java-debug bundle available today). */
        boolean javaLaunchAvailable();

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
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
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
        return RunConfigRouting.pick(open, activeJava, cfg.workingDir());
    }

    /** Runs a saved {@link RunConfiguration}: its main class with its own program/VM args + working dir. */
    void runConfig(RunConfiguration cfg) {
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
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
        String label = shortName(cfg.mainClass());
        if (ops.javaLaunchAvailable()) {
            JavaMainClass mc = new JavaMainClass(cfg.mainClass(), cfg.projectName(), routing.toString());
            ops.resolveJavaLaunch(routing, mc, info -> {
                if (info == null || !info.ok()) {
                    host.setStatus(info == null ? tr("status.run.resolveFailed") : info.error());
                    return;
                }
                streamRun(
                        label,
                        cwd,
                        JavaRunCommand.build(
                                info.javaExec(), info.modulePaths(), info.classPaths(), cfg.mainClass(), vm, args),
                        env);
            });
        } else if (ops.mavenProjectAt(root)) {
            host.setStatus(tr("status.run.resolvingClasspath"));
            ops.resolveMavenClasspath(root, cp -> {
                if (cp == null || cp.isEmpty()) {
                    host.setStatus(tr("status.run.resolveFailed"));
                    return;
                }
                streamRun(label, cwd, JavaRunCommand.build("", List.of(), cp, cfg.mainClass(), vm, args), env);
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
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
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
        ops.resolveMavenClasspath(root, cp -> {
            if (cp == null || cp.isEmpty()) {
                host.setStatus(tr("status.run.resolveFailed"));
                return;
            }
            List<String> args = ProgramArgs.tokenize(ops.programArgs(b.getPath()));
            List<String> command = JavaRunCommand.build("", List.of(), cp, m.fqn(), List.of(), args);
            streamRun(shortName(m.fqn()), root, command);
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
                    info.javaExec(), info.modulePaths(), info.classPaths(), mc.fqn(), List.of(), args);
            streamRun(shortName(mc.fqn()), root, command);
        });
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
        if ((buffer.isDirty() || buffer.getPath() == null) && !ops.saveBuffer(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run against stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
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
        if ((buffer.isDirty() || buffer.getPath() == null) && !ops.saveBuffer(buffer)) {
            return; // user cancelled Save-As, or the save failed — don't run stale/missing content
        }
        Path path = buffer.getPath();
        if (path == null) {
            return;
        }
        if (service.isRunning()) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        boolean java = !buffer.isPython() && !buffer.isShell();
        Runnable proceed = () -> {
            String stored = ops.programArgs(path);
            if (promptArgs) {
                host.promptText(tr("dialog.runArgs.title"), tr("dialog.runArgs.label"), stored, args -> {
                    ops.setProgramArgs(path, args == null ? "" : args.strip());
                    launchRun(path, buildRunCommand(buffer, path));
                });
            } else {
                launchRun(path, buildRunCommand(buffer, path));
            }
        };
        if (java) {
            // Compact source files need the JDK 25+ source-file launcher; preflight so an older java
            // on PATH yields a clear message instead of a cryptic launcher error. Cached after once.
            service.detectJavaMajor(major -> {
                if (major > 0 && major < 25) {
                    host.setStatus(tr("status.run.needJdk25", major));
                    return;
                }
                proceed.run();
            });
        } else {
            proceed.run();
        }
    }

    /** The launcher argv for the buffer's language: interpreter + file + the remembered args. */
    private List<String> buildRunCommand(EditorBuffer buffer, Path path) {
        List<String> command = new ArrayList<>();
        if (buffer.isPython()) {
            command.add("python3");
        } else if (buffer.isShell()) {
            command.add("bash");
        } else {
            command.add("java");
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

    private void launchRun(Path path, List<String> command) {
        streamRun(path.getFileName().toString(), path.getParent(), command);
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
            public void onExit(int code) {
                panel.finished(code);
                host.setStatus(code == 0 ? tr("status.run.ok") : tr("status.run.exit", code));
            }

            @Override
            public void onError(String message) {
                panel.failed(message);
                host.setStatus(tr("status.run.failed", message));
            }
        });
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
        service.stop();
    }
}
