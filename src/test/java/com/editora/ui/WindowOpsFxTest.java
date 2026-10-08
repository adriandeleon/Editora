package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ButtonBar;
import javafx.scene.control.Label;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.config.ConfigManager;
import com.editora.config.Project;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.maven.MavenArchetype;
import com.editora.search.SearchEverywhere;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a window does when one of its coordinators asks something of it — the window's half of each
 * coordinator's contract, called here as the coordinator calls it.
 */
@Tag("fx")
class WindowOpsFxTest {

    @TempDir
    Path dir;

    private AsyncTestScope async;
    private FxWindowFixture fx;
    private MainController controller;
    private Settings settings;
    private ConfigManager config;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        async = new AsyncTestScope();
        fx = async.own(FxWindowFixture.create());
        controller = fx.controller;
        settings = fx.shared.getSettings();
        config = FxTestSupport.field(controller, "config");
    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            FxTestSupport.runOnFx(() -> {
                SettingsWindow window = FxTestSupport.field(controller, "settingsWindow");
                FxTestSupport.<Stage>field(window, "stage").hide();
            });
        } finally {
            async.close();
        }
    }

    private <T> T ops(Class<T> type) throws Exception {
        return FxTestSupport.callOnFx(() -> WindowAdapterSweepFxTest.adapter(controller, type));
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    private EditorBuffer active() {
        return (EditorBuffer) FxTestSupport.call(controller, "activeBuffer", new Class<?>[] {});
    }

    private EditorBuffer addBuffer(String content) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setContent(content);
        FxTestSupport.call(controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
        return buffer;
    }

    private ToolWindow toolWindow(String field) {
        return FxTestSupport.field(controller, field);
    }

    private ToolWindowManager tools() {
        return FxTestSupport.field(controller, "toolWindows");
    }

    private void awaitStatus(String expected) throws Exception {
        SettingsRig.awaitFx("the status \"" + expected + "\"", () -> expected.equals(echo()));
    }

    // --- TODO tool window ------------------------------------------------------------------------

    @Test
    void aTodoEditRewritesTheLineOnlyWhileItStillReadsWhatTheScanSaw() throws Exception {
        Path file = Files.writeString(dir.resolve("work.txt"), "first\n// TODO fix this\nlast\n");
        TodoCoordinator.Ops todo = ops(TodoCoordinator.Ops.class);
        List<String> after = new ArrayList<>();

        FxTestSupport.runOnFx(
                () -> todo.applyLineEdit(file, 2, "// TODO fix this", "// DONE fix this", () -> after.add("edited")));
        awaitStatus(tr("status.todo.edited"));
        FxTestSupport.runOnFx(() -> {
            EditorBuffer buffer = active();
            assertEquals(file, buffer.getPath(), "the file is opened so the change is seen");
            assertEquals("first\n// DONE fix this\nlast\n", buffer.getContent());
            assertTrue(buffer.isDirty(), "an edit like any other: unsaved, undoable");
            assertEquals(List.of("edited"), after);
            controller.setStatus("");

            // The scan is stale: the line it wants to change reads differently now.
            todo.applyLineEdit(file, 2, "// TODO fix this", "// TODO (high) fix this", () -> after.add("stale"));
        });
        awaitStatus(tr("status.todo.lineChanged"));
        FxTestSupport.runOnFx(() -> {
            assertEquals("first\n// DONE fix this\nlast\n", active().getContent(), "nothing is overwritten");
            assertEquals(List.of("edited", "stale"), after, "the panel is told to scan again");
            controller.setStatus("");

            // The file is shorter than the scan thought.
            todo.applyLineEdit(file, 40, "// TODO gone", "// DONE gone", () -> after.add("beyond"));
        });
        awaitStatus(tr("status.todo.lineChanged"));
        FxTestSupport.runOnFx(() -> {
            assertEquals(List.of("edited", "stale", "beyond"), after);
            todo.applyLineEdit(file, 0, "first", "changed", null); // line 0 is no line; no callback to run
        });
        awaitStatus(tr("status.todo.lineChanged"));
        assertEquals(
                "first\n// TODO fix this\nlast\n", Files.readString(file), "nothing was saved behind the user's back");
    }

    @Test
    void aTodoInAReadOnlyBufferSaysWhyItWasNotChanged() throws Exception {
        Path file = Files.writeString(dir.resolve("vendored.txt"), "// TODO upstream\n");
        EditorBuffer buffer = SaveDecisionsFxTest.open(async, fx, file);
        TodoCoordinator.Ops todo = ops(TodoCoordinator.Ops.class);
        List<String> after = new ArrayList<>();
        FxTestSupport.runOnFx(() -> {
            buffer.setViewMode(true);
            todo.applyLineEdit(file, 1, "// TODO upstream", "// DONE upstream", () -> after.add("ran"));
        });
        awaitStatus(tr("status.todo.readOnly"));
        FxTestSupport.runOnFx(() -> {
            assertEquals("// TODO upstream\n", buffer.getContent());
            assertEquals(List.of(), after);

            ToolWindow window = toolWindow("todoToolWindow");
            tools().setVisible(window, true);
            assertFalse(todo.isToolWindowOpen());
            todo.toggleToolWindow();
            assertTrue(todo.isToolWindowOpen());
            assertTrue(tools().isOpen(window));
            todo.toggleToolWindow();
            assertFalse(todo.isToolWindowOpen());
        });
    }

    @Test
    void aClickedResultOpensItsFileAtItsLine() throws Exception {
        Path file = Files.writeString(dir.resolve("result.txt"), "a\nb\nc with TODO\nd\n");
        TodoCoordinator.Ops todo = ops(TodoCoordinator.Ops.class);
        FxTestSupport.runOnFx(() -> todo.openMatch(file, 3, 8));
        SettingsRig.awaitFx("the caret on the match", () -> {
            EditorBuffer b = active();
            return b != null && file.equals(b.getPath()) && b.getArea().getCurrentParagraph() == 2;
        });

        // The CSV grid's cell → source jump, in the same buffer: out-of-range targets land on the nearest place.
        CsvCoordinator.Ops csv = ops(CsvCoordinator.Ops.class);
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = active();
            csv.jumpTo(1, 1);
            assertEquals(1, b.getArea().getCurrentParagraph());
            assertEquals(1, b.getArea().getCaretColumn());
            csv.jumpTo(999, 999);
            assertEquals(b.getArea().getParagraphs().size() - 1, b.getArea().getCurrentParagraph());
            csv.jumpTo(-5, -5);
            assertEquals(0, b.getArea().getCaretPosition());
            csv.jumpTo(2, 999);
            assertEquals("c with TODO".length(), b.getArea().getCaretColumn());
        });
    }

    // --- Search Everywhere -----------------------------------------------------------------------

    @Test
    void searchEverywhereListsCommandsRunsTheOneChosenAndOpensAChosenFile() throws Exception {
        Path file = Files.writeString(dir.resolve("chosen.txt"), "chosen\n");
        SearchEverywherePopup.Ops search = FxTestSupport.field(controller, "searchEverywhereOps");
        FxTestSupport.runOnFx(() -> {
            List<SearchEverywhere.Item> all = search.commands("");
            List<SearchEverywhere.Item> blank = search.commands(null);
            assertEquals(all.size(), blank.size(), "no query lists every command, as the palette does");
            assertTrue(all.size() > 300, "every command: " + all.size());
            assertTrue(all.stream().allMatch(i -> i.kind() == SearchEverywhere.Kind.COMMAND));

            String zoomTitle = tr("command.view.textZoomIn");
            List<SearchEverywhere.Item> zoom = search.commands(zoomTitle);
            SearchEverywhere.Item zoomIn = zoom.stream()
                    .filter(i -> ((Command) i.payload()).id().equals("view.textZoomIn"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(zoomIn.enabled());
            assertNull(search.disabledReason(zoomIn));
            assertTrue(zoom.size() < all.size());

            // A command whose feature is off is listed, greyed, with the reason.
            settings.setGitSupport(false);
            SearchEverywhere.Item commit = search.commands(tr("command.git.commit")).stream()
                    .filter(i -> ((Command) i.payload()).id().equals("git.commit"))
                    .findFirst()
                    .orElseThrow();
            assertFalse(commit.enabled());
            assertNotNull(search.disabledReason(commit));

            double before = settings.getFontZoom();
            search.choose(zoomIn);
            assertEquals(before + 0.1, settings.getFontZoom(), 1e-9, "choosing a command runs it");

            SearchEverywhere.Item fileItem =
                    new SearchEverywhere.Item(SearchEverywhere.Kind.FILE, "chosen.txt", "", 1, file);
            assertNull(search.disabledReason(fileItem), "a file is always actionable");
            search.openDocs(fileItem);
            assertEquals(tr("status.searchEverywhere.noDocs"), echo());
            controller.setStatus("");
            search.openDocs(zoomIn); // a command has a page; with no browser to hand it to, nothing is said
            assertEquals("", echo());
            search.choose(fileItem);
        });
        SettingsRig.awaitFx("the chosen file to open", () -> controller.hasFileOpen(file));
    }

    @Test
    void searchEverywhereFindsAProjectsFilesAndSymbolsOnceItsIndexIsBuilt() throws Exception {
        Path root = Files.createDirectories(dir.resolve("proj"));
        Path nested = Files.createDirectories(root.resolve("src").resolve("pkg"));
        Path source = Files.writeString(
                nested.resolve("Zebracorn.java"),
                "package pkg;\n\npublic class Zebracorn {\n    static class Hoofprint {}\n}\n");
        Files.writeString(root.resolve("Quokka.md"), "# Quokka\n");
        MainController[] window = new MainController[1];
        FxTestSupport.runOnFx(() -> {
            settings.setSymbolIndex(true);
            Project project = fx.shared.projects().createOrGet("proj", root);
            fx.windowManager.openOrFocus(project);
            for (Object holder : FxTestSupport.<List<?>>field(fx.windowManager, "windows")) {
                MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                if (project.id().equals(FxTestSupport.<String>field(c, "projectKey"))) {
                    window[0] = c;
                }
            }
        });
        assertNotNull(window[0]);
        SearchEverywherePopup.Ops search = FxTestSupport.field(window[0], "searchEverywhereOps");
        boolean[] built = new boolean[1];
        FxTestSupport.runOnFx(() -> search.ensureIndex(() -> built[0] = true));
        SettingsRig.awaitFx("the project index", () -> built[0]);

        SettingsRig.awaitFx(
                "the indexed files", () -> !search.files("Zebracorn").isEmpty());
        FxTestSupport.runOnFx(() -> {
            SearchEverywhere.Item file = search.files("Zebracorn").get(0);
            assertEquals(SearchEverywhere.Kind.FILE, file.kind());
            assertEquals("Zebracorn.java", file.label());
            assertEquals("src/pkg", file.detail(), "the folder it is in, relative to the project");
            assertEquals(source, file.payload());

            SearchEverywhere.Item top = search.files("Quokka").get(0);
            assertEquals("Quokka.md", top.label());
            assertEquals("", top.detail(), "a file at the project's top has no folder to show");
            assertEquals(List.of(), search.files("nothing-is-called-this-zzzz"));
        });

        SettingsRig.awaitFx(
                "the indexed symbols", () -> !search.symbols("Hoofprint").isEmpty());
        FxTestSupport.runOnFx(() -> {
            SearchEverywhere.Item inner = search.symbols("Hoofprint").get(0);
            assertEquals(SearchEverywhere.Kind.SYMBOL, inner.kind());
            assertEquals("Hoofprint", inner.label());
            assertEquals("Zebracorn \u2014 Zebracorn.java:4", inner.detail(), "its container, then where it is");

            SearchEverywhere.Item type = search.symbols("Zebracorn").get(0);
            assertEquals("Zebracorn.java:3", type.detail(), "a top-level declaration has no container to name");
            assertEquals(List.of(), search.symbols("nothing-is-called-this-zzzz"));
            search.choose(inner);
        });
        SettingsRig.awaitFx("the symbol's file at its line", () -> {
            EditorBuffer b = (EditorBuffer) FxTestSupport.call(window[0], "activeBuffer", new Class<?>[] {});
            return b != null && source.equals(b.getPath()) && b.getArea().getCurrentParagraph() == 3;
        });
    }

    // --- Run / Debug -----------------------------------------------------------------------------

    @Test
    void theRunCoordinatorIsToldWhatKindOfProjectAFolderIsAndKeepsProgramArgumentsPerFile() throws Exception {
        Path maven = Files.createDirectories(dir.resolve("maven"));
        Files.writeString(maven.resolve("pom.xml"), "<project/>");
        Path gradleKts = Files.createDirectories(dir.resolve("gradle-kts"));
        Files.writeString(gradleKts.resolve("build.gradle.kts"), "plugins { application }\n");
        Path gradleSettingsOnly = Files.createDirectories(dir.resolve("gradle-settings"));
        Files.writeString(gradleSettingsOnly.resolve("settings.gradle"), "rootProject.name = 'x'\n");
        Path plain = Files.createDirectories(dir.resolve("plain"));
        Path main = Files.writeString(plain.resolve("Main.java"), "class Main {}\n");
        RunCoordinator.Ops run = ops(RunCoordinator.Ops.class);

        FxTestSupport.runOnFx(() -> {
            assertTrue(run.mavenProjectAt(maven));
            assertFalse(run.mavenProjectAt(plain));
            assertFalse(run.mavenProjectAt(null));
            assertTrue(run.gradleProjectAt(gradleKts));
            assertTrue(run.gradleProjectAt(gradleSettingsOnly));
            assertFalse(run.gradleProjectAt(maven));
            assertFalse(run.gradleProjectAt(null));

            assertEquals("plugins { application }\n", readGradle(gradleKts));
            assertEquals("", readGradle(gradleSettingsOnly), "a settings file is not the build file");
            assertEquals("", readGradle(null));
            assertEquals("", readGradle(plain));

            assertEquals("", run.programArgs(main), "none saved yet");
            run.setProgramArgs(main, "--verbose input.txt");
            assertEquals("--verbose input.txt", run.programArgs(main));
            assertEquals(
                    "--verbose input.txt",
                    config.getWorkspaceState().getProgramArgs().get(main.toString()));
            assertEquals("", run.programArgs(plain.resolve("Other.java")), "per file");

            assertEquals(List.of(), run.runConfigurations());
            assertEquals(config.getWorkspaceState().getSelectedRunConfig(), run.selectedRunConfigName());
            assertNull(run.projectRoot(), "the no-project window has no project root");

            // With no Gradle detected there is nothing to run the task with: no window, no process.
            run.runGradleRunTask(gradleKts);
            ToolWindow runWindow = toolWindow("runToolWindow");
            tools().closeAllOpen();
            run.openToolWindow();
            assertTrue(tools().isOpen(runWindow));
        });
    }

    private String readGradle(Path root) {
        return (String) FxTestSupport.invokeWith(controller, "readGradleBuildFile", Path.class, root);
    }

    @Test
    void theDebuggerPersistsItsWatchesAndDrivesItsToolWindow() throws Exception {
        DebugCoordinator.Ops debug = ops(DebugCoordinator.Ops.class);
        FxTestSupport.runOnFx(() -> {
            List<String> watches = new ArrayList<>(List.of("a + b", "list.size()"));
            debug.persistDebugWatches(watches);
            watches.add("added to the caller's list afterwards");
            assertEquals(List.of("a + b", "list.size()"), debug.debugWatches(), "a copy is stored");

            ToolWindow window = toolWindow("debugToolWindow");
            debug.setToolWindowAvailable(true);
            tools().setVisible(window, true);
            assertFalse(debug.isToolWindowOpen());
            debug.toggleToolWindow();
            assertTrue(debug.isToolWindowOpen());
            debug.toggleToolWindow();
            assertFalse(debug.isToolWindowOpen());
            debug.openToolWindow();
            assertTrue(tools().isOpen(window));
        });
        assertTrue(
                Files.readString(fx.configDir.resolve("workspace-state.json")).contains("list.size()"));
    }

    // --- LSP -------------------------------------------------------------------------------------

    @Test
    void libraryDocumentationOpensAsAReadOnlyBufferThatCanBeSelectedAgain() throws Exception {
        LspCoordinator.Ops lsp = ops(LspCoordinator.Ops.class);
        FxTestSupport.runOnFx(() -> {
            EditorBuffer doc = lsp.openReadOnlyDoc("String.class", "public final class String {}", "java");
            assertSame(doc, active());
            assertEquals("String.class", doc.getDisplayName());
            assertEquals("java", doc.getLanguage());
            assertTrue(doc.isViewMode(), "library source is for reading");
            assertNull(doc.getPath());
            assertFalse(lsp.activeEditable());

            EditorBuffer other = addBuffer("mine\n");
            assertTrue(lsp.activeEditable());
            assertTrue(lsp.selectBufferTab(doc));
            assertSame(doc, active());
            assertFalse(lsp.selectBufferTab(new EditorBuffer()), "a buffer that is not a tab here");
            assertSame(doc, active());
            assertNotNull(other);

            ToolWindow references = toolWindow("referencesToolWindow");
            ToolWindow hierarchy = toolWindow("hierarchyToolWindow");
            ToolWindow problems = toolWindow("problemsToolWindow");
            lsp.setProblemsAvailable(true);
            lsp.enableNavigationWindowsByDefault();
            assertTrue(tools().isVisible(references) && tools().isVisible(hierarchy));
            lsp.openReferencesWindow();
            assertTrue(tools().isOpen(references));
            lsp.openHierarchyWindow();
            assertTrue(tools().isOpen(hierarchy));
            lsp.setProblemsAvailable(false); // no managed file in front: the three windows step back
            assertFalse(tools().isOpen(hierarchy));
            assertFalse(tools().isOpen(problems));
        });
    }

    // --- Agent / AI ------------------------------------------------------------------------------

    @Test
    void theAgentIsToldWhenAnotherWindowHasTheFileItIsAboutToWriteAndKeepsItsSessions() throws Exception {
        Path root = Files.createDirectories(dir.resolve("proj"));
        Path shared = Files.writeString(root.resolve("shared.txt"), "shared\n");
        Path mine = Files.writeString(dir.resolve("mine.txt"), "mine\n");
        AgentCoordinator.Ops agent = ops(AgentCoordinator.Ops.class);
        MainController[] other = new MainController[1];
        FxTestSupport.runOnFx(() -> {
            assertNull(agent.projectRoot());
            Project project = fx.shared.projects().createOrGet("proj", root);
            fx.windowManager.openOrFocus(project);
            for (Object holder : FxTestSupport.<List<?>>field(fx.windowManager, "windows")) {
                MainController c = (MainController) FxTestSupport.call(holder, "controller", new Class<?>[] {});
                if (c != controller) {
                    other[0] = c;
                }
            }
            FxTestSupport.<FileWorkflowCoordinator>field(other[0], "fileWorkflows")
                    .openPath(shared);
        });
        SettingsRig.awaitFx("the other window's file", () -> other[0].hasFileOpen(shared));
        FxTestSupport.runOnFx(() -> {
            EditorBuffer elsewhere = agent.bufferInAnotherWindow(shared);
            assertNotNull(elsewhere, "open in the project's window");
            assertEquals(shared, elsewhere.getPath());
            assertNull(agent.bufferInAnotherWindow(mine), "open nowhere");

            int before = agent.sessionHistory().size();
            agent.rememberSession("session-1", dir.toString(), "Fix the parser", 1_000L, "claude");
            assertEquals(before + 1, agent.sessionHistory().size());
            assertEquals("session-1", agent.sessionHistory().get(0).sessionId());
            assertEquals("Fix the parser", agent.sessionHistory().get(0).label());

            ToolWindow window = toolWindow("agentToolWindow");
            agent.setToolWindowAvailable(true);
            tools().setVisible(window, true);
            agent.openToolWindow(false);
            assertTrue(tools().isOpen(window));
            agent.closeToolWindow();
            assertFalse(tools().isOpen(window));
            agent.openToolWindow(true);
            assertTrue(tools().isOpen(window));
            agent.toggleToolWindow();
            assertFalse(tools().isOpen(window));
            agent.setToolWindowAvailable(false);
            agent.openBackgroundBuffer(mine);
        });
        SettingsRig.awaitFx("the file the agent asked for", () -> controller.hasFileOpen(mine));
        FxTestSupport.runOnFx(() -> assertFalse(
                mine.equals(active() == null ? null : active().getPath())
                        && active().getArea().isFocused(),
                "opened in the background: the user's place is not taken"));
    }

    @Test
    void theAiCommitMessageLandsInTheCommitWindowAndTheHttpClientRemembersItsEnvironment() throws Exception {
        AiCoordinator.Ops ai = ops(AiCoordinator.Ops.class);
        HttpClientCoordinator.WindowOps http = ops(HttpClientCoordinator.WindowOps.class);
        FxTestSupport.runOnFx(() -> {
            assertNull(ai.repoRoot(), "no repository in the no-project window");
            ToolWindow commit = toolWindow("commitToolWindow");
            tools().setAvailable(commit, true);
            tools().setVisible(commit, true);
            ai.setCommitMessage("fix: handle the empty case");
            ai.openCommitWindow();
            assertTrue(tools().isOpen(commit));
            GitPanel panel = FxTestSupport.field(controller, "gitPanel");
            assertEquals(
                    "fix: handle the empty case",
                    FxTestSupport.<javafx.scene.control.TextInputControl>field(panel, "message")
                            .getText());

            EditorBuffer explained = new EditorBuffer();
            explained.setContent("An explanation.\n");
            ai.openTab(explained);
            assertSame(explained, active());

            assertEquals(config.getWorkspaceState().getHttpEnvironment(), http.savedEnvironment());
            http.persistEnvironment("staging");
            assertEquals("staging", http.savedEnvironment());
            EditorBuffer response = new EditorBuffer();
            http.openTab(response);
            assertSame(response, active());
        });
        assertTrue(
                Files.readString(fx.configDir.resolve("workspace-state.json")).contains("staging"));
    }

    // --- Doctor, builds, new Maven project -------------------------------------------------------

    @Test
    void theDoctorReadsTheServersAsConfiguredAndOpensTheSettingsPageOfARow() throws Exception {
        DoctorCoordinator.Ops doctor = ops(DoctorCoordinator.Ops.class);
        FxTestSupport.runOnFx(() -> {
            assertEquals(LspCoordinator.serverIds(), doctor.lspServerIds());
            settings.setLspSupport(true);
            settings.setJsonLspEnabled(true);
            settings.setJsonLspCommand("/opt/json-ls --stdio");
            FxTestSupport.<LspCoordinator>field(controller, "lspCoordinator").applySupport();
            assertTrue(doctor.lspServerEnabled("json"));
            assertEquals(List.of("/opt/json-ls", "--stdio"), doctor.lspServerArgv("json"));
            settings.setJsonLspEnabled(false);
            assertFalse(doctor.lspServerEnabled("json"));
            assertEquals(settings.isLspSupport(), doctor.lspFeatureEnabled());
            assertEquals(settings.isGitSupport(), doctor.gitFeatureEnabled());

            doctor.openSettingsFor("debug");
            SettingsWindow window = FxTestSupport.field(controller, "settingsWindow");
            assertTrue(FxTestSupport.<Stage>field(window, "stage").isShowing());
            javafx.scene.control.ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
            assertEquals("DEBUG", ((Enum<?>) sidebar.getSelectionModel().getSelectedItem()).name());
        });
    }

    @Test
    void aBuildWrapperRunsOnlyFromAFolderTheUserTrustedAndTheQuestionNamesBoth() throws Exception {
        Path root = Files.createDirectories(dir.resolve("untrusted"));
        Path wrapper = Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        FxTestSupport.runOnFx(() -> {
            List<BuildCoordinator> builds = FxTestSupport.field(controller, "buildCoordinators");
            BuildCoordinator.Ops build = FxTestSupport.field(builds.get(0), "ops");
            assertFalse(build.isTrusted(root));

            boolean[] answer = new boolean[1];
            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE, () -> answer[0] = build.confirmTrust(root, wrapper));
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.trust.title"), asked.get(0).title());
            assertEquals(tr("dialog.trust.header", "untrusted"), asked.get(0).header());
            assertEquals(
                    tr("dialog.trust.body", "mvnw", root.toString()),
                    asked.get(0).content());
            assertFalse(answer[0]);
            assertFalse(build.isTrusted(root), "asking is not trusting");

            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> answer[0] = build.confirmTrust(root, wrapper));
            assertTrue(answer[0]);
            build.trust(root);
            assertTrue(build.isTrusted(root));
            assertTrue(build.isTrusted(root.resolve("module")), "and so is what is inside it");
            assertNull(build.projectRoot());
        });
        fx.shared.flushWrites();
        assertTrue(
                Files.readString(fx.configDir.resolve("trusted-folders.json")).contains("untrusted"),
                "a security decision is on disk at once");
    }

    @Test
    void generatingFromAnArchetypeAsksFirstAndNamesTheArchetype() throws Exception {
        MavenProjectCoordinator.Ops maven = ops(MavenProjectCoordinator.Ops.class);
        MavenArchetype archetype =
                new MavenArchetype("org.example", "quickstart-archetype", "1.4", "A quick start", "", false);
        Path root = Files.createDirectories(dir.resolve("generated"));
        FxTestSupport.runOnFx(() -> {
            boolean[] answer = new boolean[1];
            List<SettingsRig.Shown> asked = SettingsRig.answering(
                    ButtonBar.ButtonData.CANCEL_CLOSE, () -> answer[0] = maven.confirmArchetype(archetype));
            assertEquals(1, asked.size());
            assertEquals(tr("dialog.mavenProject.trustTitle"), asked.get(0).title());
            assertEquals(
                    tr("dialog.mavenProject.trustHeader", "quickstart-archetype"),
                    asked.get(0).header());
            assertEquals(
                    tr("dialog.mavenProject.trustBody", archetype.gav()),
                    asked.get(0).content());
            assertFalse(answer[0]);
            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, () -> answer[0] = maven.confirmArchetype(archetype));
            assertTrue(answer[0]);

            // With projects switched off the generated folder is not registered as one.
            settings.setProjectSupport(false);
            maven.openProject(root, "generated", null);
            assertTrue(fx.shared.projects().list().isEmpty());
            assertEquals(
                    1, FxTestSupport.<List<?>>field(fx.windowManager, "windows").size());

            settings.setProjectSupport(true);
            maven.openProject(root, "generated", null);
            assertEquals(1, fx.shared.projects().list().size());
            assertEquals("generated", fx.shared.projects().list().get(0).name());
            assertEquals(
                    2, FxTestSupport.<List<?>>field(fx.windowManager, "windows").size(), "and gets its window");
            assertNull(maven.openBuffer(root.resolve("pom.xml")), "nothing of it is open in this window");
        });
    }
}
