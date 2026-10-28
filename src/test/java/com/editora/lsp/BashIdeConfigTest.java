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
        assertEquals(List.of(), section.get("shellcheckArguments"));
        assertEquals("", section.get("explainshellEndpoint"));
        assertEquals(true, section.get("enableSourceErrorDiagnostics"), "a hint on source lines it cannot follow (B4)");
        assertEquals(BashIdeConfig.DEFAULT_GLOB, section.get("globPattern"));
        assertEquals("warning", section.get("logLevel"));
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
        assertEquals(List.of("-x", "--severity=warning"), section.get("shellcheckArguments"));
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

    /** "Lint with ShellCheck" / "Format with shfmt" off: the server's own way, an empty path (A8, D7, B15). */
    @Test
    void switchedOffToolsAreSentAnEmptyPathAndAProjectMayTurnThemOff() {
        Settings s = new Settings();
        s.setBashShellcheckEnabled(false);
        s.setBashShfmtEnabled(false);
        BashIdeConfig off = BashIdeConfig.of(s).withInstalledTools("/cfg/sc", "/cfg/shfmt");
        assertEquals("", off.toSection().get("shellcheckPath"));
        assertEquals("", shfmt(off.toSection()).get("path"));
        assertEquals("", off.effectiveShellcheck(), "off reads as no command, wherever it is asked");
        assertEquals("", off.effectiveShfmt());
        assertEquals(false, off.toolEnabled("shellcheck"));

        BashIdeConfig user = BashIdeConfig.of(new Settings());
        BashIdeConfig project = user.withProject(Map.of("shellcheckPath", "", "shfmt", Map.of("path", " ")), false);
        assertEquals("", project.toSection().get("shellcheckPath"), "turning a tool off needs no trust");
        assertEquals("", shfmt(project.toSection()).get("path"));
        assertEquals(List.of(), BashIdeConfig.trustRequests(Map.of("shellcheckPath", "")));
        BashIdeConfig back = BashIdeConfig.of(s).withProject(Map.of("shellcheckPath", "/opt/sc"), true);
        assertEquals("/opt/sc", back.toSection().get("shellcheckPath"), "a trusted path turns it on again");
    }

    /** A project's further keys reach the server when of the type it accepts; unknown ones only when trusted. */
    @Test
    void aProjectsOtherServerKeysPassThroughValidated() {
        Map<String, Object> project = Map.of(
                "globPattern",
                "**/*@(.sh|.bash)",
                "logLevel",
                "Info",
                "shellcheckExternalSources",
                false,
                "backgroundAnalysisIgnore",
                List.of("**/vendor/**"),
                "enableSourceErrorDiagnostics",
                false,
                "futureKey",
                Map.of("a", 1),
                "shfmt",
                Map.of("additionalArguments", "--minify -ln=bash", "newShfmtKey", true));
        Map<String, Object> untrusted =
                BashIdeConfig.defaults().withProject(project, false).toSection();
        assertEquals("**/*@(.sh|.bash)", untrusted.get("globPattern"));
        assertEquals("info", untrusted.get("logLevel"));
        assertEquals(false, untrusted.get("shellcheckExternalSources"));
        assertEquals(List.of("**/vendor/**"), untrusted.get("backgroundAnalysisIgnore"));
        assertEquals(false, untrusted.get("enableSourceErrorDiagnostics"));
        assertEquals(List.of("--minify", "-ln=bash"), shfmt(untrusted).get("additionalArguments"));
        assertNull(untrusted.get("futureKey"), "a key Editora does not know could name a program");
        assertNull(shfmt(untrusted).get("newShfmtKey"));
        assertEquals(List.of("bash: futureKey = {a=1}"), BashIdeConfig.trustRequests(project));

        Map<String, Object> trusted =
                BashIdeConfig.defaults().withProject(project, true).toSection();
        assertEquals(Map.of("a", 1), trusted.get("futureKey"));
        assertEquals(true, shfmt(trusted).get("newShfmtKey"));

        Map<String, Object> bad = BashIdeConfig.defaults()
                .withProject(
                        Map.of(
                                "logLevel",
                                "loud",
                                "globPattern",
                                3,
                                "shellcheckExternalSources",
                                "no",
                                "backgroundAnalysisIgnore",
                                List.of(1)),
                        true)
                .toSection();
        assertEquals("warning", bad.get("logLevel"), "one bad value would make the server drop the whole section");
        assertEquals(BashIdeConfig.DEFAULT_GLOB, bad.get("globPattern"));
        assertNull(bad.get("shellcheckExternalSources"));
        assertNull(bad.get("backgroundAnalysisIgnore"));
    }

    /** ShellCheck arguments go as a list: the server split a string on single spaces, quotes and all (A8). */
    @Test
    void shellcheckArgumentsAreSplitTheWayAShellWould() {
        assertEquals(
                List.of("--rcfile", "/a b/rc", "-e", "SC2086", "x\"y"),
                BashIdeConfig.tokenize(" --rcfile '/a b/rc'  -e SC2086 \"x\\\"y\" "));
        assertEquals(List.of(), BashIdeConfig.tokenize("   "));
        assertEquals(List.of("a b", "c"), BashIdeConfig.tokenize("a\\ b c"));
        BashIdeConfig fromList = BashIdeConfig.defaults()
                .withProject(Map.of("shellcheckArguments", List.of("--rcfile", "/a b/rc", "-o", "all")), false);
        assertEquals(
                List.of("--rcfile", "/a b/rc", "-o", "all"),
                fromList.toSection().get("shellcheckArguments"));
    }

    /** Hover sends explainshell the whole command line: plain http only to this machine (B10). */
    @Test
    void explainshellOverPlainHttpOnlyToThisMachine() {
        assertTrue(BashIdeConfig.explainshellAllowed(""));
        assertTrue(BashIdeConfig.explainshellAllowed("https://explain.example.com"));
        assertTrue(BashIdeConfig.explainshellAllowed("http://localhost:5000"));
        assertTrue(BashIdeConfig.explainshellAllowed("http://127.0.0.1:5000"));
        assertTrue(BashIdeConfig.explainshellAllowed("http://[::1]:5000"));
        assertTrue(BashIdeConfig.explainshellAllowed("http://explain.localhost"));
        assertEquals(false, BashIdeConfig.explainshellAllowed("http://explain.example.com"));
        assertEquals(false, BashIdeConfig.explainshellAllowed("http://localhost.example.com"));
        assertEquals(false, BashIdeConfig.explainshellAllowed("localhost:5000"));
        assertEquals(false, BashIdeConfig.explainshellAllowed("ftp://localhost"));
        Settings s = new Settings();
        s.setBashExplainshellEndpoint("http://explain.example.com");
        assertEquals("", BashIdeConfig.of(s).toSection().get("explainshellEndpoint"), "a refused endpoint is not sent");
    }

    /** A project's bashIde reaches only the servers for its own folders, not another repository's (A1). */
    @Test
    void theProjectsConfigurationIsForItsOwnRootsOnly() {
        BashIdeConfig user = BashIdeConfig.defaults();
        BashIdeConfig project = user.withProject(Map.of("shellcheckArguments", "--exclude=SC2086"), false);
        var forRoot = BashIdeConfig.perRoot(project, user, Path.of("/work/app"));
        assertEquals(project, forRoot.apply(Path.of("/work/app")));
        assertEquals(project, forRoot.apply(Path.of("/work/app/modules/a")));
        assertEquals(project, forRoot.apply(Path.of("/work")), "a git root holding the project serves its scripts");
        assertEquals(user, forRoot.apply(Path.of("/work/other")));
        assertEquals(user, forRoot.apply(Path.of("/home/u/bin")));
        assertEquals(user, BashIdeConfig.perRoot(project, user, null).apply(Path.of("/work/app")));
    }

    /** The Settings card names the rows a project overrides (A10). */
    @Test
    void theKeysAProjectOverridesAreNamed() {
        Map<String, Object> project = Map.of(
                "shellcheckArguments", "-o all",
                "shellcheckPath", "./sc",
                "backgroundAnalysisMaxFiles", "lots",
                "shfmt", Map.of("caseIndent", true, "languageDialect", "fish"));
        List<String> untrusted = BashIdeConfig.overriddenKeys(project, false);
        assertTrue(untrusted.containsAll(List.of("shellcheckArguments", "shfmt.caseIndent")), untrusted.toString());
        assertEquals(2, untrusted.size(), "an untrusted path and values of the wrong kind change nothing");
        assertTrue(BashIdeConfig.overriddenKeys(project, true).contains("shellcheckPath"));
        assertEquals(List.of(), BashIdeConfig.overriddenKeys(Map.of(), true));
    }

    /**
     * A container or WSL folder is a local path whose server runs elsewhere: it is told to find ShellCheck and
     * shfmt on its own PATH, not given this machine's installed copies (A2).
     */
    @Test
    void aMappedFoldersServerLooksItsToolsUpThere(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path folder = java.nio.file.Files.createDirectories(dir.resolve("project"));
        com.editora.process.ExecTargets.setRemoteEnabled(() -> true);
        var target = com.editora.process.MappedTestTarget.register(folder, dir.resolve("alias"));
        try {
            var spec = new LspServerRegistry.ServerSpec("bash", List.of("bash-language-server"), List.of());
            LanguageServerSession session = new LanguageServerSession(spec, folder.toRealPath(), d -> {}, (t, m) -> {});
            session.setBashIde(BashIdeConfig.defaults().withInstalledTools("/cfg/tools/shellcheck", "/cfg/shfmt"));
            ConfigurationItem item = new ConfigurationItem();
            item.setSection("bashIde");
            @SuppressWarnings("unchecked")
            Map<String, Object> section =
                    (Map<String, Object>) session.configuration(new ConfigurationParams(List.of(item)))
                            .get()
                            .get(0);
            assertEquals("shellcheck", section.get("shellcheckPath"));
            assertEquals("shfmt", shfmt(section).get("path"));
        } finally {
            target.unregister();
            com.editora.process.ExecTargets.setRemoteEnabled(null);
        }
    }
}
