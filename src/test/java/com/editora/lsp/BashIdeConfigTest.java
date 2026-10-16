package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bash-language-server's {@code bashIde} section: what Editora answers and pushes (shell review C7). Before,
 * every request for it was answered {@code null}, so none of its settings could be changed.
 */
class BashIdeConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> shfmt(Map<String, Object> section) {
        return (Map<String, Object>) section.get("shfmt");
    }

    @Test
    void untouchedSettingsSendTheServersOwnDefaults() {
        Map<String, Object> section = BashIdeConfig.of(new Settings()).toSection();
        assertEquals("shellcheck", section.get("shellcheckPath"));
        assertEquals("", section.get("shellcheckArguments"));
        assertEquals("", section.get("explainshellEndpoint"));
        assertEquals(500, section.get("backgroundAnalysisMaxFiles"));
        assertEquals(false, section.get("includeAllWorkspaceSymbols"));
        assertEquals("shfmt", shfmt(section).get("path"));
        assertEquals("auto", shfmt(section).get("languageDialect"));
        assertEquals(false, shfmt(section).get("caseIndent"));
    }

    @Test
    void theUsersSettingsReachTheSectionInTheServersKeyNames() {
        Settings s = new Settings();
        s.setBashShellcheckArguments("-x --severity=warning");
        s.setBashShellcheckPath("/opt/sc/shellcheck");
        s.setBashShfmtPath("/opt/shfmt");
        s.setBashShfmtDialect("posix");
        s.setBashShfmtCaseIndent(true);
        s.setBashShfmtSimplify(true);
        s.setBashShfmtBinaryNextLine(true);
        s.setBashShfmtFuncNextLine(true);
        s.setBashShfmtSpaceRedirects(true);
        s.setBashShfmtKeepPadding(true);
        s.setBashShfmtIgnoreEditorconfig(true);
        s.setBashBackgroundAnalysisMaxFiles(5000);
        s.setBashIncludeAllWorkspaceSymbols(true);
        s.setBashExplainshellEndpoint("http://localhost:5000");

        Map<String, Object> section = BashIdeConfig.of(s).toSection();

        assertEquals("/opt/sc/shellcheck", section.get("shellcheckPath"));
        assertEquals("-x --severity=warning", section.get("shellcheckArguments"));
        assertEquals("http://localhost:5000", section.get("explainshellEndpoint"));
        assertEquals(5000, section.get("backgroundAnalysisMaxFiles"));
        assertEquals(true, section.get("includeAllWorkspaceSymbols"));
        assertEquals(
                Map.of(
                        "path", "/opt/shfmt",
                        "languageDialect", "posix",
                        "simplifyCode", true,
                        "binaryNextLine", true,
                        "caseIndent", true,
                        "funcNextLine", true,
                        "spaceRedirects", true,
                        "keepPadding", true,
                        "ignoreEditorconfig", true),
                shfmt(section));
    }

    @Test
    void badValuesAreKeptInRange() {
        Settings s = new Settings();
        s.setBashShfmtDialect("fish");
        s.setBashBackgroundAnalysisMaxFiles(-3);
        assertEquals("auto", s.getBashShfmtDialect());
        s.setBashShfmtDialect(null);
        assertEquals("auto", s.getBashShfmtDialect());
        assertEquals(
                "auto",
                BashIdeConfig.of(s)
                        .withProject(java.util.Map.of("shfmt", java.util.Map.of()), true)
                        .shfmtDialect());
        assertEquals(0, s.getBashBackgroundAnalysisMaxFiles());
        s.setBashBackgroundAnalysisMaxFiles(Integer.MAX_VALUE);
        assertEquals(Settings.BASH_BACKGROUND_ANALYSIS_MAX, s.getBashBackgroundAnalysisMaxFiles());
    }

    @Test
    void aProjectOverridesStyleAlwaysButProgramsAndTheEndpointOnlyWhenTrusted() {
        Map<String, Object> project = Map.of(
                "shellcheckArguments", "-e SC2086",
                "shellcheckPath", "./tools/shellcheck",
                "explainshellEndpoint", "https://explain.example",
                "backgroundAnalysisMaxFiles", 0,
                "shfmt", Map.of("caseIndent", true, "path", "./tools/shfmt", "languageDialect", "bash"));
        BashIdeConfig user = BashIdeConfig.of(new Settings());

        BashIdeConfig untrusted = user.withProject(project, false);
        assertEquals("-e SC2086", untrusted.shellcheckArguments());
        assertEquals(0, untrusted.backgroundAnalysisMaxFiles());
        assertTrue(untrusted.shfmtCaseIndent());
        assertEquals("bash", untrusted.shfmtDialect());
        assertEquals("shellcheck", untrusted.effectiveShellcheck(), "an untrusted checkout picks no program");
        assertEquals("shfmt", untrusted.effectiveShfmt());
        assertEquals("", untrusted.explainshellEndpoint(), "nor where the code under the caret is sent");

        BashIdeConfig trusted = user.withProject(project, true);
        assertEquals("./tools/shellcheck", trusted.effectiveShellcheck());
        assertEquals("./tools/shfmt", trusted.effectiveShfmt());
        assertEquals("https://explain.example", trusted.explainshellEndpoint());

        assertEquals(
                List.of(
                        "bash: shellcheckPath = ./tools/shellcheck",
                        "bash: shfmt.path = ./tools/shfmt",
                        "bash: explainshellEndpoint = https://explain.example"),
                BashIdeConfig.trustRequests(project));
        assertEquals(List.of(), BashIdeConfig.trustRequests(Map.of("shellcheckArguments", "-x")));
    }

    @Test
    void installedToolsFillOnlyBlankPathsAndARemoteServerUsesItsHostsPath() {
        BashIdeConfig installed = BashIdeConfig.of(new Settings()).withInstalledTools("/cfg/sc", "/cfg/shfmt");
        assertEquals("/cfg/sc", installed.effectiveShellcheck());
        assertEquals("/cfg/shfmt", installed.effectiveShfmt());

        Settings s = new Settings();
        s.setBashShellcheckPath("/mine/shellcheck");
        assertEquals(
                "/mine/shellcheck",
                BashIdeConfig.of(s).withInstalledTools("/cfg/sc", null).effectiveShellcheck());

        BashIdeConfig remote = installed.forRemote();
        assertEquals("shellcheck", remote.effectiveShellcheck());
        assertEquals("shfmt", remote.effectiveShfmt());
        s.setBashShfmtPath("shfmt3");
        assertEquals("shfmt3", BashIdeConfig.of(s).forRemote().effectiveShfmt(), "a bare name is kept");
    }

    @Test
    void theBashServerIsAnsweredItsSectionAndPushedIt() {
        Settings s = new Settings();
        s.setBashShellcheckArguments("-x");
        BashIdeConfig config = BashIdeConfig.of(s);

        assertEquals(config.toSection(), LspServerSettings.answer("bash", "bashIde", config));
        assertEquals(BashIdeConfig.defaults().toSection(), LspServerSettings.answer("bash", "bashIde"));
        assertNull(LspServerSettings.answer("bash", "python"));
        assertNull(LspServerSettings.answer("yaml", "bashIde", config), "only the Bash server gets it");
        assertEquals(Map.of("bashIde", config.toSection()), LspServerSettings.push("bash", false, config));
    }

    @Test
    void aSessionAnswersTheConfigurationItWasGivenAndPushesAChange() throws Exception {
        var spec = new LspServerRegistry.ServerSpec("bash", List.of("bash-language-server"), List.of());
        LanguageServerSession session = new LanguageServerSession(spec, Path.of("/srv/project"), d -> {}, (t, m) -> {});
        FakeLanguageServer fake = new FakeLanguageServer();
        session.attachForTest(fake, new ServerCapabilities());
        Settings s = new Settings();
        s.setBashShfmtCaseIndent(true);

        session.setBashIde(BashIdeConfig.of(s));

        ConfigurationItem item = new ConfigurationItem();
        item.setSection("bashIde");
        List<Object> answer =
                session.configuration(new ConfigurationParams(List.of(item))).get();
        assertEquals(List.of(BashIdeConfig.of(s).toSection()), answer);
        assertEquals(
                Map.of("bashIde", BashIdeConfig.of(s).toSection()),
                FakeLanguageServer.last(fake.configurations).getSettings(),
                "the running server is told about the change");
        int pushes = fake.configurations.size();
        session.setBashIde(BashIdeConfig.of(s));
        assertEquals(pushes, fake.configurations.size(), "an unchanged configuration is not pushed again");
    }

    @SuppressWarnings("unchecked")
    private static int analysisCapFor(Path root) throws Exception {
        var spec = new LspServerRegistry.ServerSpec("bash", List.of("bash-language-server"), List.of());
        LanguageServerSession session = new LanguageServerSession(spec, root, d -> {}, (t, m) -> {});
        session.setBashIde(BashIdeConfig.of(new Settings()));
        ConfigurationItem item = new ConfigurationItem();
        item.setSection("bashIde");
        var section = (Map<String, Object>) session.configuration(new ConfigurationParams(List.of(item)))
                .get()
                .get(0);
        return (Integer) section.get("backgroundAnalysisMaxFiles");
    }

    /**
     * A loose script directly in the home folder, the temp folder or at a filesystem root gets that folder as its
     * workspace; the server is told not to walk it for scripts (shell review C8). A real project keeps the cap.
     */
    @Test
    void noBackgroundAnalysisWhenTheWorkspaceIsHomeTheTempFolderOrARoot(@org.junit.jupiter.api.io.TempDir Path project)
            throws Exception {
        assertEquals(0, analysisCapFor(Path.of(System.getProperty("user.home"))));
        assertEquals(0, analysisCapFor(Path.of(System.getProperty("java.io.tmpdir"))));
        assertEquals(0, analysisCapFor(project.getRoot()));
        assertEquals(500, analysisCapFor(project));
        assertTrue(RootResolver.isBroadRoot(Path.of(System.getProperty("user.home"))));
        assertEquals(false, RootResolver.isBroadRoot(project));
    }
}
