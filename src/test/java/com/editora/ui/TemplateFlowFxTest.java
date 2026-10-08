package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.config.Project;
import com.editora.editor.EditorBuffer;
import com.editora.template.NewFileCatalog;
import com.editora.template.NewFileContent;
import com.editora.template.Template;
import com.editora.template.TemplateOutput;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The template and new-file flows through a real window: where the caret lands once the created file has
 * loaded, what the wizard refuses and keeps on screen, what the status bar ends up saying, and what the
 * project wizard creates. Everything is written under JUnit temp directories.
 */
@Tag("fx")
class TemplateFlowFxTest {

    private static FxWindowFixture window;
    private static MainController controller;
    private static TemplateCoordinator templates;
    private static Stage stage;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        window = FxWindowFixture.create();
        controller = window.controller;
        templates = FxTestSupport.field(controller, "templateActions");
        stage = FxTestSupport.field(controller, "stage");
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (window != null) {
            window.dispose();
            window = null;
        }
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static void await(String what, Callable<Boolean> condition) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            FxTestSupport.drainFx();
            if (FxTestSupport.callOnFx(condition)) {
                return;
            }
            Thread.sleep(40);
        }
        throw new AssertionError("timed out waiting for " + what + "; status=" + lastStatus());
    }

    private static String collapsed(Path path) {
        return (String) FxTestSupport.call(controller, "homeCollapsed", new Class<?>[] {String.class}, path.toString());
    }

    private static EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    private static Template template(String id) throws Exception {
        return FxTestSupport.callOnFx(() -> templates.templates.all().stream()
                .filter(t -> t.id().equals(id))
                .findFirst()
                .orElseThrow());
    }

    private static MessageLog log() {
        Object statusBar = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.field(statusBar, "messageLog");
    }

    private static MessageLog.Entry lastStatus() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<MessageLog.Entry> entries = log().entries();
            return entries.isEmpty() ? null : entries.get(0); // newest first
        });
    }

    private static <T extends Node> List<T> all(Node root, Class<T> type, List<T> out) {
        if (type.isInstance(root)) {
            out.add(type.cast(root));
        }
        if (root instanceof Parent parent) {
            parent.getChildrenUnmodifiable().forEach(c -> all(c, type, out));
        }
        return out;
    }

    private static <T extends Node> List<T> inScene(Class<T> type) {
        return all(stage.getScene().getRoot(), type, new ArrayList<>());
    }

    private static Button createButton() {
        return inScene(Button.class).stream()
                .filter(b -> tr("dialog.template.create").equals(b.getText()))
                .findFirst()
                .orElse(null);
    }

    /** The wizard's inline error, or "" when it shows none (or there is no wizard). */
    private static String wizardError() {
        return inScene(Label.class).stream()
                .filter(l -> l.getStyleClass().contains("overlay-form-error") && l.isVisible())
                .map(Label::getText)
                .findFirst()
                .orElse("");
    }

    private static List<String> labelTexts() {
        return inScene(Label.class).stream().map(Label::getText).toList();
    }

    private static void closeOverlay() throws Exception {
        FxTestSupport.runOnFx(() -> ((OverlayHost) FxTestSupport.field(controller, "overlayHost")).hide());
    }

    private static List<String> tree(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    // --- T3: the caret lands at ${cursor} once the file has loaded --------------------------------

    @Test
    void aNewJavaClassOpensWithTheCaretInItsBody(@TempDir Path project) throws Exception {
        Path pkg = Files.createDirectories(project.resolve("src/main/java/demo"));
        FxTestSupport.runOnFx(() -> templates.createFileOfType(pkg, NewFileCatalog.byId("java.class"), "Slug"));
        int expected = NewFileContent.render(NewFileCatalog.byId("java.class"), "Slug", "demo")
                .caret();
        assertTrue(expected > 0);
        await("Slug.java to load with its caret placed", () -> {
            EditorBuffer b = active();
            return b != null
                    && b.getPath() != null
                    && b.getPath().endsWith("Slug.java")
                    && b.getArea().getLength() > 0
                    && b.getArea().getCaretPosition() == expected;
        });
        assertEquals(tr("status.newfile.created", "Slug.java"), lastStatus().text(), "not replaced by 'Opened …'");
    }

    @Test
    void aTemplateWrittenToDiskOpensWithTheCaretAtItsCursor(@TempDir Path dir) throws Exception {
        Template html = template("html-page");
        FxTestSupport.runOnFx(() -> templates.applyTemplate(html, dir, Map.of("baseName", "page", "title", "T"), null));
        await("page.html to load with its caret placed", () -> {
            EditorBuffer b = active();
            if (b == null || b.getPath() == null || !b.getPath().endsWith("page.html")) {
                return false;
            }
            String text = b.getArea().getText();
            return !text.isEmpty() && b.getArea().getCaretPosition() == text.indexOf("\n</body>");
        });
        // T7: the last word is what was created, at info level — not "Opened …".
        assertEquals(tr("status.templateCreated", "page.html"), lastStatus().text());
        assertTrue(Files.readString(dir.resolve("page.html")).endsWith("</html>\n"), "T18: final newline");
    }

    @Test
    void anUnsavedBufferFromATemplateHasItsTextNameAndCaret() throws Exception {
        Template java = template("java-class");
        FxTestSupport.runOnFx(() -> templates.applyTemplate(java, null, Map.of("className", "Foo"), null));
        await("the untitled Foo.java buffer", () -> {
            EditorBuffer b = active();
            return b != null && b.getPath() == null && "Foo.java".equals(b.getDisplayName());
        });
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = active();
            String text = b.getArea().getText();
            assertTrue(text.startsWith("public class Foo {"), text);
            assertEquals(text.indexOf("\n    }"), b.getArea().getCaretPosition());
            assertEquals("java", b.getLanguage());
        });
    }

    // --- T12: a template's language is honoured ----------------------------------------------------

    @Test
    void aTemplatesLanguageSetsTheGrammarOfTheFileItCreates(@TempDir Path dir) throws Exception {
        Template jenkins =
                new Template("jenkins", "Jenkinsfile", "", "java", "Buildfile", "class X {}\n${cursor}", null);
        FxTestSupport.runOnFx(() -> templates.applyTemplate(jenkins, dir, Map.of(), null));
        await("Buildfile to load as Java", () -> {
            EditorBuffer b = active();
            return b != null
                    && b.getPath() != null
                    && b.getPath().endsWith("Buildfile")
                    && "java".equals(b.getLanguage());
        });
        assertTrue(TemplateCoordinator.needsLanguageOverride("java", "plaintext", true));
        assertFalse(TemplateCoordinator.needsLanguageOverride("java", "java", true), "already that language");
        assertFalse(TemplateCoordinator.needsLanguageOverride("klingon", "plaintext", false), "no such grammar");
        assertFalse(TemplateCoordinator.needsLanguageOverride("", "plaintext", true));
    }

    // --- T6 / T7 / T13: multi-file apply -----------------------------------------------------------

    @Test
    void aMultiFileApplyReportsOneSummaryAndOpensTheFileWithTheCursor(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "MINE");
        Files.writeString(dir.resolve("pyproject.toml"), "MINE");
        Template py = template("python-project");
        List<Path> created = new ArrayList<>();
        FxTestSupport.runOnFx(() ->
                templates.applyTemplate(py, dir, Map.of("packageName", "shop", "description", "d"), created::add));
        await("src/shop/main.py to open", () -> {
            EditorBuffer b = active();
            return b != null
                    && b.getPath() != null
                    && b.getPath().endsWith("src/shop/main.py")
                    && b.getArea().getLength() > 0
                    && b.getArea().getCaretPosition() == b.getArea().getText().indexOf("print(");
        });
        assertEquals("MINE", Files.readString(dir.resolve("README.md")));
        assertEquals(
                tr("status.template.createdSomeKept", 4, collapsed(dir), 2),
                lastStatus().text(),
                "one message saying what was created and what was kept");
        assertEquals(MessageLog.Severity.INFO, lastStatus().severity());
        assertEquals(List.of(dir.toAbsolutePath().normalize()), created);

        // Again: nothing left to create is a failure, reported through the error channel.
        created.clear();
        FxTestSupport.runOnFx(() -> templates.applyTemplate(py, dir, Map.of("packageName", "shop"), created::add));
        await("the all-exist error", () -> lastStatus().severity() == MessageLog.Severity.ERROR);
        assertEquals(
                tr("status.template.allExist", collapsed(dir)), lastStatus().text());
        assertTrue(created.isEmpty(), "no project registration for an apply that created nothing");
    }

    @Test
    void aSingleFileThatAlreadyExistsIsAnErrorNotAPlainStatus(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("page.html"), "MINE");
        Template html = template("html-page");
        FxTestSupport.runOnFx(() -> templates.applyTemplate(html, dir, Map.of("baseName", "page"), null));
        await("the exists error", () -> {
            MessageLog.Entry e = lastStatus();
            return e != null && e.text().equals(tr("status.templateExists", "page.html"));
        });
        assertEquals(MessageLog.Severity.ERROR, lastStatus().severity());
        assertEquals("MINE", Files.readString(dir.resolve("page.html")));
    }

    // --- T1: a multi-file template is never applied without a folder --------------------------------

    @Test
    void aBlankFolderOnAMultiFileTemplateIsRefusedInTheWizardAndWritesNothing() throws Exception {
        Template py = template("python-project");
        List<Runnable> ran = new ArrayList<>();
        var realIo = templates.io;
        try {
            FxTestSupport.runOnFx(() -> {
                templates.io = ran::add; // any attempt to plan or write would show up here
                templates.beginTemplate(py, null, null); // exactly what template.new does after the pick
            });
            await("the wizard", () -> createButton() != null);
            FxTestSupport.runOnFx(() -> {
                assertTrue(labelTexts().contains(tr("template.wizard.folderRequiredLabel")), "not 'Folder (optional)'");
                assertFalse(labelTexts().contains(tr("template.wizard.folder")));
                TextField folder = inScene(TextField.class).stream()
                        .filter(f -> tr("template.wizard.folderRequiredPrompt").equals(f.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                assertEquals("", folder.getText(), "no default folder is invented");
                // T5: the template's main name is asked for, pre-filled, with a readable label.
                assertTrue(
                        labelTexts().contains(tr("template.var.packageName")),
                        labelTexts().toString());
                assertFalse(labelTexts().contains("packageName"));
                assertFalse(labelTexts().contains("description"));
                createButton().fire();
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(tr("template.wizard.folderRequired"), wizardError());
                assertNotNull(createButton(), "the wizard stays open");
            });
            assertTrue(ran.isEmpty(), "nothing was planned, let alone written");
            assertEquals(MessageLog.Severity.ERROR, lastStatus().severity());

            // A relative folder with no project to be relative to is refused too.
            FxTestSupport.runOnFx(() -> {
                inScene(TextField.class).stream()
                        .filter(f -> tr("template.wizard.folderRequiredPrompt").equals(f.getPromptText()))
                        .findFirst()
                        .orElseThrow()
                        .setText("somewhere");
                createButton().fire();
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> assertEquals(tr("template.wizard.folderRelative"), wizardError()));
            assertTrue(ran.isEmpty());
        } finally {
            FxTestSupport.runOnFx(() -> templates.io = realIo);
            closeOverlay();
        }
    }

    @Test
    void existingFilesAreListedAndTheAnswersKeptUntilTheUserConfirms(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("README.md"), "MINE");
        Template py = template("python-project");
        try {
            FxTestSupport.runOnFx(() -> templates.beginTemplate(py, dir, null));
            await("the wizard", () -> createButton() != null);
            FxTestSupport.runOnFx(() -> {
                TextField pkg = inScene(TextField.class).stream()
                        .filter(f -> "app".equals(f.getText()))
                        .findFirst()
                        .orElseThrow();
                pkg.setText("shop");
                createButton().fire();
            });
            await("the existing-files question", () -> !wizardError().isEmpty());
            FxTestSupport.runOnFx(() -> {
                assertTrue(wizardError().contains("README.md"), wizardError());
                assertTrue(
                        inScene(TextField.class).stream().anyMatch(f -> "shop".equals(f.getText())),
                        "what was typed is still there");
            });
            assertEquals(List.of("README.md"), tree(dir), "nothing is written before the user answers");

            FxTestSupport.runOnFx(() -> createButton().fire()); // "Create" again = create the missing ones
            await("the wizard to close", () -> createButton() == null);
            await(
                    "main.py to open",
                    () -> active() != null
                            && active().getPath() != null
                            && active().getPath().endsWith("src/shop/main.py"));
            assertEquals(6, tree(dir).size());
            assertEquals("MINE", Files.readString(dir.resolve("README.md")));
        } finally {
            closeOverlay();
        }
    }

    // --- T2: New Project From Template -------------------------------------------------------------

    @Test
    void theProjectWizardCreatesANamedFolderAndRegistersIt(@TempDir Path workspace) throws Exception {
        // The active file sits in a source package: the old flow scaffolded the project right there.
        Path pkg = Files.createDirectories(workspace.resolve("old/src/main/java/demo"));
        Path activeFile = Files.writeString(pkg.resolve("Bar.java"), "class Bar {}\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.<FileWorkflowCoordinator>field(controller, "fileWorkflows")
                .openPath(activeFile));
        await("Bar.java", () -> active() != null && activeFile.equals(active().getPath()));

        Template py = template("python-project");
        AtomicReference<Project> opened = new AtomicReference<>();
        AtomicReference<TemplateOutput.Target> openedOn = new AtomicReference<>();
        var realOpen = templates.openProject;
        Path parent = Files.createDirectories(workspace.resolve("projects"));
        try {
            FxTestSupport.runOnFx(() -> {
                templates.openProject = (p, target) -> {
                    opened.set(p);
                    openedOn.set(target);
                };
                templates.beginProject(py);
            });
            await("the project wizard", () -> createButton() != null);
            FxTestSupport.runOnFx(() -> {
                List<TextField> fields = inScene(TextField.class);
                TextField name = fields.stream()
                        .filter(f -> tr("template.project.namePrompt").equals(f.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                TextField location = fields.get(fields.indexOf(name) + 1);
                assertNotEquals(pkg.toString(), location.getText(), "never the active file's package folder");
                assertEquals(templates.defaultProjectParent().toString(), location.getText());
                createButton().fire(); // no name yet
            });
            FxTestSupport.drainFx();
            FxTestSupport.runOnFx(() -> {
                assertEquals(tr("template.project.nameRequired"), wizardError());
                List<TextField> fields = inScene(TextField.class);
                TextField name = fields.stream()
                        .filter(f -> tr("template.project.namePrompt").equals(f.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                fields.get(fields.indexOf(name) + 1).setText(parent.toString());
                name.setText("My Shop");
                assertTrue(
                        fields.stream().anyMatch(f -> "my_shop".equals(f.getText())),
                        "the package name follows the project name");
                assertEquals(
                        1,
                        labelTexts().stream()
                                .filter(tr("template.project.name")::equals)
                                .count(),
                        "the project name is asked once, not again as ${projectName}");
                createButton().fire();
            });
            await("the project to be opened", () -> opened.get() != null);

            Path root = parent.resolve("My Shop");
            assertEquals(
                    List.of(
                            ".gitignore",
                            "README.md",
                            "pyproject.toml",
                            "src/my_shop/__init__.py",
                            "src/my_shop/main.py",
                            "tests/test_main.py"),
                    tree(root));
            assertEquals(
                    List.of("src/main/java/demo/Bar.java"),
                    tree(workspace.resolve("old")),
                    "nothing landed beside the active file");
            assertEquals("My Shop", opened.get().name());
            assertEquals(
                    root.toAbsolutePath().normalize().toString(), opened.get().root());
            assertEquals("src/my_shop/main.py", openedOn.get().relative(), "opens on the file with the cursor");
            assertTrue(Files.readString(root.resolve("pyproject.toml")).contains("name = \"my_shop\""));

            // The same name again: the folder exists and is not empty, so nothing is touched.
            opened.set(null);
            FxTestSupport.runOnFx(() -> templates.beginProject(py));
            await("the project wizard", () -> createButton() != null);
            FxTestSupport.runOnFx(() -> {
                List<TextField> fields = inScene(TextField.class);
                TextField name = fields.stream()
                        .filter(f -> tr("template.project.namePrompt").equals(f.getPromptText()))
                        .findFirst()
                        .orElseThrow();
                fields.get(fields.indexOf(name) + 1).setText(parent.toString());
                name.setText("My Shop");
                createButton().fire();
            });
            await("the exists refusal", () -> !wizardError().isEmpty());
            FxTestSupport.runOnFx(() -> assertEquals(tr("template.project.exists", collapsed(root)), wizardError()));
            assertEquals(null, opened.get());
        } finally {
            FxTestSupport.runOnFx(() -> templates.openProject = realOpen);
            closeOverlay();
        }
    }

    // --- T16 / T19: managing and labelling ---------------------------------------------------------

    @Test
    void editUserTemplatesListsWhatCanBeEditedAndCreatesNothingUntilAsked() throws Exception {
        Path userDir = templates.templates.userDir();
        Files.createDirectories(userDir);
        Files.writeString(userDir.resolve("broken.json"), "{ nope");
        try {
            List<TemplateCoordinator.EditRow> rows = FxTestSupport.callOnFx(templates::editRows);
            assertFalse(Files.exists(userDir.resolve("example.json")), "no permanent example row is created");
            TemplateCoordinator.EditRow broken = rows.stream()
                    .filter(r -> r.label().equals("broken.json"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(
                    broken.detail()
                            .contains(
                                    tr("template.problem.malformedJson", 1, "").substring(0, 12)),
                    broken.detail());
            // T10: Reload says which file failed and why, through the error channel.
            FxTestSupport.runOnFx(templates::reloadTemplates);
            assertEquals(MessageLog.Severity.ERROR, lastStatus().severity());
            assertTrue(lastStatus().text().contains("broken.json"), lastStatus().text());
            assertTrue(
                    lastStatus()
                            .text()
                            .contains(
                                    tr("template.problem.malformedJson", 1, "").substring(0, 12)),
                    lastStatus().text());
            Files.delete(userDir.resolve("broken.json"));
            FxTestSupport.runOnFx(templates::reloadTemplates);
            assertEquals(tr("status.templatesReloaded"), lastStatus().text());
            Files.writeString(userDir.resolve("broken.json"), "{ nope");

            TemplateCoordinator.EditRow customize = rows.stream()
                    .filter(r -> r.label().equals(tr("template.edit.customize", "Python Project")))
                    .findFirst()
                    .orElseThrow();
            FxTestSupport.runOnFx(() -> customize.action().run());
            Path copy = userDir.resolve("python-project.json");
            assertTrue(Files.exists(copy), "the bundled multi-file template was copied for editing");
            await("the copy to open", () -> active() != null && copy.equals(active().getPath()));
            assertEquals(Template.Origin.USER, template("python-project").origin());
            assertEquals(6, template("python-project").files().size(), "and it is still the same six-file template");
        } finally {
            Files.deleteIfExists(userDir.resolve("broken.json"));
            Files.deleteIfExists(userDir.resolve("python-project.json"));
        }
    }

    @Test
    void wizardLabelsComeFromTheTemplateThenTheCatalogThenTheIdentifier() {
        Template t = new Template("x", "X", "", "", "", "", null).withLabels(Map.of("ticket", "Ticket number"));
        assertEquals("Ticket number", TemplateCoordinator.variableLabel(t, "ticket"));
        assertEquals(tr("template.var.baseName"), TemplateCoordinator.variableLabel(t, "baseName"));
        assertEquals("Issue title", TemplateCoordinator.variableLabel(t, "issueTitle"));
    }

    @Test
    void sameNamedTemplatesAreToldApartInThePicker() {
        Template bundled = new Template(
                "java-class", "Java Class", "A class", "", "", "", null, Map.of(), Template.Origin.BUNDLED, "");
        Template mine = new Template("my-class", "Java Class", "", "", "", "", null);
        Template plugin = new Template("p", "Other", "", "", "", "", null, Map.of(), Template.Origin.PLUGIN, "acme");
        var ambiguous = TemplateCoordinator.ambiguousNames(List.of(bundled, mine, plugin));
        assertEquals("Java Class (java-class)", TemplateCoordinator.pickerLabel(bundled, ambiguous));
        assertEquals("Java Class (my-class)", TemplateCoordinator.pickerLabel(mine, ambiguous));
        assertEquals("Other", TemplateCoordinator.pickerLabel(plugin, ambiguous));
        assertEquals(tr("template.origin.bundled") + " · A class", TemplateCoordinator.pickerDetail(bundled));
        assertEquals(tr("template.origin.user"), TemplateCoordinator.pickerDetail(mine));
        assertEquals(tr("template.origin.plugin", "acme"), TemplateCoordinator.pickerDetail(plugin));
    }

    @Test
    void aRefusedNameSaysWhy() throws Exception {
        Path dir = Files.createTempDirectory("editora-newfile-refusal");
        FxTestSupport.runOnFx(() -> templates.createFileOfType(dir, NewFileCatalog.byId("java.class"), "class"));
        assertEquals(
                tr("status.newfile.refused.javaKeyword", "class"), lastStatus().text());
        assertEquals(MessageLog.Severity.ERROR, lastStatus().severity());
        FxTestSupport.runOnFx(() -> templates.createFileOfType(dir, NewFileCatalog.byId("java.packageInfo"), ""));
        assertEquals(tr("status.newfile.refused.noPackage"), lastStatus().text());
        assertEquals(List.of(), tree(dir));
        Files.delete(dir);
    }

    @Test
    void aNewShellScriptIsExecutable(@TempDir Path dir) throws Exception {
        FxTestSupport.runOnFx(() -> templates.createFileOfType(dir, NewFileCatalog.byId("scripts.shell"), "x"));
        assertTrue(Files.exists(dir.resolve("x.sh")));
        if (dir.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertTrue(Files.isExecutable(dir.resolve("x.sh")));
        }
    }
}
