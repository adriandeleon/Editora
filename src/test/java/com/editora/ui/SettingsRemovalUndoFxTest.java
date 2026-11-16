package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.config.ProjectSettings;
import com.editora.config.Settings;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The small removals made in a window's own Settings (a dictionary word, a row put back to its default, a project's
 * value taken off a row): each is said in the editor window's status bar with how to take it back, and Edit: Undo
 * Last Removal, run in that window, puts it back, unless it was changed again since.
 */
@Tag("fx")
class SettingsRemovalUndoFxTest {

    private static final String HINT = " Undo Last Removal takes this back.";

    private FxWindowFixture fx;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        // The project file is written in place here, so a step sees it as it returns (the app writes it on a thread
        // of its own, PERF-01, Pauta 6.0.1).
        com.editora.config.ProjectSettingsFile.writeInPlaceForTest(true);
    }

    @org.junit.jupiter.api.AfterAll
    static void writeOffThreadAgain() {
        com.editora.config.ProjectSettingsFile.writeInPlaceForTest(false);
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

    private void undo() throws Exception {
        FxTestSupport.runOnFx(
                () -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run("edit.undoRemoval"));
    }

    /** What the editor window's status bar says. */
    private String status() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
            return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo").getText();
        });
    }

    /** Opens the page of the category named {@code name} and returns its content. FX thread. */
    private static Region page(SettingsWindow window, String name) {
        ListView<Object> sidebar = SettingsRig.control(window, "sidebar");
        Object category = sidebar.getItems().stream()
                .filter(item -> item instanceof Enum<?> e && e.name().equals(name))
                .findFirst()
                .orElseThrow();
        sidebar.getSelectionModel().select(category);
        java.util.Map<Object, Region> pages = SettingsRig.control(window, "pages");
        return pages.get(category);
    }

    private static Node rowOf(SettingsWindow w, Node control) {
        ProjectScopeSettings scope = FxTestSupport.field(w, "scopeUi");
        return (Node) FxTestSupport.call(scope, "rowOf", new Class<?>[] {Node.class}, control);
    }

    @Test
    void settingsRecordsOnItsEditorWindowsList() throws Exception {
        fx = FxWindowFixture.create();
        assertSame(fx.controller.removalUndo, fx.controller.settingsWindow.removalUndo);
    }

    // --- 6. a personal dictionary word ------------------------------------------------------------------------

    @Test
    void aRemovedDictionaryWordComesBackUnlessItWasAddedAgain() throws Exception {
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        SettingsWindow window = open();
        FxTestSupport.runOnFx(() -> {
            List.of("alpha", "quux", "zeta").forEach(config::addUserWord);
            Region page = page(window, "SPELL_CHECK");
            window.syncDictionaryList();
            ListView<String> list = SettingsRig.control(window, "dictionaryList");
            list.getSelectionModel().select("quux");
            ((Button) SettingsRig.button(page, tr("settings.dict.remove"))).fire();
            assertEquals(List.of("alpha", "zeta"), list.getItems());
        });
        assertFalse(config.getUserDictionary().contains("quux"));
        assertEquals("“quux” removed from the dictionary." + HINT, status(), "said in the editor window");

        undo();
        assertTrue(config.getUserDictionary().contains("quux"));
        assertEquals(
                List.of("alpha", "quux", "zeta"),
                FxTestSupport.callOnFx(() -> List.copyOf(SettingsRig.<ListView<String>>control(window, "dictionaryList")
                        .getItems())),
                "the open page lists it again");
        assertEquals("“quux” is in the dictionary again.", status());

        // Removed again, then added again by hand: there is nothing left to put back.
        FxTestSupport.runOnFx(() -> {
            ListView<String> list = SettingsRig.control(window, "dictionaryList");
            list.getSelectionModel().select("quux");
            ((Button) SettingsRig.button(page(window, "SPELL_CHECK"), tr("settings.dict.remove"))).fire();
            config.addUserWord("quux");
        });
        undo();
        assertEquals("This can no longer be undone: “quux” removed from the dictionary.", status());
        assertTrue(config.getUserDictionary().contains("quux"));
        FxTestSupport.runOnFx(() -> FxTestSupport.<Stage>field(window, "stage").hide());
    }

    // --- 7. one setting reset to its default --------------------------------------------------------------------

    @Test
    void aRowResetToItsDefaultGetsItsValueBackUnlessItWasChangedAgain() throws Exception {
        fx = FxWindowFixture.create();
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        SettingsWindow window = open();
        CheckBox minimap = FxTestSupport.field(window, "minimapCheck");
        Spinner<Integer> tab = FxTestSupport.field(window, "tabSizeSpinner");
        boolean defaultMinimap = new Settings().isShowMinimap();
        int defaultTab = new Settings().getTabSize();

        FxTestSupport.runOnFx(() -> {
            SettingRowState.of(rowOf(window, minimap)).resetNow(); // at its default: nothing goes
            assertEquals(0, fx.controller.removalUndo.size(), "a row at its default records nothing");

            minimap.setSelected(!defaultMinimap);
            SettingRowState.of(rowOf(window, minimap)).resetLink().fire();
            assertEquals(defaultMinimap, config.getSettings().isShowMinimap());
        });
        assertEquals("Setting reset to its default." + HINT, status());

        undo();
        FxTestSupport.runOnFx(() -> {
            assertEquals(!defaultMinimap, config.getSettings().isShowMinimap(), "the value it had");
            assertEquals(!defaultMinimap, minimap.isSelected(), "the control shows it");
            assertTrue(SettingRowState.of(rowOf(window, minimap)).isModified(), "and the row is marked again");
        });
        assertEquals("Setting put back to the value it had.", status());

        // Reset, then set to something else by hand: the newer value stays.
        FxTestSupport.runOnFx(() -> {
            tab.getValueFactory().setValue(defaultTab + 4);
            SettingRowState.of(rowOf(window, tab)).resetLink().fire();
            assertEquals(defaultTab, config.getSettings().getTabSize());
            tab.getValueFactory().setValue(defaultTab + 2);
        });
        undo();
        assertEquals("This can no longer be undone: Setting reset to its default.", status());
        assertEquals(defaultTab + 2, config.getSettings().getTabSize());
        FxTestSupport.runOnFx(() -> FxTestSupport.<Stage>field(window, "stage").hide());
    }

    private static JsonNode projectFile(Path project) throws Exception {
        return new ObjectMapper().readTree(ProjectSettings.fileFor(project).toFile());
    }

    @Test
    void aProjectValueTakenOffARowIsWrittenBackUnlessTheProjectSetOneSince() throws Exception {
        fx = FxWindowFixture.create();
        Path project = fx.useProject();
        SettingsWindow window = open();
        Spinner<Integer> tab = FxTestSupport.field(window, "tabSizeSpinner");
        FxTestSupport.runOnFx(() -> {
            ProjectScopeSettings ui = FxTestSupport.field(window, "scopeUi");
            ui.refresh();
            FxTestSupport.<ToggleButton>field(ui, "projectButton").setSelected(true);
            tab.getValueFactory().setValue(2);
            SettingRowState.peek(rowOf(window, tab)).resetNow(); // Use my setting
        });
        assertNull(projectFile(project).path("editor").get("tabSize"), "the project's value is gone");
        assertEquals("The project value was removed; the user setting applies." + HINT, status());

        undo();
        assertEquals(2, projectFile(project).get("editor").get("tabSize").asInt());
        assertEquals("The project value was put back.", status());

        FxTestSupport.runOnFx(() -> {
            SettingRowState.peek(rowOf(window, tab)).resetNow();
            tab.getValueFactory().setValue(3); // the project is given another value since
        });
        undo();
        assertEquals(
                "This can no longer be undone: The project value was removed; the user setting applies.", status());
        assertEquals(3, projectFile(project).get("editor").get("tabSize").asInt());
        FxTestSupport.runOnFx(() -> FxTestSupport.<Stage>field(window, "stage").hide());
    }
}
