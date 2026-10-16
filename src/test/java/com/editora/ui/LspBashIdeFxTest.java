package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A running bash-language-server hears about its settings (shell review C7): a change in Settings is pushed
 * as {@code workspace/didChangeConfiguration}, and a project's {@code .editora/settings.json} {@code bashIde}
 * object is laid over the user's.
 */
@Tag("fx")
class LspBashIdeFxTest {

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        FxTestSupport.runOnFx(() -> fx.coordinator.setServerAvailableForTest("bash", true));
        fx.open("deploy.sh", "#!/usr/bin/env bash\necho \"$1\"\n");
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> lastPushedSection() {
        FakeLanguageServer server = fx.server();
        Map<String, Object> settings = (Map<String, Object>)
                FakeLanguageServer.last(server.configurations).getSettings();
        return (Map<String, Object>) settings.get("bashIde");
    }

    @Test
    void aSettingsChangeIsPushedToTheRunningBashServer() throws Exception {
        fx.host.settings.setBashShellcheckArguments("--severity=warning");
        fx.host.settings.setBashBackgroundAnalysisMaxFiles(2000);

        fx.run(() -> fx.coordinator.applyBashIde());

        Map<String, Object> section = lastPushedSection();
        assertEquals("--severity=warning", section.get("shellcheckArguments"));
        assertEquals(2000, section.get("backgroundAnalysisMaxFiles"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aProjectsBashIdeObjectIsLaidOverTheUsersSettings() throws Exception {
        Files.createDirectories(root.resolve(".editora"));
        Files.writeString(
                root.resolve(".editora/settings.json"),
                "{\"bashIde\": {\"shfmt\": {\"caseIndent\": true}, \"shellcheckPath\": \"./evil\"}}");
        fx.ops.projectRoot = root;

        fx.run(() -> fx.coordinator.applyBashIde());

        Map<String, Object> section = lastPushedSection();
        assertEquals(true, ((Map<String, Object>) section.get("shfmt")).get("caseIndent"));
        assertEquals("shellcheck", section.get("shellcheckPath"), "an untrusted project picks no program");
    }

    /** The didOpen names the document's language the way LSP does: {@code shellscript}, not {@code shell} (C12). */
    @Test
    void aShellScriptOpensAsShellscript() {
        assertEquals(
                "shellscript",
                FakeLanguageServer.last(fx.server().opened).getTextDocument().getLanguageId());
    }
}
