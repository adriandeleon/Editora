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
        assertEquals(java.util.List.of("--severity=warning"), section.get("shellcheckArguments"));
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

    /**
     * A project's {@code bashIde} reaches the servers for its own folders only: a script opened from another
     * repository is linted with the user's settings, not this project's ShellCheck excludes (Bash review A1).
     */
    @SuppressWarnings("unchecked")
    @Test
    void aProjectsBashIdeReachesOnlyItsOwnRepository() throws Exception {
        Path app = Files.createDirectories(root.resolve("app/.git")).getParent();
        Path other = Files.createDirectories(root.resolve("other/.git")).getParent();
        Files.createDirectories(app.resolve(".editora"));
        Files.writeString(
                app.resolve(".editora/settings.json"),
                "{\"bashIde\": {\"shellcheckArguments\": \"--exclude=SC2086,SC2045\"}}");
        // Switching the project re-runs server detection, which replaces setUp's forced "available" with a real
        // lookup; a command that exists everywhere (this JVM) keeps it found where no bash-language-server is.
        fx.host.settings.setBashLspCommand(
                ProcessHandle.current().info().command().orElseThrow());
        fx.ops.projectRoot = app;
        fx.open("app/main.sh", "#!/usr/bin/env bash\necho $1\n");
        FakeLanguageServer appServer = fx.server();
        fx.open("other/main.sh", "#!/usr/bin/env bash\necho $1\n");

        fx.run(() -> fx.coordinator.applyBashIde());

        assertEquals(
                app.toRealPath(), fx.manager.sessionRoot(app.resolve("main.sh")).toRealPath());
        fx.host.settings.setBashShfmtCaseIndent(true); // a user change: both servers are told again
        fx.run(() -> fx.coordinator.applyBashIde());
        assertEquals(java.util.List.of("--exclude=SC2086,SC2045"), pushedArguments(appServer));
        assertEquals(java.util.List.of(), pushedArguments(fx.server()), "not this project's excludes");
        assertEquals("--exclude=SC2086,SC2045", fx.manager.bashIdeFor(app).shellcheckArguments());
        assertEquals("", fx.manager.bashIdeFor(other).shellcheckArguments(), "the other repository gets the user's");
        assertEquals(
                java.util.List.of(), fx.manager.bashIdeFor(other).toSection().get("shellcheckArguments"));
    }

    @SuppressWarnings("unchecked")
    private static Object pushedArguments(FakeLanguageServer server) {
        var last = FakeLanguageServer.last(server.configurations);
        org.junit.jupiter.api.Assertions.assertNotNull(last, "the server was told its settings");
        Map<String, Object> settings = (Map<String, Object>) last.getSettings();
        return ((Map<String, Object>) settings.get("bashIde")).get("shellcheckArguments");
    }

    /**
     * Scripts outside any repository share one Bash server per window, which reads nothing in the background:
     * before, every folder of loose scripts started its own node process (Bash review A9).
     */
    @Test
    void looseScriptsShareOneServerThatSkipsBackgroundAnalysis() throws Exception {
        Path config = Files.createDirectories(root.resolve("config"));
        fx.ops.configManager = new com.editora.config.ConfigManager(config);
        fx.run(() -> fx.coordinator.applyBashIde()); // sets the loose root under the config folder
        Path loose = fx.manager.looseRoot();
        org.junit.jupiter.api.Assertions.assertNotNull(loose);
        Files.createDirectories(root.resolve("bin"));
        Files.createDirectories(root.resolve("etc"));
        fx.open("bin/a.sh", "#!/usr/bin/env bash\necho a\n");
        fx.open("etc/b.sh", "#!/usr/bin/env bash\necho b\n");

        assertEquals(loose, fx.manager.sessionRoot(root.resolve("bin/a.sh")));
        assertEquals(loose, fx.manager.sessionRoot(root.resolve("etc/b.sh")), "one server for both folders");
        assertEquals(0, fx.manager.bashIdeFor(loose).backgroundAnalysisMaxFiles());
    }

    /** The Bash card names the rows the project overrides, and an explainshell endpoint it will not use (A10, B10). */
    @Test
    void theSettingsCardSaysWhatTheProjectOverrides() throws Exception {
        com.editora.i18n.Messages.init("en");
        Files.createDirectories(root.resolve(".editora"));
        Files.writeString(
                root.resolve(".editora/settings.json"),
                "{\"bashIde\": {\"shellcheckArguments\": \"-o all\", \"shfmt\": {\"caseIndent\": true}}}");
        fx.ops.projectRoot = root;
        fx.host.settings.setBashExplainshellEndpoint("http://explain.example.com");

        String note = FxTestSupport.callOnFx(() -> SettingsBashRows.projectNote(fx.coordinator, "bash"));

        org.junit.jupiter.api.Assertions.assertNotNull(note);
        org.junit.jupiter.api.Assertions.assertTrue(note.contains("shellcheckArguments, shfmt.caseIndent"), note);
        org.junit.jupiter.api.Assertions.assertTrue(note.contains("http://explain.example.com"), note);
        org.junit.jupiter.api.Assertions.assertNull(
                FxTestSupport.callOnFx(() -> SettingsBashRows.projectNote(fx.coordinator, "yaml")));
    }
}
