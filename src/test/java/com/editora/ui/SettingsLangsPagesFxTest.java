package com.editora.ui;

import java.util.Map;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.Region;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Languages &amp; Tools and AI pages of Settings, as the user sees them. */
@Tag("fx")
class SettingsLangsPagesFxTest {

    private SettingsRig rig;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        rig = SettingsRig.create();
    }

    @AfterEach
    void tearDown() throws Exception {
        rig.close();
    }

    /** True when some note on {@code page} says to turn on {@code switchTitle} and is showing. */
    private static boolean showsDependsNote(Region page, String switchTitle) {
        String text = tr("settings.dependsOn", switchTitle);
        return SettingsRig.all(page, Label.class).stream().anyMatch(l -> text.equals(l.getText()) && l.isVisible());
    }

    /**
     * Everything under an off master switch greys out, and the page says which switch turns it back on — it
     * used to be only the top enable box (AI), or nothing at all (LSP commands and Install buttons, Mermaid).
     */
    @Test
    void settingsUnderAnOffMasterSwitchAreGreyedWithANote() throws Exception {
        FxTestSupport.runOnFx(() -> {
            rig.settings.setAiEnabled(false);
            rig.settings.setLspSupport(false);
            rig.settings.setMermaidSupport(false);
            rig.settings.setHttpClientSupport(false);
            rig.show();
            CheckBox ai = rig.control("aiMasterCheck");
            Map<String, TextField> agentCommands = rig.control("agentCommandFields");
            ComboBox<String> provider = rig.control("aiProviderCombo");
            TextField endpoint = rig.control("aiEndpointField");
            Region agentPage = rig.page("AGENT");
            assertTrue(agentCommands.get("claude").isDisabled(), "agent command");
            assertTrue(provider.isDisabled() && endpoint.isDisabled(), "AI Actions rows");
            assertTrue(showsDependsNote(agentPage, ai.getText()));

            CheckBox lsp = rig.control("lspCheck");
            Map<String, TextField> lspCommands = rig.control("lspCommandFields");
            Map<String, javafx.scene.control.Button> installs = rig.control("installButtons");
            Region lspPage = rig.page("LSP");
            assertTrue(lspCommands.get("rust").isDisabled(), "server command");
            assertTrue(installs.get("server:rust").isDisabled(), "Install button");
            assertTrue(showsDependsNote(lspPage, lsp.getText()));

            TextField mmdc = rig.control("mmdcPathField");
            assertTrue(mmdc.isDisabled(), "mmdc path");
            CheckBox scripts = rig.control("httpScriptsCheck");
            assertTrue(scripts.isDisabled(), "HTTP scripts follow the HTTP client");

            ai.setSelected(true);
            lsp.setSelected(true);
            ((CheckBox) rig.control("mermaidCheck")).setSelected(true);
            assertFalse(agentCommands.get("claude").isDisabled());
            assertFalse(provider.isDisabled() || endpoint.isDisabled());
            assertFalse(lspCommands.get("rust").isDisabled());
            assertFalse(mmdc.isDisabled());
            assertFalse(showsDependsNote(agentPage, ai.getText()), "the note goes once the switch is on");
        });
    }
}
