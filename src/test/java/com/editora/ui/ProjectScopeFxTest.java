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
class ProjectScopeFxTest {

    private FxWindowFixture fx;
    private Path project;
    private ConfigManager config;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
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
            registry.run("markdownLint.fix");
            assertEquals("# Title\n\nword  after\n", buffer.getContent(), "two spaces: the project's tab size");
        });
    }
}
