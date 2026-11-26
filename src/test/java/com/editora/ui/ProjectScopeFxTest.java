package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.config.ProjectSettings;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A project's values from its committed {@code .editora/settings.json} reach that project's window — the tab
 * size and rulers of its buffers, what a save trims, its build and debugger commands — only once the folder is
 * trusted; until then the user's own values apply.
 */
@Tag("fx")
@Tag("windows-fx")
class ProjectScopeFxTest {

    private FxWindowFixture fx;
    private Path project;
    private ConfigManager config;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        // The project file is written in place here, so a step sees it as it returns (the app writes it on a thread
        // of its own, PERF-01, Pauta 6.2.0).
        com.editora.config.ProjectSettingsFile.writeInPlaceForTest(true);
    }

    @org.junit.jupiter.api.AfterAll
    static void writeOffThreadAgain() {
        com.editora.config.ProjectSettingsFile.writeInPlaceForTest(false);
    }

    @BeforeEach
    void setUp() throws Exception {
        fx = FxWindowFixture.create();
        project = fx.useProject();
        config = FxTestSupport.field(fx.controller, "config");
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setTabSize(4);
            config.getSettings().setRulers(List.of(80));
        });
        Path file = ProjectSettings.fileFor(project);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"version": 2,
                 "editor": {"tabSize": 2, "rulers": [100], "trimTrailingWhitespace": true, "insertFinalNewline": true},
                 "buildCommands": {"maven": "./tools/mvn"},
                 "debugTools": {"python": "/opt/project-python"}}
                """);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.dispose();
    }

    private ProjectScope scope() {
        LspCoordinator lsp = FxTestSupport.field(fx.controller, "lspCoordinator");
        return lsp.projectScope();
    }

    private EditorBuffer open(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class<?>[] {Path.class}, file));
        return FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    /**
     * The project's JDK and Maven: its {@code .sdkmanrc} picks them from the installs whether or not the folder is
     * trusted (it only chooses among what is installed); the project file's {@code toolchain.jdk} wins once the folder
     * is trusted; a project build command still wins over the {@code .sdkmanrc} Maven.
     */
    @Test
    void theProjectsSdkmanrcAndToolchainChooseItsJdkAndMaven() throws Exception {
        Files.writeString(project.resolve(".sdkmanrc"), "java=21.0.9-tem\nmaven=3.9.11\n");
        Files.writeString(ProjectSettings.fileFor(project), """
                {"version": 2, "toolchain": {"jdk": "25"}, "buildCommands": {"maven": "./tools/mvn"}}
                """);
        com.editora.toolchain.ToolInstall jdk21 = new com.editora.toolchain.ToolInstall(
                "jdk", "21.0.9+10", "temurin", false, Path.of("/jk/jdk21"), "jkit", "21.0.9+10-temurin");
        com.editora.toolchain.ToolInstall jdk25 = new com.editora.toolchain.ToolInstall(
                "jdk", "25.0.1+8", "temurin", false, Path.of("/jk/jdk25"), "jkit", "25.0.1+8-temurin");
        com.editora.toolchain.ToolInstall maven = new com.editora.toolchain.ToolInstall(
                "maven", "3.9.11", null, false, Path.of("/jk/mvn"), "jkit", "3.9.11");
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setMavenJdkHome("/mine/jdk");
            config.getSettings().setMavenCommand("");
            ProjectScope scope = scope();
            scope.reads = Runnable::run;
            scope.toolchainReads = Runnable::run;
            scope.installs = () -> new com.editora.toolchain.ProjectToolchain.Installs(
                    List.of(jdk21, jdk25), List.of(maven), List.of());
            scope.filePins = r -> com.editora.toolchain.ProjectToolchain.sdkmanrcPins(
                    com.editora.toolchain.SdkmanRc.read(r)); // as without jkit, whatever this machine has
            scope.reload();
            assertEquals(
                    Path.of("/jk/jdk21").toString(),
                    scope.jdkHome(config.getSettings()),
                    "untrusted: .sdkmanrc's java=");
            assertEquals(
                    Path.of("/jk/mvn", "bin", com.editora.Os.isWindows() ? "mvn.cmd" : "mvn")
                            .toString(),
                    scope.buildCommand(com.editora.build.BuildTool.MAVEN, config.getSettings()),
                    "untrusted: .sdkmanrc's maven=, not the project file's command");

            config.getTrustStore().trust(project);
            scope.reload();
            assertEquals(
                    Path.of("/jk/jdk25").toString(),
                    scope.jdkHome(config.getSettings()),
                    "trusted: the project file's toolchain.jdk");
            assertEquals("./tools/mvn", scope.buildCommand(com.editora.build.BuildTool.MAVEN, config.getSettings()));
        });
        Files.delete(project.resolve(".sdkmanrc"));
        Files.writeString(ProjectSettings.fileFor(project), "{\"version\": 2}");
        FxTestSupport.runOnFx(() -> {
            scope().reload();
            assertEquals("/mine/jdk", scope().jdkHome(config.getSettings()), "nothing named: the user's Default JDK");
        });
    }

    /**
     * With jkit installed, the project's pins are what jkit says ({@code jkit status}): the install jkit picked is
     * the project's JDK, and an sbt it pins is the sbt the build runs.
     */
    @Test
    void jkitsPinsChooseTheProjectsJdkAndSbt() throws Exception {
        Files.writeString(project.resolve("jkit.toml"), "[tools]\njdk = \"21\"\nsbt = \"1.11\"\n");
        com.editora.toolchain.ToolInstall picked = new com.editora.toolchain.ToolInstall(
                "jdk", "21.0.9+10", "zulu", false, Path.of("/jk/zulu21"), "jkit", "21.0.9+10-zulu");
        com.editora.toolchain.ToolInstall sbt = new com.editora.toolchain.ToolInstall(
                "sbt", "1.11.7", null, false, Path.of("/jk/sbt"), "jkit", "1.11.7");
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setMavenJdkHome("/mine/jdk");
            ProjectScope scope = scope();
            scope.reads = Runnable::run;
            scope.toolchainReads = Runnable::run;
            scope.installs = () ->
                    new com.editora.toolchain.ProjectToolchain.Installs(List.of(), List.of(), List.of(), List.of(sbt));
            scope.filePins = r -> List.of(
                    new com.editora.toolchain.ProjectToolchain.FilePin(
                            com.editora.toolchain.ToolRequest.of(
                                    new com.editora.toolchain.JkitCli.PinSpec("jdk", "21", null, false)),
                            com.editora.toolchain.ProjectToolchain.Origin.JKIT,
                            "jkit.toml",
                            picked),
                    new com.editora.toolchain.ProjectToolchain.FilePin(
                            com.editora.toolchain.ToolRequest.of(
                                    new com.editora.toolchain.JkitCli.PinSpec("sbt", "1.11", null, false)),
                            com.editora.toolchain.ProjectToolchain.Origin.JKIT,
                            "jkit.toml",
                            null));
            scope.reload();
            assertEquals(Path.of("/jk/zulu21").toString(), scope.jdkHome(config.getSettings()), "the JDK jkit picked");
            assertEquals(
                    Path.of("/jk/sbt", "bin", com.editora.Os.isWindows() ? "sbt.bat" : "sbt")
                            .toString(),
                    scope.buildCommand(com.editora.build.BuildTool.SBT, config.getSettings()),
                    "the sbt jkit.toml pins, matched among the installs");
        });
    }

    @Test
    void anUntrustedProjectsValuesAreKeptButTheUsersApply() throws Exception {
        EditorBuffer buffer = open(Files.writeString(project.resolve("A.java"), "class A {}\n"));
        FxTestSupport.runOnFx(() -> {
            scope().reload();
            assertEquals(4, buffer.getTabSize(), "untrusted: the user's tab size");
            assertEquals(List.of(80), FxTestSupport.call(buffer, "rulerColumnsInForce", new Class<?>[] {}));
            assertEquals(2, scope().settings().editor().getTabSize(), "the value is read, not applied");
        });
    }

    @Test
    void trustingTheFolderAppliesItsValuesToTheBuffersAndToWhatIsSaved() throws Exception {
        Path source = Files.writeString(project.resolve("notes.txt"), "keep\n");
        EditorBuffer buffer = open(source);
        FxTestSupport.runOnFx(() -> {
            config.getTrustStore().trust(project);
            scope().reload(); // a trust decision: listeners re-apply this window's buffers
            assertEquals(2, buffer.getTabSize(), "the project's tab size");
            assertEquals(List.of(100), FxTestSupport.call(buffer, "rulerColumnsInForce", new Class<?>[] {}));
            ProjectScope scope = scope();
            assertEquals("./tools/mvn", scope.buildCommand(com.editora.build.BuildTool.MAVEN, config.getSettings()));
            assertEquals("/opt/project-python", scope.debugTool("python", ""));
            assertSame(
                    scope,
                    ((CoordinatorHost) FxTestSupport.field(fx.controller, "coordinatorHost")).projectScope(),
                    "every coordinator asks the same scope");

            buffer.getArea().replaceText("trailing   \nspaces\t");
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            registry.run("file.save");
        });
        SettingsRig.awaitFx("the save", () -> {
            try {
                return Files.readString(source).equals("trailing\nspaces\n");
            } catch (java.io.IOException e) {
                return false;
            }
        });
    }

    @Test
    void toggleWordWrapFlipsTheProjectsValueWhereTheProjectSetsIt() throws Exception {
        Files.writeString(ProjectSettings.fileFor(project), "{\"editor\": {\"wordWrap\": true}}");
        EditorBuffer buffer = open(Files.writeString(project.resolve("B.java"), "class B {}\n"));
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setWordWrap(false);
            config.getTrustStore().trust(project);
            scope().reload();
            assertEquals(true, buffer.wrap().preferred(), "the project wraps code");
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            registry.run("view.toggleWordWrap");
            assertEquals(false, buffer.wrap().preferred(), "the toggle acts on what is on screen");
            assertEquals(false, config.getSettings().isWordWrap(), "the user's own setting is untouched");
            assertEquals(Boolean.FALSE, ProjectSettings.load(project).editor().getWordWrap(), "the project's flipped");
        });
    }

    @Test
    void markdownLintFixesTabsWithTheProjectsTabSize() throws Exception {
        EditorBuffer buffer = open(Files.writeString(project.resolve("README.md"), "# Title\n\nword\tafter\n"));
        FxTestSupport.runOnFx(() -> {
            config.getTrustStore().trust(project);
            scope().reload();
            CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
            // The fixes are worked out off the FX thread (PERF-01); here in place, so they land now.
            com.editora.perf.OffFx.runnerForTest((name, task) -> task.run());
            java.util.concurrent.Executor look = MarkdownLintActions.configExec;
            MarkdownLintActions.configExec = Runnable::run;
            try {
                registry.run("markdownLint.fix");
            } finally {
                com.editora.perf.OffFx.runnerForTest(null);
                MarkdownLintActions.configExec = look;
            }
            assertEquals("# Title\n\nword  after\n", buffer.getContent(), "two spaces: the project's tab size");
        });
    }

    /**
     * A project file written outside Editora — {@code jkit pin} in a terminal, another editor — is read as soon as
     * the project's watcher reports it, not at the next restart. Found on Windows: a {@code jkit.toml} created
     * beside an open window left Run on PATH's java (there was none) until Editora was restarted.
     */
    @Test
    void aProjectFileWrittenElsewhereAppliesWhenTheWatcherSeesIt() throws Exception {
        com.editora.toolchain.ToolInstall jdk21 = new com.editora.toolchain.ToolInstall(
                "jdk", "21.0.9+10", "temurin", false, Path.of("/jk/jdk21"), "jkit", "21.0.9+10-temurin");
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setMavenJdkHome("/mine/jdk");
            ProjectScope scope = scope();
            scope.reads = Runnable::run;
            scope.toolchainReads = Runnable::run;
            scope.installs =
                    () -> new com.editora.toolchain.ProjectToolchain.Installs(List.of(jdk21), List.of(), List.of());
            scope.filePins =
                    r -> com.editora.toolchain.ProjectToolchain.sdkmanrcPins(com.editora.toolchain.SdkmanRc.read(r));
            scope.reload();
            assertEquals("/mine/jdk", scope.jdkHome(config.getSettings()), "nothing named yet: the Default JDK");
        });
        Path sdkmanrc = project.resolve(".sdkmanrc");
        Files.writeString(sdkmanrc, "java=21.0.9-tem\n"); // by another program: Editora saved nothing
        FxTestSupport.runOnFx(() -> {
            ProjectPanel panel = FxTestSupport.field(fx.controller, "projectPanel");
            java.util.function.Consumer<ProjectWatchChanges.Plan> watcher =
                    FxTestSupport.field(panel, "onExternalChange");
            watcher.accept(new ProjectWatchChanges.Plan(
                    false,
                    java.util.Set.of(project),
                    List.of(),
                    List.of(new ProjectPanel.FsChange(sdkmanrc, ProjectPanel.FsKind.CREATED)),
                    false));
            assertEquals(
                    Path.of("/jk/jdk21").toString(),
                    scope().jdkHome(config.getSettings()),
                    "the new .sdkmanrc's java=");
        });
    }
}
