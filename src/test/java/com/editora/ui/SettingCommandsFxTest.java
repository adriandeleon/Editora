package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The palette commands of {@link SettingCommands} (SET-06, Pauta 6.0.1): a page's command lists its settings by
 * their Settings rows' names with their values, flips the one picked or asks for its value, keeps what its row
 * would keep, and says what the setting is now.
 */
@Tag("fx")
@org.junit.jupiter.api.extension.ExtendWith(OffFxInPlace.class) // the picker filters off FX in the app
class SettingCommandsFxTest {

    private static FxWindowFixture fx;
    private static CommandRegistry registry;
    private static Stage stage;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        stage = FxTestSupport.field(fx.controller, "stage");
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private static Settings settings() {
        return fx.shared.getSettings();
    }

    private static Scene scene() {
        return stage.getScene();
    }

    private static String echo() {
        StatusBar status = FxTestSupport.field(fx.controller, "statusBar");
        return FxTestSupport.<Label>field(status, "echo").getText();
    }

    @Test
    void everyCommandHasATitleAndADescriptionInTheCatalog() {
        for (String id : SettingCommands.ids()) {
            assertTrue(registry.get(id).isPresent(), id + " is registered");
            assertNotEquals("command." + id, tr("command." + id), id + " has a title");
            assertNotEquals("command." + id + ".desc", tr("command." + id + ".desc"), id + " has a description");
        }
    }

    /** The rule's own example: terminal notifications were only a Settings row. */
    @Test
    void terminalNotificationsAreTurnedOffAndOnFromThePalette() throws Exception {
        boolean before = settings().isTerminalNotifications();
        String name = tr("settings.terminal.notifications");
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            assertTrue(
                    OverlayTestKit.pickerItems(scene()).size() > 10, "the picker lists the Terminal page's settings");
            assertTrue(OverlayTestKit.pick(scene(), name), "found by the row's name");
        });
        assertEquals(!before, settings().isTerminalNotifications());
        assertEquals(tr("status.settingChanged", name, tr(before ? "common.off" : "common.on")), echo());
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            OverlayTestKit.pick(scene(), name);
        });
        assertEquals(before, settings().isTerminalNotifications(), "picked again, it flips back");
    }

    /**
     * The question before a paste of several lines is a Settings row, so it is a palette setting too: turned off
     * from the palette as from its row (saved, applied, said), and on again.
     */
    @Test
    void thePasteConfirmationIsTurnedOffAndOnFromThePalette() throws Exception {
        boolean before = settings().isTerminalConfirmMultilinePaste();
        String name = tr("settings.terminal.confirmMultilinePaste");
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            assertTrue(OverlayTestKit.pick(scene(), name), "found by the row's name");
        });
        assertEquals(!before, settings().isTerminalConfirmMultilinePaste());
        assertEquals(tr("status.settingChanged", name, tr(before ? "common.off" : "common.on")), echo());
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            OverlayTestKit.pick(scene(), name);
        });
        assertEquals(before, settings().isTerminalConfirmMultilinePaste(), "picked again, it flips back");
    }

    @Test
    void aNumberIsKeptInsideItsRangeAndANonNumberIsNotKept() throws Exception {
        String name = tr("settings.terminal.fontSize");
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            OverlayTestKit.pick(scene(), name);
            assertEquals(
                    Integer.toString(settings().getTerminalFontSize()),
                    OverlayTestKit.formFields(scene()).get(0).getText(),
                    "the prompt starts from the value in force");
            OverlayTestKit.submitForm(scene(), "500");
        });
        assertEquals(72, settings().getTerminalFontSize(), "brought down to the largest the row allows");
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            OverlayTestKit.pick(scene(), name);
            OverlayTestKit.submitForm(scene(), "big");
        });
        assertEquals(72, settings().getTerminalFontSize(), "not a number: nothing is kept");
        assertEquals(tr("status.setting.invalidNumber", "big"), echo());
        FxTestSupport.runOnFx(() -> settings().setTerminalFontSize(0));
    }

    @Test
    void aChoiceListsTheRowsValuesAndATextTheRowRefusesIsNotKept() throws Exception {
        FxTestSupport.runOnFx(() -> {
            registry.run("terminal.changeSetting");
            OverlayTestKit.pick(scene(), tr("settings.terminal.cursor"));
            assertEquals(List.of("block", "underline", "bar"), OverlayTestKit.pickerItems(scene()));
            OverlayTestKit.pick(scene(), tr("settings.terminal.cursor.bar"));
        });
        assertEquals("bar", settings().getTerminalCursor());
        String branch = settings().getSyncBranch();
        FxTestSupport.runOnFx(() -> {
            registry.run("sync.changeSetting");
            OverlayTestKit.pick(scene(), tr("settings.sync.branch"));
            OverlayTestKit.submitForm(scene(), "../main");
        });
        assertEquals(branch, settings().getSyncBranch(), "the row's own check refuses it");
        assertEquals(tr("settings.sync.branch.invalid"), echo());
        FxTestSupport.runOnFx(() -> settings().setTerminalCursor("block"));
    }

    @Test
    void theApiKeyIsTypedIntoAFieldOfDotsAndNeverShown() throws Exception {
        FxTestSupport.runOnFx(() -> {
            registry.run("ai.setApiKey");
            List<TextField> fields = OverlayTestKit.formFields(scene());
            assertInstanceOf(PasswordField.class, fields.get(0));
            OverlayTestKit.submitForm(scene(), "sk-test-key");
        });
        com.editora.ai.AiProvider provider =
                com.editora.ai.AiProvider.from(settings().getAiProvider());
        assertEquals("sk-test-key", settings().getApiKeyFor(provider));
        assertFalse(echo().contains("sk-test-key"), "the status line does not show the key: " + echo());
        FxTestSupport.runOnFx(() -> settings().setApiKeyFor(provider, ""));
    }
}
