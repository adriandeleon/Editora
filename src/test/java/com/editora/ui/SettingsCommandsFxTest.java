package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.control.ToggleButton;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Settings window's buttons and links that act are commands (KBD-01, Pauta 6.0.1), found the way the palette
 * finds them while the window shows: on the page that is open, never on one that is not, and a row's own button
 * only for the row the keyboard is in.
 */
@Tag("fx")
class SettingsCommandsFxTest {

    private SettingsRig rig;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        rig = SettingsRig.create();
        FxTestSupport.runOnFx(rig::show);
    }

    @AfterEach
    void tearDown() throws Exception {
        rig.close();
    }

    private static String notOffered(String id) {
        return tr("status.rows.notOffered", DebugPanelText.bareTitle(tr("command." + id)));
    }

    /** Runs {@code id} with the keyboard nowhere in particular: it is looked for below the window's root. */
    private boolean run(String id, List<String> status) {
        Stage stage = rig.stage();
        return FocusCommands.run(id, status::add, List.of(stage), List.of(), rows -> status.add("pick"));
    }

    @Test
    void theShellsButtonsAreCommandsOnEveryPage() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> status = new ArrayList<>();
            ToggleButton modifiedOnly = rig.control("modifiedOnly");
            boolean before = modifiedOnly.isSelected();
            assertTrue(run("settings.toggleModifiedOnly", status));
            assertEquals(!before, modifiedOnly.isSelected(), "as a click on the toggle");
            assertTrue(run("settings.toggleModifiedOnly", status));
            assertEquals(before, modifiedOnly.isSelected());
            rig.page("APPEARANCE");
            assertTrue(run("settings.pageHelp", status), "the open page's ?");
            assertTrue(status.isEmpty(), status.toString());
        });
    }

    @Test
    void aPagesButtonIsOfferedOnlyWhileItsPageIsOpen() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> status = new ArrayList<>();
            rig.page("APPEARANCE");
            assertFalse(run("settings.openSettingsFile", status), "the Advanced page is not open");
            rig.page("ADVANCED");
            assertTrue(run("settings.openSettingsFile", status), "its settings.json link");
            rig.page("PLUGINS");
            assertTrue(run("plugins.reload", status), "the Plugins page's Reload");
            assertFalse(run("settings.openSettingsFile", status));
            assertEquals(
                    List.of(notOffered("settings.openSettingsFile"), notOffered("settings.openSettingsFile")), status);
        });
    }

    @Test
    void aRowsButtonAnswersOnlyForTheRowTheKeyboardIsIn() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> status = new ArrayList<>();
            rig.page("TOOL_WINDOWS");
            assertFalse(run("settings.moveToolWindowLater", status), "no row has the keyboard: never the first row");
            assertFalse(run("settings.resetRow", status), "no setting row has the keyboard");
            rig.page("LSP");
            assertFalse(run("settings.installTool", status), "no card has the keyboard");
            assertEquals(3, status.size());
        });
    }

    @Test
    void theKeymapsPagesRowButtonsExistOnlyForTheSelectedShortcut() throws Exception {
        FxTestSupport.runOnFx(() -> {
            List<String> status = new ArrayList<>();
            rig.page("KEYMAPS");
            assertFalse(run("settings.removeShortcut", status), "no shortcut selected: no row buttons");
            assertEquals(List.of(notOffered("settings.removeShortcut")), status);
        });
    }

    @Test
    void eachPagesActingButtonIsItsCommandsOnThatPageAndNowhereElse() throws Exception {
        String[][] table = {
            {"APPEARANCE", "settings.openUserThemesFolder"},
            {"COMPLETION", "settings.chooseJavaCleanups"},
            {"TOOL_WINDOWS", "settings.restoreToolWindowLayout"},
            {"ADVANCED", "settings.revokeAllTrust"},
            {"ADVANCED", "settings.openSessionLog"},
            {"ADVANCED", "settings.restoreBackup"},
            {"KEYMAPS", "settings.resetAllShortcuts"},
            {"SPELL_CHECK", "settings.openTechnicalDictionary"},
            {"SPELL_CHECK", "settings.openPersonalDictionary"},
            {"SPELL_CHECK", "settings.addDictionaryWord"},
            {"SNIPPETS", "settings.addSnippet"},
            {"TEMPLATES", "settings.addTemplate"},
            {"ABBREVIATIONS", "settings.addAbbreviation"},
            {"EXTERNAL_TOOLS", "settings.addExternalTool"},
            {"DEBUG", "settings.addTypeRenderer"},
            {"REMOTE", "settings.addRemoteSite"},
            {"TODO", "settings.addTodoPattern"},
            {"TOOLBAR", "toolbar.addSeparator"},
            {"SYNC", "sync.openFolder"},
            {"MCP", "mcp.newToken"},
            {"MACROS", "macro.addTextStep"},
        };
        FxTestSupport.runOnFx(() -> {
            List<String> wrong = new ArrayList<>();
            for (String[] row : table) {
                rig.page(row[0]);
                rig.stage().getScene().getRoot().applyCss();
                rig.stage().getScene().getRoot().layout();
                if (FocusCommands.findBelow(rig.stage().getScene().getRoot(), row[1]) == null) {
                    wrong.add(row[1] + " not offered on " + row[0]);
                }
                rig.page(row[0].equals("APPEARANCE") ? "INTERFACE" : "APPEARANCE");
                if (FocusCommands.findBelow(rig.stage().getScene().getRoot(), row[1]) != null) {
                    wrong.add(row[1] + " offered with " + row[0] + " closed");
                }
            }
            assertEquals(List.of(), wrong);
        });
    }

    @Test
    void aRowsResetIsItsCommandOnlyWhileTheRowIsChanged() throws Exception {
        FxTestSupport.runOnFx(() -> {
            javafx.scene.layout.VBox row = new javafx.scene.layout.VBox();
            SettingRowState state = SettingRowState.of(row);
            int[] resets = {0};
            assertFalse(FocusCommands.find(row, "settings.resetRow").action().getAsBoolean(), "nothing to reset");
            state.setResetAction(() -> resets[0]++);
            assertFalse(FocusCommands.find(row, "settings.resetRow").action().getAsBoolean(), "not changed");
            state.setModified(true);
            assertTrue(FocusCommands.find(row, "settings.resetRow").action().getAsBoolean());
            assertEquals(1, resets[0], "as its Reset link does");
            assertTrue(FocusCommands.findBelow(row, "settings.resetRow") == null, "never found below a page");
        });
    }
}
