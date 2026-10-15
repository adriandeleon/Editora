package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.markdown.MarkdownLint;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Markdown lint with a real project open in a real window: {@code Lint Project} from the command to the grouped
 * tool window and a row opening its file; a config file changed by another program reaching the open buffers
 * through the project's file watcher; and, outside any project, through the app's look at the disk when it
 * regains focus.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkdownLintProjectFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** Looks until {@code probe} answers non-null: up to a minute, since macOS only has a polling file watcher. */
    private static <T> T await(String what, Callable<T> probe) throws Exception {
        for (int i = 0; i < 1200; i++) {
            T value = FxTestSupport.callOnFx(probe::call);
            if (value != null) {
                return value;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("timed out waiting for " + what);
    }

    private MainController projectWindow(Path root) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.invokeWith(fx.controller, "openProjectRoot", Path.class, root));
        return await("the project's window", () -> {
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            for (Object holder : holders) {
                MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                Path active = (Path) FxTestSupport.call(c, "windowProjectRoot", new Class<?>[] {});
                if (c != fx.controller && active != null && Files.isSameFile(active, root)) {
                    return c;
                }
            }
            return null;
        });
    }

    private static EditorBuffer active(MainController c) {
        return (EditorBuffer) FxTestSupport.call(c, "activeBuffer", new Class<?>[] {});
    }

    private static List<String> codes(EditorBuffer b) {
        return b.markdownLintDiagnostics().stream()
                .map(MarkdownLint.Diagnostic::code)
                .toList();
    }

    @Test
    void theProjectIsLintedFromTheCommandToTheOpenedRowAndFollowsItsConfigFile() throws Exception {
        Path root = Files.createDirectory(dir.resolve("proj"));
        Files.writeString(root.resolve(".gitignore"), "build/\n");
        Path readme = Files.writeString(root.resolve("README.md"), "# Readme\n\ntext   \n");
        Path guide = Files.writeString(
                Files.createDirectory(root.resolve("docs")).resolve("guide.md"),
                "# Guide\n\n[gone](missing.md) and [[nowhere]] and [[README]]\n");
        Files.writeString(root.resolve("docs").resolve("clean.md"), "# Clean\n");
        Files.writeString(Files.createDirectory(root.resolve("build")).resolve("generated.md"), "#Bad   \n");

        MainController window = projectWindow(root);
        CommandRegistry registry = FxTestSupport.field(window, "registry");
        FxTestSupport.runOnFx(() -> window.openAndNavigate(readme, 0));
        EditorBuffer readmeBuffer = await("the README's squiggles", () -> {
            EditorBuffer b = active(window);
            return b != null && readme.equals(b.getPath()) && codes(b).equals(List.of("MD009")) ? b : null;
        });

        // --- Lint Project, through the command ---------------------------------------------------------
        FxTestSupport.runOnFx(() -> registry.run("markdownLint.lintProject"));
        MarkdownLintPanel panel = await("the tool window", () -> FxTestSupport.field(window, "markdownLintPanel"));
        ListView<MarkdownLint.Diagnostic> list = FxTestSupport.field(panel, "list");
        Label summary = FxTestSupport.field(panel, "summary");
        await(
                "the project's results",
                () -> panel.showingProject() && !summary.getText().equals(tr("markdownLint.projectScanning"))
                        ? Boolean.TRUE
                        : null);
        assertEquals(tr("markdownLint.projectSummary", 3, 2), FxTestSupport.callOnFx(summary::getText));
        List<String> rows = FxTestSupport.callOnFx(() -> list.getItems().stream()
                .map(d -> d.code().isEmpty() ? d.message().replace('\\', '/') : d.code())
                .toList());
        assertEquals(
                List.of("docs/guide.md", "LINK001", "LINK003", "README.md", "MD009"),
                rows,
                "grouped by file; the ignored build/ folder and the clean file are not listed");

        // Activating a row opens its file at the issue.
        FxTestSupport.runOnFx(() -> {
            list.getSelectionModel().select(2);
            list.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false));
        });
        EditorBuffer guideBuffer = await("the guide to open", () -> {
            EditorBuffer b = active(window);
            return b != null && guide.equals(b.getPath()) ? b : null;
        });
        assertEquals(2, (int) await(
                "the caret on the issue's line", () -> guideBuffer.getArea().getCurrentParagraph() == 2 ? 2 : null));
        assertTrue(
                FxTestSupport.callOnFx(panel::showingProject), "the project's list stays while its files are visited");

        // --- A config written by another program: the real watcher, the real hook -----------------------
        FxTestSupport.runOnFx(() -> window.openAndNavigate(readme, 0));
        await("the README again", () -> active(window) == readmeBuffer ? Boolean.TRUE : null);
        Files.writeString(root.resolve(".markdownlint.json"), "{ \"MD009\": false }");
        await(
                "the open buffer to be re-linted with the new config",
                () -> codes(readmeBuffer).isEmpty() ? Boolean.TRUE : null);
        assertEquals(
                "# Readme\n\ntext   \n",
                FxTestSupport.callOnFx(() -> readmeBuffer.getArea().getText()),
                "no edit was made");
    }

    @Test
    void outsideAProjectAChangedConfigIsNoticedWhenTheAppLooksAtTheDisk() throws Exception {
        Path folder = Files.createDirectory(dir.resolve("loose"));
        Path file = Files.writeString(folder.resolve("notes.md"), "# Notes\n\ntext   \n");
        PreviewCoordinator previews = FxTestSupport.field(fx.controller, "previews");
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        FxTestSupport.runOnFx(() -> {
            previews.lint().configCheckIntervalNanos = 0; // every look counts in a test
            fx.controller.openAndNavigate(file, 0);
        });
        EditorBuffer buffer = await("the squiggle", () -> {
            EditorBuffer b = active(fx.controller);
            return b != null && file.equals(b.getPath()) && codes(b).equals(List.of("MD009")) ? b : null;
        });
        FxTestSupport.runOnFx(workflows::checkExternalChanges); // the first look: nothing to compare with yet

        Path config = Files.writeString(folder.resolve(".markdownlint.yaml"), "MD009: false\n");
        FxTestSupport.runOnFx(workflows::checkExternalChanges); // what regaining focus or switching tabs runs
        await("the re-lint", () -> codes(buffer).isEmpty() ? Boolean.TRUE : null);

        Files.writeString(config, "MD009: true\n");
        Files.setLastModifiedTime(
                config,
                java.nio.file.attribute.FileTime.fromMillis(
                        Files.getLastModifiedTime(config).toMillis() + 5_000));
        FxTestSupport.runOnFx(workflows::checkExternalChanges);
        await("the squiggle back", () -> codes(buffer).equals(List.of("MD009")) ? Boolean.TRUE : null);

        Files.delete(config);
        FxTestSupport.runOnFx(workflows::checkExternalChanges);
        FxTestSupport.runOnFx(workflows::checkExternalChanges);
        assertFalse(
                FxTestSupport.callOnFx(() -> codes(buffer).isEmpty()), "a config that went away changes nothing here");
    }
}
