package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.application.Platform;

import com.editora.build.BuildTool;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link RunCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class RunOpsHost implements RunCoordinator.Ops {

    private final MainController mc;

    RunOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openToolWindow() {
        mc.toolWindows.open(mc.runToolWindow);
    }

    @Override
    public void deployThen(
            String name,
            DeploymentCoordinator.Console console,
            java.util.function.Consumer<DeploymentCoordinator.Deployed> then) {
        mc.deployments.deployThen(name, console, then);
    }

    @Override
    public void cancelDeploy() {
        mc.deployments.cancel();
    }

    @Override
    public void attachJavaDebugger(@Nullable Path anchor, int port, Runnable stopOwner) {
        if (anchor != null) mc.debugCoordinator.attachToPort(anchor, "localhost", port, stopOwner);
    }

    @Override
    public com.editora.terminal.ProgramRun runInTerminal(
            @Nullable String title,
            @Nullable Path dir,
            List<String> command,
            Map<String, String> env,
            java.util.function.IntConsumer onExit)
            throws java.io.IOException {
        return mc.terminalCoordinator.runProgram(title != null ? title : "", dir, command, env, onExit);
    }

    @Override
    public void onRunStateChanged() {
        mc.runConfigurations.updateRunConfigButtons();
        mc.updateRunButton();
    }

    @Override
    public void editConfiguration(String name) {
        mc.runConfigurations.runConfigEditor.accept(name);
    }

    @Override
    public void saveThen(EditorBuffer buffer, java.util.function.Consumer<Boolean> then) {
        mc.fileWorkflows.saveThen(buffer, then); // the write off the FX thread, no nested wait (PERF-01)
    }

    @Override
    public String programArgs(java.nio.file.@Nullable Path path) {
        return mc.programArgsFor(path);
    }

    @Override
    public void setProgramArgs(java.nio.file.Path path, String args) {
        mc.config.getWorkspaceState().getProgramArgs().put(path.toString(), args);
        mc.config.save();
    }

    @Override
    public void openLink(com.editora.run.StackTraceLinks.Link link) {
        mc.testNavigation.openRunLink(link);
    }

    @Override
    public void openUrl(String url) {
        mc.openExternalUrl(url);
    }

    @Override
    public java.nio.file.@Nullable Path javaProjectRoot(java.nio.file.Path file) {
        return JavaProjectRoot.find(file);
    }

    @Override
    public String pythonInterpreterChoice() {
        return mc.config.getWorkspaceState().getPythonInterpreter();
    }

    @Override
    public boolean runPythonTestFile(java.nio.file.Path file, String text) {
        return mc.testNavigation.runPythonTestFile(file, text);
    }

    @Override
    public boolean runNodeTestFile(java.nio.file.Path file, java.util.List<String> nodeFlags) {
        return mc.testNavigation.runNodeTestFile(file, nodeFlags);
    }

    @Override
    public boolean runNodeTestFile(java.nio.file.Path file) {
        return mc.testNavigation.runNodeTestFile(file);
    }

    @Override
    public java.nio.file.@Nullable Path projectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public boolean javaLaunchAvailable() {
        // Resolving a project main class + classpath rides jdtls's java-debug bundle (the same gate as
        // Java debugging). PR4 adds a build-tool fallback so Run works without it.
        return mc.debugCoordinator.debugEffectiveFor("java");
    }

    @Override
    public List<com.editora.config.RunConfiguration> runConfigurations() {
        return mc.runConfigurations.savedConfigs(); // none in the No Project window: the gutter ▶ runs plain
    }

    @Override
    public String selectedRunConfigName() {
        return mc.config.getWorkspaceState().getSelectedRunConfig();
    }

    @Override
    public void resolveJavaMainClasses(
            java.nio.file.Path routingFile, java.util.function.Consumer<List<com.editora.run.JavaMainClass>> cb) {
        // The routing file may be a background tab whose server start was deferred; open it first or
        // jdtls has never seen it and answers "no language server for file".
        mc.lspCoordinator.ensureManaged(routingFile);
        mc.dapManager.resolveMainClasses(
                routingFile,
                opts -> cb.accept(opts.stream()
                        .map(o -> new com.editora.run.JavaMainClass(
                                java.util.Objects.requireNonNullElse(o.mainClass(), ""), o.projectName(), o.filePath()))
                        .toList()));
    }

    @Override
    public void resolveJavaLaunch(
            java.nio.file.Path routingFile,
            com.editora.run.JavaMainClass main,
            java.util.function.Consumer<com.editora.run.JavaLaunchInfo> cb) {
        mc.lspCoordinator.ensureManaged(routingFile); // see resolveJavaMainClasses
        mc.dapManager.resolveLaunch(
                routingFile,
                new com.editora.dap.DapManager.MainClassOption(main.fqn(), main.projectName(), main.filePath()),
                r -> cb.accept(new com.editora.run.JavaLaunchInfo(
                        r.javaExec(), r.modulePaths(), r.classPaths(), r.error(), r.enablePreview())));
    }

    @Override
    public boolean mavenProjectAt(java.nio.file.Path root) {
        return root != null && buildFiles(root)[0];
    }

    @Override
    public boolean gradleProjectAt(java.nio.file.Path root) {
        return root != null && buildFiles(root)[1];
    }

    /**
     * Whether a folder holds a pom ({@code [0]}) and Gradle build files ({@code [1]}), as last looked. A launch's
     * plan looks off the FX thread; an FX command that needs a folder no look has filled waits for one first
     * ({@code Looked.allKnown}), so the FX thread answers from memory (PERF-01, Pauta 6.2.0).
     */
    private static final com.editora.perf.Looked<java.nio.file.Path, boolean[]> BUILD_FILES =
            new com.editora.perf.Looked<>("RunOpsHost.buildFiles", RunOpsHost::buildFilesNow);

    /** Whether {@code root} holds a pom, as last looked ({@link #buildFiles}). */
    static boolean isMavenProject(java.nio.file.Path root) {
        return buildFiles(root)[0];
    }

    static boolean[] buildFiles(java.nio.file.Path root) {
        boolean[] known = BUILD_FILES.get(root);
        return known != null ? known : new boolean[2];
    }

    private static boolean[] buildFilesNow(java.nio.file.Path root) {
        boolean gradle = false;
        for (String m : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            if (java.nio.file.Files.isRegularFile(root.resolve(m))) {
                gradle = true;
                break;
            }
        }
        return new boolean[] {java.nio.file.Files.isRegularFile(root.resolve("pom.xml")), gradle};
    }

    @Override
    public void resolveMavenClasspath(java.nio.file.Path root, java.util.function.Consumer<List<String>> cb) {
        resolveMavenClasspath(root, "", List.of("mvn"), com.editora.maven.MavenClasspath.Scope.RUNTIME, cb);
    }

    @Override
    public void resolveMavenClasspath(
            java.nio.file.Path root,
            String jdkHome,
            List<String> executable,
            com.editora.maven.MavenClasspath.Scope scope,
            java.util.function.Consumer<List<String>> cb) {
        Thread.ofVirtual().name("editora-maven-cp").start(() -> {
            List<String> result = com.editora.maven.MavenClasspathResolver.resolve(root, jdkHome, executable, scope);
            // what the launch asks next on the FX thread, looked at here (PERF-01, Pauta 6.2.0)
            com.editora.run.JdkToolchain.isGone(jdkHome);
            com.editora.run.JdkToolchain.inheritedJavaExecutable(System.getenv());
            Platform.runLater(() -> cb.accept(result));
        });
    }

    @Override
    public void warmBuildExecutable(BuildTool tool, java.nio.file.Path root) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool) {
                c.warmTrustedExecutable(root, mc.projectRoot());
            }
        }
    }

    @Override
    public void buildExecutableFacts(BuildTool tool, java.nio.file.Path root) {
        buildExecutableFacts(mc, tool, root);
    }

    /** {@link BuildCoordinator#trustFacts} of {@code tool}'s coordinator: what its trusted executable asks. */
    static void buildExecutableFacts(MainController mc, BuildTool tool, java.nio.file.Path root) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool) {
                c.trustFacts(root);
            }
        }
    }

    @Override
    public @Nullable List<String> buildExecutable(BuildTool tool, java.nio.file.@Nullable Path root) {
        return mc.buildExecutableFor(tool, root);
    }

    @Override
    public boolean trustBuild(java.nio.file.Path root, List<String> items) {
        return DebugOpsHost.trustBuild(mc, root, items);
    }

    @Override
    public List<String> mavenToggleArgs() {
        return mc.buildCoordinators.stream()
                .filter(c -> c.tool() == BuildTool.MAVEN)
                .findFirst()
                .map(BuildCoordinator::activeToggleArgs)
                .orElse(List.of());
    }

    @Override
    public void runGradleRunTask(java.nio.file.Path root) {
        // The build script is read off the FX thread (PERF-01, Pauta 6.2.0): bootRun for Spring Boot.
        DebugPlanning.offFx(
                "gradle-run-task",
                () -> com.editora.build.SpringBoot.gradleRunTask(
                        com.editora.build.SpringBoot.readGradleBuildFile(root)),
                a -> {
                    String task = a.value();
                    for (BuildCoordinator c : mc.buildCoordinators) {
                        if (task != null && c.tool() == BuildTool.GRADLE && c.isEnabled() && c.isDetected()) {
                            c.runTask(List.of(task), List.of());
                            return;
                        }
                    }
                });
    }

    @Override
    public boolean runHttpFile(EditorBuffer buffer) {
        mc.httpClient.runFile(); // the active buffer — the one "Run File" was asked for
        return true;
    }

    @Override
    public boolean runBuildTask(BuildTool tool, java.nio.file.@Nullable Path root, List<String> taskArgs) {
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == tool && c.isEnabled()) {
                c.runTaskAt(root, taskArgs, List.of());
                return true;
            }
        }
        return false;
    }
}
