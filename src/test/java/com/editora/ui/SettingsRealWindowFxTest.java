package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.template.Template;
import com.editora.toolbar.ToolbarCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings window as the controller wires it: its pages acting on the real trust store, toolbar, agent
 * and template folder of a running window, not on stand-ins.
 */
@Tag("fx")
class SettingsRealWindowFxTest {

    @TempDir
    Path dir;

    private FxWindowFixture fx;
    private SettingsWindow window;
    private Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
            fx.dispose();
        }
    }

    private void open(Path configDir) throws Exception {
        fx = configDir == null ? FxWindowFixture.create() : FxWindowFixture.create(configDir, shared -> {});
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        settings = fx.shared.getSettings();
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.field(fx.controller, "stage")));
    }

    private Region page(String name) {
        ListView<Object> sidebar = FxTestSupport.field(window, "sidebar");
        Object category = sidebar.getItems().stream()
                .filter(item -> item instanceof Enum<?> e && e.name().equals(name))
                .findFirst()
                .orElseThrow();
        sidebar.getSelectionModel().select(category);
        java.util.Map<Object, Region> pages = FxTestSupport.field(window, "pages");
        return pages.get(category);
    }

    private String echo() {
        StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    @Test
    void revokingATrustedFolderOnTheWorkspacePageRevokesItInTheStore() throws Exception {
        Path a = Files.createDirectories(dir.resolve("a"));
        Path b = Files.createDirectories(dir.resolve("b"));
        open(null);
        FxTestSupport.runOnFx(() -> {
            var trust = fx.shared.getTrustStore();
            trust.trust(a);
            trust.trust(b);
            window.refreshTrustedFolders();
            Region workspace = page("WORKSPACE");
            ListView<String> list = FxTestSupport.field(window, "trustedFoldersList");
            assertEquals(2, list.getItems().size());

            String entryForA = list.getItems().stream()
                    .filter(root -> Path.of(root).equals(a)
                            || root.endsWith(a.getFileName().toString()))
                    .findFirst()
                    .orElseThrow();
            list.getSelectionModel().select(entryForA);
            SettingsRig.button(workspace, tr("settings.trustedFolders.revoke")).fire();
            assertFalse(trust.isTrusted(a), "the store, not just the list");
            assertTrue(trust.isTrusted(b));
            assertEquals(1, list.getItems().size());

            SettingsRig.button(workspace, tr("settings.trustedFolders.revokeAll"))
                    .fire();
            assertFalse(trust.isTrusted(b));
            assertEquals(List.of(), list.getItems());
        });
        fx.shared.flushWrites();
        assertFalse(
                Files.readString(fx.configDir.resolve("trusted-folders.json"))
                        .contains(b.getFileName().toString()),
                "a revoked folder is not trusted again after a restart");
    }

    @Test
    void theToolbarPageChangesTheBarOfTheRunningWindow() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            Region toolbarPage = page("TOOLBAR");
            ToolbarCoordinator toolbar = FxTestSupport.field(fx.controller, "toolbarCoordinator");
            int before = toolbar.effectiveLayout().size();

            List<String> currentItems = FxTestSupport.field(window, "toolbarCurrentItems");
            @SuppressWarnings("unchecked")
            ListView<String> current = SettingsRig.all(toolbarPage, ListView.class).stream()
                    .filter(v -> v.getItems() == currentItems)
                    .findFirst()
                    .orElseThrow();
            current.getSelectionModel().select(0);
            SettingsRig.button(toolbarPage, tr("settings.toolbar.addSeparator")).fire();
            assertEquals(before + 1, settings.getToolbarLayout().size(), "the layout is saved");
            assertEquals(ToolbarCatalog.SEPARATOR, settings.getToolbarLayout().get(1), "after the selected item");

            SettingsRig.button(toolbarPage, tr("settings.toolbar.restoreDefault"))
                    .fire();
            assertEquals(List.of(), settings.getToolbarLayout(), "the default is \"nothing saved\"");
            assertEquals(ToolbarCatalog.defaultLayout(), toolbar.effectiveLayout());
            assertEquals(tr("status.toolbar.restored"), echo());
        });
        FxTestSupport.drainFx();
        FxTestSupport.runOnFx(() -> {
            ToolbarCoordinator toolbar = FxTestSupport.field(fx.controller, "toolbarCoordinator");
            List<?> nodes = FxTestSupport.field(toolbar, "customNodes");
            assertEquals(ToolbarCatalog.defaultLayout().size(), nodes.size(), "and the bar itself is rebuilt");
        });
    }

    @Test
    void pickingAnotherAgentOnTheAgentPageSwitchesTheAgentTheWindowUses() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            ComboBox<String> client = FxTestSupport.field(window, "agentClientCombo");
            String before = settings.getAgentClient();
            String other = client.getItems().stream()
                    .filter(id -> !id.equals(client.getValue()))
                    .findFirst()
                    .orElseThrow();
            client.setValue(other);
            assertEquals(other, settings.getAgentClient());
            assertNotEquals(before, settings.getAgentClient());

            // And back by the command instead: the combo follows without switching again.
            settings.setAgentClient(before);
            window.syncAgentCheck();
            assertEquals(com.editora.agent.AcpAgentRegistry.from(before).id(), client.getValue());
        });
    }

    @Test
    void removingAUserTemplateAsksInADialogAndDeletesTheFileOnlyOnOk() throws Exception {
        Path config = Files.createDirectories(dir.resolve("config"));
        Path file = Files.createDirectories(config.resolve("templates")).resolve("my-note.json");
        String json = "{\"name\":\"My Note\",\"fileName\":\"note.txt\",\"body\":\"x\"}";
        Files.writeString(file, json);
        open(config);
        FxTestSupport.runOnFx(() -> {
            Region templates = page("TEMPLATES");
            @SuppressWarnings("unchecked")
            ListView<Template> list = (ListView<Template>)
                    SettingsRig.all(templates, ListView.class).get(0);
            Template mine = list.getItems().stream()
                    .filter(t -> t.id().equals("my-note"))
                    .findFirst()
                    .orElseThrow();
            list.getSelectionModel().select(mine);
            Button remove = (Button) SettingsRig.button(templates, tr("settings.template.remove"));

            List<SettingsRig.Shown> asked = SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, remove::fire);
            assertEquals(1, asked.size());
            assertEquals(tr("settings.template.remove"), asked.get(0).title());
            assertEquals(
                    tr("settings.template.removeConfirm", "My Note", "my-note.json"),
                    asked.get(0).content());
            assertTrue(Files.exists(file), "declined: the file stays");

            list.getSelectionModel().select(mine);
            SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, remove::fire);
            assertFalse(Files.exists(file));
            assertFalse(list.getItems().stream().anyMatch(t -> t.id().equals("my-note")));
        });
    }

    @Test
    void aTextFieldCommitsWhenTheFocusLeavesIt() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            page("GIT");
            TextField gitPath = FxTestSupport.field(window, "gitPathField");
            TextField search = FxTestSupport.field(window, "searchField");
            Stage stage = FxTestSupport.field(window, "stage");
            gitPath.requestFocus();
            assertEquals(gitPath, stage.getScene().getFocusOwner());
            gitPath.setText("/opt/git/bin/git");
            assertNotEquals("/opt/git/bin/git", settings.getGitPath(), "typing alone commits nothing");

            search.requestFocus(); // Tab, or a click elsewhere
            assertEquals("/opt/git/bin/git", settings.getGitPath());
        });
    }

    @Test
    void resetToDefaultsIsNotDoneWhenTheBackupCannotBeWritten() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win"));
        open(null);
        FxTestSupport.runOnFx(() -> settings.setTabSize(7));
        fx.shared.flushWrites();
        // The backup is written beside settings.json: with the folder read-only it cannot be.
        java.io.File folder = fx.configDir.toFile();
        org.junit.jupiter.api.Assumptions.assumeTrue(folder.setWritable(false), "the folder can be made read-only");
        try {
            org.junit.jupiter.api.Assumptions.assumeFalse(
                    Files.isWritable(fx.configDir), "running as a user the read-only bit does not stop");
            FxTestSupport.runOnFx(() -> {
                Region advanced = page("ADVANCED");
                Button reset = (Button) SettingsRig.button(advanced, tr("settings.resetDefaults"));
                List<SettingsRig.Shown> shown = SettingsRig.answering(ButtonBar.ButtonData.OK_DONE, reset::fire);
                assertEquals(2, shown.size(), "the question, then why it was not done");
                assertEquals(tr("settings.reset.confirm"), shown.get(0).content());
                String[] around = tr("settings.reset.backupFailed", "\u0000").split("\u0000");
                assertTrue(
                        shown.get(1).content().startsWith(around[0]),
                        shown.get(1).content());
                assertTrue(
                        shown.get(1).content().endsWith(around[1]), shown.get(1).content());
                assertEquals(7, settings.getTabSize(), "without a copy of the old settings there is no reset");
            });
        } finally {
            folder.setWritable(true);
        }
    }

    @Test
    void cancellingResetToDefaultsChangesNothing() throws Exception {
        open(null);
        FxTestSupport.runOnFx(() -> {
            settings.setTabSize(7);
            Region advanced = page("ADVANCED");
            Button reset = (Button) SettingsRig.button(advanced, tr("settings.resetDefaults"));
            List<SettingsRig.Shown> shown = SettingsRig.answering(ButtonBar.ButtonData.CANCEL_CLOSE, reset::fire);
            assertEquals(1, shown.size());
            assertEquals(tr("settings.reset.title"), shown.get(0).title());
            assertEquals(7, settings.getTabSize());
        });
        try (var files = Files.list(fx.configDir)) {
            assertFalse(
                    files.anyMatch(p -> p.getFileName().toString().contains("before-reset")
                            || p.getFileName().toString().contains(".reset")),
                    "no backup is left behind by a reset that did not happen");
        }
    }
}
