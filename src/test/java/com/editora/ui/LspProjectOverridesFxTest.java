package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.ProjectSettings;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's committed {@code .editora/settings.json} and what it is allowed to launch.
 *
 * <p>Two defects met here. The override was resolved for the status label and Doctor but never handed to
 * {@code LspManager.configure}, so the project's command was displayed and the global one ran. And once it
 * <em>is</em> handed over, a file anyone can commit chooses a program to run with the user's privileges — so
 * it is honoured only for a folder the user has trusted.
 */
@Tag("fx")
class LspProjectOverridesFxTest {

    private static final String PROJECT_JDTLS = "/opt/project/bin/jdtls";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private Path project;
    private ConfigManager config;
    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private FakeOps ops;

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private final class FakeOps extends LspOpsStub {
        Path projectRoot;

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public Path lspProjectRoot() {
            return projectRoot;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(root.resolve("project"));
        config = new ConfigManager(Files.createDirectories(root.resolve("config")));
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        LspTestHooks.useFakeSessions(manager);
        host = new FakeHost();
        host.settings.setRustLspEnabled(false); // the user switched rust-analyzer off
        ops = new FakeOps();
        ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> coordinator = new LspCoordinator(host, manager, ops));
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(() -> host.buffers.forEach(EditorBuffer::dispose));
        manager.close();
    }

    private void projectFile(String json) throws Exception {
        Path file = ProjectSettings.fileFor(project);
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }

    private void overrideJavaAndEnableRust() throws Exception {
        projectFile("{\"lspCommands\": {\"java\": \"" + PROJECT_JDTLS + "\"}, \"lspEnabled\": {\"rust\": true}}");
    }

    private void applySupport() throws Exception {
        FxTestSupport.runOnFx(coordinator::applySupport);
    }

    private String configuredJava() {
        return LspTestHooks.configuredCommand(manager, "java");
    }

    @Test
    void anUntrustedProjectCannotChooseWhatRuns() throws Exception {
        overrideJavaAndEnableRust();

        applySupport();

        assertEquals("jdtls", configuredJava(), "the user's own command must be the one configured to launch");
        assertEquals(List.of("jdtls"), FxTestSupport.callOnFx(() -> coordinator.serverArgv("java")), "Doctor shows it");
        assertFalse(FxTestSupport.callOnFx(() -> coordinator.serverEnabled("rust")), "rust stays off");
        assertTrue(
                host.statuses.contains(
                        tr("status.lsp.projectSettingsUntrusted", tr("command.lsp.trustProjectSettings"))),
                "the user is told the project's settings are being ignored, and how to allow them");
    }

    /** The original defect: with trust in place, the override must reach the launch, not just the label. */
    @Test
    void aTrustedProjectsCommandIsTheOneConfiguredAndLaunched() throws Exception {
        overrideJavaAndEnableRust();
        config.getTrustStore().trust(project);

        applySupport();

        assertEquals(PROJECT_JDTLS, configuredJava());
        assertTrue(FxTestSupport.callOnFx(() -> coordinator.serverEnabled("rust")));
        Path file = project.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        FxTestSupport.runOnFx(() -> manager.openDocument(file, project, "java", "class A {}\n"));
        assertEquals(PROJECT_JDTLS, LspTestHooks.launchCommand(manager, file).get(0));
    }

    @Test
    void aProjectMayAlwaysSwitchAServerOff() throws Exception {
        projectFile("{\"lspEnabled\": {\"java\": false}}");

        applySupport();

        assertFalse(FxTestSupport.callOnFx(() -> coordinator.serverEnabled("java")), "less is always allowed");
        assertFalse(
                host.statuses.contains(
                        tr("status.lsp.projectSettingsUntrusted", tr("command.lsp.trustProjectSettings"))),
                "nothing is being withheld, so there is nothing to announce");
    }

    @Test
    void trustingTheProjectAppliesItsSettingsAndIsRemembered() throws Exception {
        overrideJavaAndEnableRust();
        applySupport();
        List<List<String>> asked = new ArrayList<>();

        FxTestSupport.runOnFx(() -> {
            coordinator.trustConfirmer = (folder, requests) -> {
                asked.add(requests);
                return false;
            };
            coordinator.trustProjectSettings();
        });
        assertEquals(List.of(List.of("java: " + PROJECT_JDTLS, "rust")), asked, "the prompt shows what would run");
        assertEquals("jdtls", configuredJava(), "declining changes nothing");
        assertFalse(config.getTrustStore().isTrusted(project));

        FxTestSupport.runOnFx(() -> {
            coordinator.trustConfirmer = (folder, requests) -> true;
            coordinator.trustProjectSettings();
        });
        assertEquals(PROJECT_JDTLS, configuredJava());
        assertTrue(config.getTrustStore().isTrusted(project));
        assertTrue(
                Files.readString(config.shared().getTrustFile())
                        .contains(project.toRealPath().getFileName().toString()),
                "the decision is written to the trusted-folders file, where Settings lists it");
    }

    /** An edit to the file takes effect on the running configuration, not only on the labels. */
    @Test
    void reloadingAnEditedProjectFileReconfiguresTheManager() throws Exception {
        config.getTrustStore().trust(project);
        projectFile("{}");
        applySupport();
        assertEquals("jdtls", configuredJava());

        overrideJavaAndEnableRust();
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings);

        assertEquals(PROJECT_JDTLS, configuredJava());
    }

    /**
     * A window is configured in {@code init}, before it knows its project. The first buffer to sync must
     * pick the project's settings up rather than launch the command resolved without them.
     */
    @Test
    void aWindowThatLearnsItsProjectLateReconfiguresBeforeLaunching() throws Exception {
        overrideJavaAndEnableRust();
        config.getTrustStore().trust(project);
        ops.projectRoot = null;
        applySupport();
        assertEquals("jdtls", configuredJava());

        ops.projectRoot = project;
        Path file = project.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        FxTestSupport.runOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent("class A {}\n");
            host.buffers.add(b);
            host.active = b;
            coordinator.syncBuffer(b);
        });

        assertEquals(PROJECT_JDTLS, configuredJava());
    }

    @Test
    void theOnTypeFormattingSettingReachesJdtlsInitialization() throws Exception {
        host.settings.setLspOnTypeFormatting(true);
        applySupport();
        Path file = project.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        List<com.editora.lsp.FakeLanguageServer> fakes = LspTestHooks.useFakeSessions(manager);

        FxTestSupport.runOnFx(() -> manager.openDocument(file, project, "java", "class A {}\n"));
        int pushed = fakes.get(0).configurations.size();
        host.settings.setLspOnTypeFormatting(false);
        FxTestSupport.runOnFx(coordinator::applyOnTypeFormatting);

        assertEquals(pushed + 1, fakes.get(0).configurations.size(), "a running jdtls is told when it flips");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings =
                (Map<String, Object>) com.editora.lsp.FakeLanguageServer.last(fakes.get(0).configurations)
                        .getSettings();
        assertEquals(Map.of("onType", Map.of("enabled", false)), ((Map<?, ?>) settings.get("java")).get("format"));
    }
}
