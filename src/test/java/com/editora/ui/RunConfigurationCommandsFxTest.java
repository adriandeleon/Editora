package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.Breakpoint;
import com.editora.config.ConfigManager;
import com.editora.config.RunConfiguration;
import com.editora.config.Settings;
import com.editora.config.SharedRunConfigs;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.StackTraceLinks;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-configuration commands — Run/Debug Configuration…, Save, Delete, Export, Import, Debug via Build —
 * with their pickers answered in a real in-scene overlay. The configurations used cannot launch anything (a
 * script with no target, a type that does not debug), so what each command dispatched is read from what the
 * Run and Debug coordinators said about it.
 */
@Tag("fx")
class RunConfigurationCommandsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private final List<String> statuses = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> runStatuses = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();
    private final List<String> savedBuffers = new ArrayList<>();
    private final List<String> prompts = new ArrayList<>();

    private EditorBuffer active;
    private String suggested;
    private String promptAnswer;
    private Path projectRoot;
    private boolean saveOk = true;

    private ConfigManager config;
    private CommandRegistry registry;
    private OverlayHost overlay;
    private StackPane sceneRoot;
    private LspManager lspManager;
    private DapManager dap;
    private DebugCoordinator debug;
    private RunCoordinator run;
    private RunConfigurationCoordinator coordinator;

    /** The window's side of the Run and Debug coordinators: an active tab and a status bar. */
    private final class CoordinatorHostFake extends CoordinatorHostStub {
        final Settings settings = new Settings();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            if (active != null) {
                action.accept(active);
            }
        }

        @Override
        public void setStatus(String message) {
            runStatuses.add(message);
        }
    }

    private final class Host implements RunConfigurationCoordinator.Host {
        @Override
        public WindowChromeCoordinator chrome() {
            return null;
        }

        @Override
        public Button runConfigStopButton() {
            return null;
        }

        @Override
        public Button runConfigDebugButton() {
            return null;
        }

        @Override
        public Button runConfigRunButton() {
            return null;
        }

        @Override
        public ComboBox<RunConfiguration> runConfigCombo() {
            return null; // the commands work in a window whose toolbar has not been built
        }

        @Override
        public Stage stage() {
            return null;
        }

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public CommandRegistry registry() {
            return registry;
        }

        @Override
        public SettingsWindow settingsWindow() {
            return null;
        }

        @Override
        public OverlayHost overlayHost() {
            return overlay;
        }

        @Override
        public DebugCoordinator debugCoordinator() {
            return debug;
        }

        @Override
        public String homeCollapsed(String full) {
            return "~" + full.substring(dir.toString().length());
        }

        @Override
        public List<BuildCoordinator> buildCoordinators() {
            return List.of();
        }

        @Override
        public RunCoordinator runCoordinator() {
            return run;
        }

        @Override
        public String readGradleBuildFile(Path root) {
            return "";
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public void setError(String message) {
            errors.add(message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public boolean save(EditorBuffer buffer) {
            savedBuffers.add(buffer.getPath().getFileName().toString());
            return saveOk;
        }

        @Override
        public void requestSave() {}

        @Override
        public String programArgsFor(Path path) {
            return "--from " + path.getFileName();
        }

        @Override
        public String suggestedMainClass() {
            return suggested;
        }

        @Override
        public Path windowProjectRoot() {
            return projectRoot;
        }

        @Override
        public Path activeProjectRoot() {
            return projectRoot;
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            prompts.add(title + "|" + initial);
            onAccept.accept(promptAnswer);
        }
    }

    private static final class RunOps implements RunCoordinator.Ops {
        final List<String> edited;

        RunOps(List<String> edited) {
            this.edited = edited;
        }

        @Override
        public void openToolWindow() {}

        @Override
        public void onRunStateChanged() {}

        @Override
        public void editConfiguration(String name) {
            edited.add(name);
        }

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return true;
        }

        @Override
        public String programArgs(Path path) {
            return "";
        }

        @Override
        public void setProgramArgs(Path path, String args) {}

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public Path javaProjectRoot(Path file) {
            return null;
        }

        @Override
        public Path projectRoot() {
            return null;
        }

        @Override
        public boolean javaLaunchAvailable() {
            return false;
        }

        @Override
        public List<RunConfiguration> runConfigurations() {
            return List.of();
        }

        @Override
        public String selectedRunConfigName() {
            return "";
        }

        @Override
        public void resolveJavaMainClasses(Path routingFile, Consumer<List<JavaMainClass>> cb) {}

        @Override
        public void resolveJavaLaunch(Path routingFile, JavaMainClass mainClass, Consumer<JavaLaunchInfo> cb) {}

        @Override
        public boolean mavenProjectAt(Path root) {
            return false;
        }

        @Override
        public boolean gradleProjectAt(Path root) {
            return false;
        }

        @Override
        public void resolveMavenClasspath(Path root, Consumer<List<String>> cb) {}

        @Override
        public void runGradleRunTask(Path root) {}
    }

    private static final class DebugOps implements DebugCoordinator.Ops {
        @Override
        public void openToolWindow() {}

        @Override
        public void editConfiguration(String name) {}

        @Override
        public void toggleToolWindow() {}

        @Override
        public void setToolWindowAvailable(boolean available) {}

        @Override
        public void setStatusDebug(String text) {}

        @Override
        public void setStatusDebugLoading(boolean loading) {}

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return true;
        }

        @Override
        public String programArgs(Path path) {
            return "";
        }

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public void openPath(Path file) {}

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return null;
        }

        @Override
        public List<String> debugWatches() {
            return List.of();
        }

        @Override
        public void persistDebugWatches(List<String> watches) {}

        @Override
        public Map<String, List<Breakpoint>> breakpointMap() {
            return Map.of();
        }

        @Override
        public void saveBreakpoints() {}
    }

    private CoordinatorHostFake windowHost;

    @BeforeEach
    void setUp() throws Exception {
        config = new ConfigManager(Files.createDirectories(dir.resolve("config")));
        registry = new CommandRegistry();
        lspManager = new LspManager((f, d) -> {}, (t, m) -> {});
        LspTestHooks.useFakeSessions(lspManager);
        lspManager.configure(true, Map.of("java", "jdtls"));
        dap = new DapManager(lspManager);
        FxTestSupport.runOnFx(() -> {
            sceneRoot = new StackPane();
            new Scene(sceneRoot, 900, 600);
            overlay = new OverlayHost();
            overlay.install(sceneRoot);
            windowHost = new CoordinatorHostFake();
            windowHost.settings.setDebugSupport(true);
            windowHost.settings.setLspSupport(true);
            LspCoordinator lsp = new LspCoordinator(windowHost, lspManager, new LspOpsStub());
            lsp.setServerAvailableForTest("java", true);
            dap.configure(true, dir.resolve("no-plugin-here").toString());
            debug = new DebugCoordinator(windowHost, dap, lspManager, lsp, new DebugOps());
            run = new RunCoordinator(windowHost, new RunOps(opened));
            coordinator = new RunConfigurationCoordinator(new Host());
            coordinator.runConfigEditor = name -> opened.add("editor:" + name);
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            run.shutdown();
            debug.shutdown();
            dap.shutdown();
        });
        lspManager.shutdownAll();
    }

    // --- harness --------------------------------------------------------------------------------------

    private static RunConfiguration script(String name) {
        return new RunConfiguration(name, "shell", "", "", "", "", "", "", "", "", ""); // no target: runs nothing
    }

    private static RunConfiguration python(String name, String target) {
        return new RunConfiguration(name, "python", target, "", "", "", "", "", "", "", "");
    }

    private void configurations(RunConfiguration... configs) throws Exception {
        FxTestSupport.runOnFx(() -> config.getWorkspaceState().setRunConfigurations(new ArrayList<>(List.of(configs))));
    }

    private List<String> names() throws Exception {
        return FxTestSupport.callOnFx(() -> config.getWorkspaceState().getRunConfigurations().stream()
                .map(RunConfiguration::name)
                .toList());
    }

    /** The picker on screen, or null. FX thread. */
    private Node picker() {
        return sceneRoot.lookup(".command-palette");
    }

    @SuppressWarnings("unchecked")
    private List<String> pickerRows() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            Node card = picker();
            assertNotNull(card, "a picker is showing");
            ListView<RunConfiguration> list = (ListView<RunConfiguration>) card.lookup(".list-view");
            return list.getItems().stream()
                    .map(c -> c.name() + " → " + RunConfigurationCoordinator.runConfigDetail(c))
                    .toList();
        });
    }

    /** Highlights row {@code index} of the picker and presses {@code key} in its field. */
    @SuppressWarnings("unchecked")
    private void answerPicker(int index, KeyCode key) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Node card = picker();
            assertNotNull(card, "a picker is showing");
            ((ListView<RunConfiguration>) card.lookup(".list-view"))
                    .getSelectionModel()
                    .select(index);
            TextField field = (TextField) card.lookup(".text-field");
            field.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", key, false, false, false, false));
        });
        FxTestSupport.drainFx();
    }

    private EditorBuffer buffer(Path file, String source) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(source);
            b.markClean();
            active = b;
            return b;
        });
    }

    // --- the pickers ------------------------------------------------------------------------------------

    @Test
    void withNoConfigurationsEveryPickerCommandSaysSo() throws Exception {
        FxTestSupport.runOnFx(() -> {
            coordinator.runSavedConfig();
            coordinator.debugSavedConfig();
            coordinator.deleteRunConfig();
            assertNull(picker(), "no empty picker is shown");
        });

        String none = tr("status.run.noConfigs");
        assertEquals(List.of(none, none, none), statuses);
    }

    @Test
    void theRunPickerListsEachConfigurationWithWhatItLaunchesAndRunsTheChosenOne() throws Exception {
        configurations(
                new RunConfiguration("Server", "demo.Server", "", "", "", ""),
                python("Report", "report.py"),
                script("Deploy"));

        FxTestSupport.runOnFx(coordinator::runSavedConfig);
        assertEquals(List.of("Server → demo.Server", "Report → report.py", "Deploy → "), pickerRows());
        answerPicker(2, KeyCode.ENTER);

        assertNull(FxTestSupport.callOnFx(this::picker), "choosing closes the picker");
        assertEquals(List.of(tr("status.run.configNeedsTarget", "Deploy")), runStatuses, "Deploy was the one run");
        assertEquals(List.of("Deploy"), opened, "and its form is opened to put it right");

        runStatuses.clear();
        FxTestSupport.runOnFx(coordinator::runSavedConfig);
        answerPicker(0, KeyCode.ESCAPE);
        assertNull(FxTestSupport.callOnFx(this::picker));
        assertEquals(List.of(), runStatuses, "a dismissed picker runs nothing");
    }

    @Test
    void theDebugPickerDebugsTheChosenConfiguration() throws Exception {
        configurations(script("Deploy"), python("Report", "report.py"));

        FxTestSupport.runOnFx(coordinator::debugSavedConfig);
        assertEquals(2, pickerRows().size());
        answerPicker(1, KeyCode.ENTER);

        assertEquals(List.of(tr("status.debug.configTypeUnsupported", "python")), runStatuses);
    }

    @Test
    void deletingAConfigurationRemovesItAndItsCommands() throws Exception {
        configurations(script("Deploy"), python("Report", "report.py"));
        FxTestSupport.runOnFx(coordinator::refreshRunConfigs);
        assertTrue(registry.get(RunConfiguration.commandIdFor("Deploy")).isPresent());
        assertTrue(registry.get(RunConfiguration.debugCommandIdFor("Deploy")).isPresent());

        FxTestSupport.runOnFx(coordinator::deleteRunConfig);
        answerPicker(0, KeyCode.ENTER);

        assertEquals(List.of("Report"), names());
        assertEquals(List.of(tr("status.run.configDeleted", "Deploy")), statuses);
        assertFalse(registry.get(RunConfiguration.commandIdFor("Deploy")).isPresent(), "its palette entry is gone");
        assertFalse(registry.get(RunConfiguration.debugCommandIdFor("Deploy")).isPresent());
        assertTrue(registry.get(RunConfiguration.commandIdFor("Report")).isPresent());
    }

    @Test
    void eachConfigurationGetsARunAndADebugCommandThatDispatchToIt() throws Exception {
        configurations(script("Deploy"), python("Report", "report.py"));
        FxTestSupport.runOnFx(coordinator::refreshRunConfigs);

        Command runDeploy =
                registry.get(RunConfiguration.commandIdFor("Deploy")).orElseThrow();
        assertEquals(tr("run.config.runCommandTitle", "Deploy"), runDeploy.title());
        assertEquals(
                tr("run.config.debugCommandTitle", "Report"),
                registry.get(RunConfiguration.debugCommandIdFor("Report"))
                        .orElseThrow()
                        .title());
        FxTestSupport.runOnFx(() -> {
            registry.run(RunConfiguration.commandIdFor("Deploy"));
            registry.run(RunConfiguration.debugCommandIdFor("Report"));
        });
        assertEquals(
                List.of(
                        tr("status.run.configNeedsTarget", "Deploy"),
                        tr("status.debug.configTypeUnsupported", "python")),
                runStatuses);

        // Renamed in Settings: the old ids must not linger in the palette.
        configurations(script("Ship"));
        FxTestSupport.runOnFx(coordinator::refreshRunConfigs);
        assertFalse(registry.get(RunConfiguration.commandIdFor("Deploy")).isPresent());
        assertFalse(registry.get(RunConfiguration.debugCommandIdFor("Report")).isPresent());
        assertTrue(registry.get(RunConfiguration.commandIdFor("Ship")).isPresent());
    }

    @Test
    void withNoToolbarTheSelectionCommandsDoNothingAndEditOpensThePage() throws Exception {
        FxTestSupport.runOnFx(() -> {
            assertNull(coordinator.selectedRunConfig());
            coordinator.onRunSelectedConfig();
            coordinator.onDebugSelectedConfig();
            coordinator.onStopRun();
            coordinator.updateRunConfigButtons();
            coordinator.refreshRunConfigToolbar();
            coordinator.editRunConfigs();
        });

        assertEquals(List.of(), runStatuses);
        assertEquals(List.of("editor:null"), opened, "with nothing selected the page opens on no configuration");
    }

    // --- Save -------------------------------------------------------------------------------------------

    @Test
    void savingAConfigurationNeedsAJavaFileWithAMainMethod() throws Exception {
        FxTestSupport.runOnFx(coordinator::saveRunConfig);
        buffer(dir.resolve("notes.txt"), "text\n");
        FxTestSupport.runOnFx(coordinator::saveRunConfig);
        buffer(dir.resolve("Lib.java"), "class Lib {}\n");
        FxTestSupport.runOnFx(coordinator::saveRunConfig);

        assertEquals(
                List.of(tr("status.run.needJavaFile"), tr("status.run.needJavaFile"), tr("status.run.noMainInFile")),
                statuses);
        assertEquals(List.of(), prompts);
    }

    @Test
    void aSavedConfigurationTakesTheFilesMainClassAndArgumentsUnderTheNameGiven() throws Exception {
        configurations(script("Deploy"));
        buffer(dir.resolve("src/demo/Server.java"), "package demo;\nclass Server {}\n");
        suggested = "demo.Server";

        promptAnswer = "   ";
        FxTestSupport.runOnFx(coordinator::saveRunConfig);
        promptAnswer = null;
        FxTestSupport.runOnFx(coordinator::saveRunConfig);
        assertEquals(List.of("Deploy"), names(), "a blank or cancelled name saves nothing");
        assertEquals(List.of(), statuses);

        promptAnswer = "  Local server ";
        FxTestSupport.runOnFx(coordinator::saveRunConfig);

        assertEquals(tr("run.config.saveTitle") + "|Server", prompts.get(2), "the class's simple name is offered");
        assertEquals(List.of("Deploy", "Local server"), names());
        RunConfiguration saved = FxTestSupport.callOnFx(
                () -> config.getWorkspaceState().getRunConfigurations().get(1));
        assertEquals("demo.Server", saved.mainClass());
        assertEquals("--from Server.java", saved.args());
        assertTrue(saved.isJava());
        assertEquals(List.of(tr("status.run.configSaved", "Local server")), statuses);
        assertTrue(registry.get(RunConfiguration.commandIdFor("Local server")).isPresent());
    }

    // --- Export / Import --------------------------------------------------------------------------------

    @Test
    void exportAndImportNeedAProjectAndSomethingToMove() throws Exception {
        FxTestSupport.runOnFx(() -> {
            coordinator.exportRunConfigs();
            coordinator.importRunConfigs();
        });
        projectRoot = Files.createDirectories(dir.resolve("proj"));
        FxTestSupport.runOnFx(() -> {
            coordinator.exportRunConfigs();
            coordinator.importRunConfigs();
        });

        assertEquals(
                List.of(
                        tr("status.run.configsNeedProject"),
                        tr("status.run.configsNeedProject"),
                        tr("status.run.noConfigs"),
                        tr(
                                "status.run.noSharedConfigs",
                                "~/proj/" + SharedRunConfigs.DIR + "/" + SharedRunConfigs.FILE)),
                statuses);
        assertFalse(Files.exists(SharedRunConfigs.fileFor(projectRoot)));
    }

    @Test
    void exportedConfigurationsAreImportedByNameWithoutDuplicates() throws Exception {
        projectRoot = Files.createDirectories(dir.resolve("proj"));
        configurations(script("Deploy"), python("Report", "report.py"));

        FxTestSupport.runOnFx(coordinator::exportRunConfigs);
        assertTrue(Files.isRegularFile(SharedRunConfigs.fileFor(projectRoot)));
        assertEquals(
                List.of(tr(
                        "status.run.configsExported",
                        2,
                        "~/proj/" + SharedRunConfigs.DIR + "/" + SharedRunConfigs.FILE)),
                statuses);

        // A colleague's window: one configuration of its own, and an older "Report".
        statuses.clear();
        configurations(script("Mine"), python("Report", "old-report.py"));
        FxTestSupport.runOnFx(coordinator::importRunConfigs);

        assertEquals(List.of("Mine", "Report", "Deploy"), names());
        assertEquals(
                "report.py",
                FxTestSupport.callOnFx(() ->
                        config.getWorkspaceState().getRunConfigurations().get(1).target()),
                "the shared one replaces the same name");
        assertEquals(List.of(tr("status.run.configsImported", 2)), statuses);
        assertTrue(registry.get(RunConfiguration.commandIdFor("Deploy")).isPresent());
    }

    @Test
    void anExportThatCannotBeWrittenIsReportedAsAnError() throws Exception {
        projectRoot = Files.writeString(dir.resolve("not-a-folder"), "a file where the project should be");
        configurations(script("Deploy"));

        FxTestSupport.runOnFx(coordinator::exportRunConfigs);

        assertEquals(1, errors.size(), errors.toString());
        assertTrue(
                errors.get(0)
                        .startsWith(tr("status.run.configsExportFailed", "").trim()),
                errors.get(0));
        assertEquals(List.of(), statuses, "nothing claims the export happened");
        assertEquals("a file where the project should be", Files.readString(projectRoot));
    }

    // --- Debug via Build --------------------------------------------------------------------------------

    @Test
    void debugViaBuildSaysWhatItNeedsAtEachStep() throws Exception {
        FxTestSupport.runOnFx(coordinator::debugViaBuild);
        assertEquals(List.of(tr("status.debug.needJavaFile")), statuses);

        statuses.clear();
        buffer(dir.resolve("loose/Loose.java"), "class Loose {}\n");
        FxTestSupport.runOnFx(coordinator::debugViaBuild); // jdtls has no java-debug: nothing to attach with
        assertEquals(List.of(tr("status.debug.unavailable")), statuses);

        statuses.clear();
        FxTestSupport.runOnFx(() -> {
            dap.setServerProvidesJavaDebug(true);
            coordinator.debugViaBuild();
        });
        assertEquals(List.of(tr("status.debug.noProject")), statuses);

        statuses.clear();
        Path project = Files.createDirectories(dir.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n");
        EditorBuffer b = buffer(project.resolve("src/main/java/App.java"), "class App {}\n");
        saveOk = false;
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().insertText(0, "// edited\n");
            coordinator.debugViaBuild();
        });
        assertEquals(List.of("App.java"), savedBuffers);
        assertEquals(List.of(), statuses, "a refused save stops here");

        saveOk = true;
        FxTestSupport.runOnFx(coordinator::debugViaBuild);
        assertEquals(List.of(tr("status.debug.viaBuildUnsupported")), statuses, "no build tool is on to run it");
    }

    @Test
    void aBuildFileIsReadAsTextAndAMissingOneAsEmpty() throws Exception {
        Path pom = Files.writeString(dir.resolve("pom.xml"), "<project>spring-boot</project>");

        assertEquals("<project>spring-boot</project>", RunConfigurationCoordinator.readTextOrEmpty(pom));
        assertEquals("", RunConfigurationCoordinator.readTextOrEmpty(dir.resolve("missing.xml")));
        assertEquals("", RunConfigurationCoordinator.readTextOrEmpty(dir), "a folder is not a build file");
    }
}
