package com.editora.ui;

import java.util.Map;

import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import com.editora.config.Settings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Bash card on Settings → LSP writes bash-language-server's own settings (shell review C7). */
@Tag("fx")
class BashSettingsPageFxTest {

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

    @SuppressWarnings("unchecked")
    private static <T extends Control> T row(String key) {
        Map<String, Control> controls = FxTestSupport.field(window, "settingControls");
        T control = (T) controls.get(key);
        assertNotNull(control, "no Settings row for " + key);
        return control;
    }

    private static void commit(TextField field, String text) throws Exception {
        FxTestSupport.runOnFx(() -> {
            field.setText(text);
            field.getOnAction().handle(new javafx.event.ActionEvent());
        });
    }

    @Test
    void shellCheckAndShfmtCanBeConfigured() throws Exception {
        commit(row("lsp.bash.shellcheckArguments"), "-x --severity=warning");
        assertEquals("-x --severity=warning", settings.getBashShellcheckArguments());
        commit(row("lsp.bash.shellcheckPath"), "/opt/shellcheck");
        assertEquals("/opt/shellcheck", settings.getBashShellcheckPath());
        commit(row("lsp.bash.shfmtPath"), "/opt/shfmt");
        assertEquals("/opt/shfmt", settings.getBashShfmtPath());

        ComboBox<String> dialect = row("lsp.bash.shfmtDialect");
        FxTestSupport.runOnFx(() -> dialect.setValue("posix"));
        assertEquals("posix", settings.getBashShfmtDialect());

        CheckBox caseIndent = row("lsp.bash.shfmtCaseIndent");
        FxTestSupport.runOnFx(() -> caseIndent.setSelected(true));
        assertTrue(settings.isBashShfmtCaseIndent());
    }

    @Test
    void theAnalysisCapWorkspaceSymbolsAndExplainshellCanBeSet() throws Exception {
        Spinner<Integer> cap = row("lsp.bash.backgroundAnalysisMaxFiles");
        assertEquals(500, FxTestSupport.callOnFx(cap::getValue), "the server's own default is shown");
        FxTestSupport.runOnFx(() -> cap.getValueFactory().setValue(3000));
        assertEquals(3000, settings.getBashBackgroundAnalysisMaxFiles());

        CheckBox all = row("lsp.bash.includeAllWorkspaceSymbols");
        FxTestSupport.runOnFx(() -> all.setSelected(true));
        assertTrue(settings.isBashIncludeAllWorkspaceSymbols());

        commit(row("lsp.bash.explainshellEndpoint"), "http://localhost:5000");
        assertEquals("http://localhost:5000", settings.getBashExplainshellEndpoint());
    }

    @Test
    void theCardSaysWhetherShellCheckAndShfmtAreThereAndOffersToInstallThem(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        Map<String, javafx.scene.control.Label> status = FxTestSupport.field(window, "bashToolStatusLabels");
        Map<String, javafx.scene.control.Button> installs = FxTestSupport.field(window, "installButtons");

        commit(row("lsp.bash.shfmtPath"), dir.resolve("nowhere/shfmt").toString());
        assertEquals(
                com.editora.i18n.Messages.tr("settings.lsp.bash.shfmtMissing"),
                FxTestSupport.callOnFx(() -> status.get("shfmt").getText()));
        assertEquals(
                false, FxTestSupport.callOnFx(() -> installs.get("tool:shfmt").isDisable()), "Install… offered");

        java.nio.file.Path shfmt = java.nio.file.Files.writeString(dir.resolve("shfmt"), "#!/bin/sh\n");
        assertTrue(shfmt.toFile().setExecutable(true));
        commit(row("lsp.bash.shfmtPath"), shfmt.toString());
        assertEquals(
                com.editora.i18n.Messages.tr("settings.lsp.bash.toolFound", "shfmt", shfmt.toString()),
                FxTestSupport.callOnFx(() -> status.get("shfmt").getText()));
        assertEquals(
                true, FxTestSupport.callOnFx(() -> installs.get("tool:shfmt").isDisable()));
        commit(row("lsp.bash.shfmtPath"), "");
    }
}
