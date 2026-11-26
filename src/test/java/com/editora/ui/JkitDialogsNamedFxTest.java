package com.editora.ui;

import java.util.List;

import javafx.scene.control.TextArea;
import javafx.stage.Window;

import com.editora.toolchain.JkitCli;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-01 (Pauta 6.2.0) for the jkit dialogs, which only open where jkit is installed: the command sweeps never
 * reach them in CI, and on a machine with jkit (the Windows VM) they found a settings dialog whose boxes and a
 * report whose text read as nothing to a screen reader.
 */
@Tag("fx")
class JkitDialogsNamedFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void everyBoxOfTheSettingsDialogIsNamedByItsKey() throws Exception {
        List<JkitCli.ConfigSetting> settings = List.of(
                new JkitCli.ConfigSetting("jdk.vendor", "zulu", "file", "JKIT_JDK_VENDOR", "temurin", "the vendor"),
                new JkitCli.ConfigSetting("shell.auto_switch", "false", "default", null, "false", "switch on cd"),
                new JkitCli.ConfigSetting("mirror.url", "", "default", null, "", "where downloads come from"));
        AccessibleNames names = FxTestSupport.callOnFx(() -> {
            JkitSettingsDialog.show(null, "jkit", "0.3.0", settings, changed -> {});
            AccessibleNames seen = new AccessibleNames();
            for (Window window : List.copyOf(Window.getWindows())) {
                if (window.isShowing()
                        && window instanceof javafx.stage.Stage stage
                        && tr("toolchain.settings.title").equals(stage.getTitle())) {
                    window.getScene().getRoot().applyCss();
                    seen.scene(window.getScene(), "jkit settings");
                    window.hide();
                }
            }
            return seen;
        });
        assertEquals(1, names.scenes, "the dialog was shown");
        assertTrue(names.unnamed().isEmpty(), "unnamed: " + names.unnamed());
    }

    @Test
    void aReportsTextIsNamedByItsHeader() throws Exception {
        TextArea area = FxTestSupport.callOnFx(() -> ToolchainCommands.reportArea("jkit doctor", "all good\n"));
        assertEquals("jkit doctor", area.getAccessibleText());
    }
}
