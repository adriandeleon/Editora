package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;

import com.editora.config.ConfigManager;
import com.editora.config.ProjectSettings;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings' User / Project switch in a real project window (F4). */
@Tag("fx")
class SettingsProjectScopeFxTest {

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private SettingsWindow open() throws Exception {
        SettingsWindow window = fx.controller.settingsWindow;
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        return window;
    }

    private static ProjectScopeSettings scopeUi(SettingsWindow w) {
        return FxTestSupport.field(w, "scopeUi");
    }

    /** The row (a node of the window's rows) that holds {@code control} — a switch row's checkbox too. */
    private static Node rowOf(SettingsWindow w, Node control) {
        return (Node) FxTestSupport.call(scopeUi(w), "rowOf", new Class<?>[] {Node.class}, control);
    }

    private static JsonNode projectFile(Path project) throws Exception {
        return new ObjectMapper().readTree(ProjectSettings.fileFor(project).toFile());
    }

    @Test
    void theSwitchShowsOnlyInAProjectWindow() throws Exception {
        fx = FxWindowFixture.create();
        SettingsWindow w = open();
        FxTestSupport.runOnFx(() -> assertFalse(scopeUi(w).bar().isVisible(), "no project: no switch"));
    }

    @Test
    void projectModeWritesTheProjectFileAndLeavesTheUsersSettingAlone() throws Exception {
        fx = FxWindowFixture.create();
        Path project = fx.useProject();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        SettingsWindow w = open();
        FxTestSupport.runOnFx(() -> {
            config.getSettings().setTabSize(4);
            ProjectScopeSettings ui = scopeUi(w);
            ui.refresh();
            assertTrue(ui.bar().isVisible(), "a project window offers the switch");
            Spinner<Integer> tabSize = FxTestSupport.field(w, "tabSizeSpinner");
            Node tabRow = rowOf(w, tabSize);
            CheckBoxRows rows = new CheckBoxRows(w);

            ToggleButton projectButton = FxTestSupport.field(ui, "projectButton");
            String name = "fx-test"; // the project's name, as the main window's title shows it; its folder is "project"
            assertEquals(
                    tr("settings.scope.projectNamed", name), projectButton.getText(), "the pill names the project");
            Stage stage = FxTestSupport.field(w, "stage");
            assertEquals(tr("settings.window.title"), stage.getTitle());
            projectButton.setSelected(true);
            assertTrue(ui.projectMode());
            assertEquals(tr("settings.window.projectTitle", name), stage.getTitle(), "Project mode names the project");
            assertTrue(
                    FxTestSupport.<Label>field(ui, "note")
                            .getText()
                            .startsWith(tr(
                                    "settings.scope.projectNote",
                                    name,
                                    project.getFileName() + "/.editora/settings.json")),
                    "the note leads with the project");
            assertTrue(rows.minimapRow().isDisabled(), "a setting a project cannot set is greyed out");
            assertFalse(tabRow.isDisabled(), "tab size can be set per project");

            tabSize.getValueFactory().setValue(2);
            assertEquals(4, config.getSettings().getTabSize(), "the user's own tab size is untouched");
            try {
                assertEquals(
                        2, projectFile(project).get("editor").get("tabSize").asInt());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            SettingRowState state = SettingRowState.peek(tabRow);
            assertEquals(tr("settings.scope.setHereUntrusted"), state.scopeNote(), "not trusted yet: says so");
            assertTrue(FxTestSupport.<Node>field(ui, "trustButton").isVisible());

            // The trim-on-save rule exists only per project.
            ComboBox<String> trim = FxTestSupport.field(w, "trimOnSaveCombo");
            assertFalse(rowOf(w, trim).isDisabled());
            trim.setValue("true");
            try {
                assertTrue(projectFile(project)
                        .get("editor")
                        .get("trimTrailingWhitespace")
                        .asBoolean());
            } catch (Exception e) {
                throw new AssertionError(e);
            }

            // Back to User: the user's value shows, and the row says the project sets another.
            FxTestSupport.<ToggleButton>field(ui, "userButton").setSelected(true);
            assertEquals(tr("settings.window.title"), stage.getTitle(), "User mode: the plain title again");
            assertEquals(4, tabSize.getValue());
            assertEquals(tr("settings.scope.overriddenUntrusted"), state.scopeNote());
            assertFalse(rows.minimapRow().isDisabled());
            assertTrue(rowOf(w, trim).isDisabled(), "a project-only rule cannot be set as a user setting");

            // Trusted: the note says it applies.
            config.getTrustStore().trust(project);
            FxTestSupport.call(w, "load", new Class<?>[] {});
            assertEquals(tr("settings.scope.overridden"), state.scopeNote());

            // "Use my setting" in Project mode removes the project's value.
            FxTestSupport.<ToggleButton>field(ui, "projectButton").setSelected(true);
            assertEquals(2, tabSize.getValue(), "Project mode shows the project's value");
            state.resetNow();
            try {
                assertNull(projectFile(project).get("editor").get("tabSize"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals(4, config.getSettings().getTabSize());
        });
        assertTrue(Files.exists(ProjectSettings.fileFor(project)));
    }

    /** The Editor page's Show minimap row, a setting no project can set. */
    private record CheckBoxRows(SettingsWindow w) {
        Node minimapRow() {
            return rowOf(w, FxTestSupport.<javafx.scene.control.CheckBox>field(w, "minimapCheck"));
        }
    }
}
