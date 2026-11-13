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
    public boolean saveBuffer(EditorBuffer buffer) {
        return mc.fileWorkflows.saveSynchronously(buffer);
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
        return root != null && java.nio.file.Files.isRegularFile(root.resolve("pom.xml"));
    }

    @Override
    public boolean gradleProjectAt(java.nio.file.Path root) {
        if (root == null) {
            return false;
        }
        for (String m : List.of("build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts")) {
            if (java.nio.file.Files.isRegularFile(root.resolve(m))) {
                return true;
            }
        }
        return false;
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
            Platform.runLater(() -> cb.accept(result));
        });
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
        String task = com.editora.build.SpringBoot.gradleRunTask(
                com.editora.build.SpringBoot.readGradleBuildFile(root)); // bootRun for Spring Boot
        for (BuildCoordinator c : mc.buildCoordinators) {
            if (c.tool() == BuildTool.GRADLE && c.isEnabled() && c.isDetected()) {
                c.runTask(List.of(task), List.of());
                return;
            }
        }
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
