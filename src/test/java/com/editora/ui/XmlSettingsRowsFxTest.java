package com.editora.ui;

import java.util.Map;

import javafx.scene.control.Control;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.config.Settings;
import com.editora.editor.ControlNames;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * XML/pom review 3, C28: "Keep text as written in" showed about a third of its default list in a 220 px field, and
 * "(XML server only)" repeated under the "Formatting (XML server)" heading.
 */
@Tag("fx")
class XmlSettingsRowsFxTest {

    private static FxWindowFixture fx;
    private static SettingsWindow window;
    private static Settings settings;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        settings = fx.shared.getSettings();
        window = FxTestSupport.field(fx.controller, "settingsWindow");
        FxTestSupport.runOnFx(() -> window.show(FxTestSupport.<Stage>field(fx.controller, "stage")));
        FxTestSupport.drainFx();
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<Stage>field(window, "stage").hide());
            fx.dispose();
        }
    }

    private static TextField field(String key) {
        Map<String, Control> controls = FxTestSupport.field(window, "settingControls");
        Control control = controls.get(key);
        assertNotNull(control, "no Settings row for " + key);
        return (TextField) control;
    }

    @Test
    void theKeepTextListIsWideAndItsWholeValueIsTheTooltip() throws Exception {
        TextField keep = field("lsp.xml.preserveSpace");
        String value = String.join("; ", settings.getXmlFormatPreserveSpace());
        FxTestSupport.runOnFx(() -> {
            assertTrue(keep.getPrefWidth() > 220, "wider than the usual text field");
            assertEquals(value, keep.getText());
            assertNotNull(ControlNames.tipText(keep), "the whole list on hover");
            assertEquals(value, ControlNames.tipText(keep));
            keep.setText("pre");
            assertEquals(null, ControlNames.tipText(keep), "a short value needs none");
        });
    }

    @Test
    void theFormattingRowsDoNotRepeatTheirHeading() {
        for (String key : new String[] {"splitAttributes", "emptyElements", "spaceBeforeEmptyCloseTag"}) {
            assertFalse(tr("settings.lsp.xml." + key + ".desc").contains("XML server"), key);
        }
    }

    /**
     * XML/pom review 4, C36: the server-command field held a 111-character path cut off, with no tooltip and no
     * accessible name, and "Schema associations" stayed 220 px wide.
     */
    @Test
    void theServerCommandFieldShowsItsWholeValueAndHasAName() throws Exception {
        java.util.Map<String, TextField> commands = SettingsRig.control(window, "lspCommandFields");
        TextField xml = commands.get("xml");
        assertNotNull(xml);
        String longCommand = "/home/someone/.editora/plugins/lsp/xml/lemminx-linux-x86_64 -Dsome.option=value -Xmx1g";
        String was = FxTestSupport.callOnFx(xml::getText);
        FxTestSupport.runOnFx(() -> {
            assertNotNull(xml.getAccessibleText(), "named for a screen reader");
            xml.setText(longCommand);
            assertNotNull(ControlNames.tipText(xml), "the whole command on hover");
            assertEquals(longCommand, ControlNames.tipText(xml));
            xml.setText("");
            assertEquals(null, ControlNames.tipText(xml));
            xml.setText(was);
        });
        FxTestSupport.runOnFx(() -> assertTrue(field("lsp.xml.fileAssociations").getPrefWidth() > 220));
    }
}
