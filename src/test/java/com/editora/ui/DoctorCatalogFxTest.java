package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorService;
import com.editora.doctor.DoctorStatus;
import com.editora.install.InstallCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Doctor's catalogue across feature switches, and the probes whose answer depends only on what the
 * settings name: a language server or agent command that does or does not exist, a debug adapter jar, the
 * selected JDK. Where a probe runs a program, the program is a script written into the test's own folder.
 */
@Tag("fx")
class DoctorCatalogFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        boolean simple;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simple;
        }
    }

    private static class Ops implements DoctorCoordinator.Ops {
        final com.editora.mermaid.MermaidService mermaid = new com.editora.mermaid.MermaidService();
        final com.editora.diagram.DiagramService diagram = new com.editora.diagram.DiagramService();
        final com.editora.typst.TypstService typst = new com.editora.typst.TypstService();
        boolean git = true;
        boolean lsp = true;
        boolean javaDebugFromServer;

        @Override
        public boolean javaDebugFromServer() {
            return javaDebugFromServer;
        }

        boolean debug;
        List<String> servers = List.of();
        Set<String> serversOff = Set.of();
        final Map<String, List<String>> argv = new HashMap<>();
        boolean installedTypstCli;
        String openedSettings;

        @Override
        public com.editora.mermaid.MermaidService mermaidService() {
            return mermaid;
        }

        @Override
        public com.editora.diagram.DiagramService diagramService() {
            return diagram;
        }

        @Override
        public com.editora.typst.TypstService typstService() {
            return typst;
        }

        @Override
        public boolean gitFeatureEnabled() {
            return git;
        }

        @Override
        public boolean lspFeatureEnabled() {
            return lsp;
        }

        @Override
        public boolean debugFeatureEnabled() {
            return debug;
        }

        @Override
        public List<String> lspServerIds() {
            return servers;
        }

        @Override
        public boolean lspServerEnabled(String serverId) {
            return !serversOff.contains(serverId);
        }

        @Override
        public List<String> lspServerArgv(String serverId) {
            return argv.getOrDefault(serverId, List.of());
        }

        @Override
        public void installServer(String serverId, Consumer<Boolean> onDone) {
            onDone.accept(true);
        }

        @Override
        public void installLang(InstallCatalog.Lang lang, Consumer<Boolean> onDone) {
            onDone.accept(true);
        }

        @Override
        public void installTypstCli(Consumer<Boolean> onDone) {
            installedTypstCli = true;
            onDone.accept(false);
        }

        @Override
        public void openSettingsFor(String settingsKey) {
            openedSettings = settingsKey;
        }
    }

    /**
     * The Bash server lints with ShellCheck and formats with shfmt, and says nothing when they are missing:
     * the Doctor shows both, by the command the server is told to run, with an Install action (C2).
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // runs a sh script
    void shellCheckAndShfmtAreCheckedWhileTheBashServerIsOn(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Path shellcheck = executable(dir.resolve("bin/shellcheck"), "#!/bin/sh\necho 'version: 0.11.0'\n");
        String noShfmt = dir.resolve("none/shfmt").toString();
        Ops ops = new Ops() {
            @Override
            public String bashTool(String toolId) {
                return "shfmt".equals(toolId) ? noShfmt : shellcheck.toString();
            }
        };
        ops.servers = List.of("bash");

        List<DoctorService.CheckSpec> specs = specs(host, ops);

        DoctorCheck sc = spec(specs, "lsp.bash.shellcheck").placeholder();
        assertEquals(shellcheck.toString(), sc.command());
        assertEquals(DoctorCheck.Install.TOOL, sc.install());
        assertEquals("shellcheck", sc.installArg());
        assertEquals(
                DoctorStatus.OK,
                spec(specs, "lsp.bash.shellcheck").probe().get().status());
        DoctorCheck shfmt = spec(specs, "lsp.bash.shfmt").probe().get();
        assertEquals(DoctorStatus.MISSING, shfmt.status());
        assertEquals("doctor.tip.bashTool", shfmt.tipKey());
        assertEquals(List.of(noShfmt), shfmt.tipArgs());

        ops.serversOff = Set.of("bash");
        assertFalse(has(specs(host, ops), "lsp.bash.shellcheck"), "not while the Bash server is off");
    }

    /**
     * The Python rows (Python review B16, decision 4): the interpreter Pyright analyses with, by version and
     * reason, beside the Pyright row; and the Ruff/Black formatter, shown with the language server off too.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // runs a sh script
    void thePythonInterpreterAndFormatterAreChecked(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Path venv = Files.createDirectories(dir.resolve(".venv"));
        Files.writeString(venv.resolve("pyvenv.cfg"), "home = /usr\n");
        Path python = executable(venv.resolve("bin/python"), "#!/bin/sh\necho Python 3.99.1\n");
        executable(venv.resolve("bin/ruff"), "#!/bin/sh\necho ruff 0.99.0\n");
        var interp = new com.editora.run.PythonInterpreters.PythonInterpreter(
                List.of(python.toString()), com.editora.run.PythonInterpreters.Source.PROJECT_VENV, venv);
        Ops ops = new Ops() {
            @Override
            public java.util.function.Supplier<com.editora.run.PythonInterpreters.PythonInterpreter>
                    pythonInterpreter() {
                return () -> interp;
            }
        };
        ops.servers = List.of("python");

        List<DoctorService.CheckSpec> specs = specs(host, ops);

        // One interpreter row (in Run: Run, Debug, the tests and Pyright all use it), with version and reason.
        assertFalse(has(specs, "lsp.python.interpreter"), "no second interpreter row under Language Servers");
        DoctorCheck interpreter = spec(specs, "run.python").probe().get();
        assertEquals(DoctorStatus.OK, interpreter.status());
        assertEquals("Python 3.99.1 — " + python + " (.venv)", interpreter.detail());
        DoctorCheck formatter = spec(specs, "python.formatter").probe().get();
        assertEquals(DoctorStatus.OK, formatter.status());
        assertTrue(formatter.detail().equals("ruff 0.99.0 — " + venv.resolve("bin/ruff")), formatter.detail());

        ops.lsp = false;
        assertTrue(has(specs(host, ops), "python.formatter"), "Format Document on .py works without LSP");
    }

    /** A remote file's interpreter is named as its host names it, and not run on this machine for a version. */
    @Test
    void aRemoteInterpreterIsNamedNotProbed() throws Exception {
        Host host = new Host();
        var remote = new com.editora.run.PythonInterpreters.PythonInterpreter(
                List.of("/home/u/proj/.venv/bin/python"),
                com.editora.run.PythonInterpreters.Source.PROJECT_VENV,
                java.nio.file.Path.of("/home/u/proj/.venv"));
        Ops ops = new Ops() {
            @Override
            public java.util.function.Supplier<com.editora.run.PythonInterpreters.PythonInterpreter>
                    pythonInterpreter() {
                return () -> remote;
            }

            @Override
            public boolean pythonInterpreterElsewhere() {
                return true;
            }
        };
        DoctorCheck row = spec(specs(host, ops), "run.python").probe().get();
        assertEquals(DoctorStatus.OK, row.status());
        assertEquals("/home/u/proj/.venv/bin/python (.venv)", row.detail());
    }

    private static List<DoctorService.CheckSpec> specs(Host host, Ops ops) throws Exception {
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        return FxTestSupport.callOnFx(doctor::buildSpecs);
    }

    private static DoctorService.CheckSpec spec(List<DoctorService.CheckSpec> specs, String id) {
        return specs.stream()
                .filter(s -> s.placeholder().id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row " + id));
    }

    private static boolean has(List<DoctorService.CheckSpec> specs, String id) {
        return specs.stream().anyMatch(s -> s.placeholder().id().equals(id));
    }

    private static Path executable(Path file, String script) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, script);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    void versionControlAndSearchRowsAreGrayWhenTheirFeatureIsOff() throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        ops.git = false;
        host.settings.setRipgrepSearch(false);
        List<DoctorService.CheckSpec> off = specs(host, ops);
        for (String id : List.of("git", "github", "ripgrep")) {
            assertEquals(DoctorStatus.DISABLED, spec(off, id).placeholder().status(), id);
            assertNull(spec(off, id).probe(), id);
        }

        // Git on but the GitHub integration off: only that row is gray.
        ops.git = true;
        host.settings.setGithubSupport(false);
        host.settings.setRipgrepSearch(true);
        List<DoctorService.CheckSpec> mixed = specs(host, ops);
        assertEquals(DoctorStatus.CHECKING, spec(mixed, "git").placeholder().status());
        assertEquals(DoctorStatus.DISABLED, spec(mixed, "github").placeholder().status());
        assertEquals(DoctorStatus.CHECKING, spec(mixed, "ripgrep").placeholder().status());
        assertEquals("search", spec(mixed, "ripgrep").placeholder().settingsKey());
    }

    @Test
    void aMissingGitOrRipgrepIsNamedByItsConfiguredCommand(@TempDir Path dir) throws Exception {
        Host host = new Host();
        String noGit = dir.resolve("no-git").toString();
        String noRg = dir.resolve("no-rg").toString();
        host.settings.setGitPath(noGit);
        host.settings.setRipgrepCommand(noRg);
        host.settings.setRipgrepSearch(true);
        List<DoctorService.CheckSpec> specs = specs(host, new Ops());

        DoctorCheck git = spec(specs, "git").probe().get();
        assertEquals(DoctorStatus.MISSING, git.status());
        assertEquals(List.of(noGit), git.tipArgs());
        // Without ripgrep the search still works, more slowly: a warning, not an error.
        DoctorCheck rg = spec(specs, "ripgrep").probe().get();
        assertEquals(DoctorStatus.WARN, rg.status());
        assertEquals("doctor.tip.ripgrepOptional", rg.tipKey());
    }

    @Test
    void eachEnabledLanguageServerGetsARowThatResolvesItsCommand(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        Path jsonServer = executable(dir.resolve("bin/json-ls"), "#!/bin/sh\nexit 0\n");
        ops.servers = List.of("json", "java", "python", "typescript", "markdown");
        ops.serversOff = Set.of("python");
        ops.argv.put("json", List.of(jsonServer.toString(), "--stdio"));
        ops.argv.put("typescript", List.of(dir.resolve("no-such-ts-server").toString()));
        ops.argv.put("markdown", List.of(dir.resolve("no-such-md-server").toString()));
        List<DoctorService.CheckSpec> specs = specs(host, ops);

        assertFalse(has(specs, "lsp.python"), "a server switched off has no row");
        assertFalse(has(specs, "lsp"), "the gray summary row is for the whole feature being off");

        DoctorCheck json = spec(specs, "lsp.json").placeholder();
        assertEquals(jsonServer + " --stdio", json.command());
        assertEquals(DoctorCheck.Install.SERVER, json.install());
        assertEquals("json", json.installArg());
        DoctorCheck jsonResult = spec(specs, "lsp.json").probe().get();
        assertEquals(DoctorStatus.OK, jsonResult.status());
        assertEquals(jsonServer.toString(), jsonResult.detail());

        // No command configured at all: says so, and names the server in the tip.
        DoctorCheck java = spec(specs, "lsp.java").placeholder();
        assertEquals(tr("doctor.notConfigured"), java.command());
        assertEquals(DoctorCheck.Install.LANG, java.install());
        assertEquals(InstallCatalog.Lang.JAVA.name(), java.installArg());
        DoctorCheck javaResult = spec(specs, "lsp.java").probe().get();
        assertEquals(DoctorStatus.MISSING, javaResult.status());
        assertEquals(List.of(java.label()), javaResult.tipArgs());

        DoctorCheck ts = spec(specs, "lsp.typescript").placeholder();
        assertEquals(InstallCatalog.Lang.JAVASCRIPT.name(), ts.installArg());
        DoctorCheck tsResult = spec(specs, "lsp.typescript").probe().get();
        assertEquals(DoctorStatus.MISSING, tsResult.status());
        assertEquals(List.of(dir.resolve("no-such-ts-server").toString()), tsResult.tipArgs());

        // A server the editor cannot install itself offers no Install button.
        assertFalse(InstallCatalog.installableServerIds().contains("markdown"));
        assertEquals(
                DoctorCheck.Install.NONE,
                spec(specs, "lsp.markdown").placeholder().install());

        // With the feature off there is one gray row instead.
        ops.lsp = false;
        List<DoctorService.CheckSpec> offSpecs = specs(host, ops);
        assertEquals(DoctorStatus.DISABLED, spec(offSpecs, "lsp").placeholder().status());
        assertFalse(has(offSpecs, "lsp.json"));
    }

    @Test
    void theJavaDebugAdapterIsFoundWhereTheSettingPointsOrReportedMissing(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        ops.debug = true;
        host.settings.setPythonDebugEnabled(false);
        host.settings.setJsDebugEnabled(false);
        Path jar = Files.writeString(dir.resolve("my-debug-plugin.jar"), "not really a jar");
        host.settings.setJavaDebugPluginPath(jar.toString());
        List<DoctorService.CheckSpec> specs = specs(host, ops);

        DoctorCheck found = spec(specs, "debug.java").probe().get();
        assertEquals(DoctorStatus.OK, found.status());
        assertEquals(jar.toString(), found.detail());
        assertEquals(InstallCatalog.Lang.JAVA.name(), found.installArg());
        assertFalse(has(specs, "debug.python"));
        assertFalse(has(specs, "debug.javascript"));
        assertFalse(has(specs, "debug"), "no gray summary row while the feature is on");

        host.settings.setJavaDebugPluginPath(dir.resolve("gone.jar").toString());
        DoctorCheck missing = spec(specs(host, ops), "debug.java").probe().get();
        assertEquals(DoctorStatus.MISSING, missing.status());
        assertEquals(List.of("java-debug"), missing.tipArgs());

        // A jdtls that ships java-debug has no jar to find; with LSP off nothing else matters (review A7).
        ops.javaDebugFromServer = true;
        assertEquals(
                DoctorStatus.OK,
                spec(specs(host, ops), "debug.java").probe().get().status());
        ops.lsp = false;
        DoctorCheck needsLsp = spec(specs(host, ops), "debug.java").probe().get();
        assertEquals(DoctorStatus.MISSING, needsLsp.status());
        assertEquals("status.debug.javaNeedsLsp", needsLsp.tipKey());
        ops.lsp = true;
        ops.javaDebugFromServer = false;

        // The other adapters appear with their own installers once they are switched on.
        host.settings.setPythonDebugEnabled(true);
        host.settings.setJsDebugEnabled(true);
        List<DoctorService.CheckSpec> all = specs(host, ops);
        assertEquals(
                InstallCatalog.Lang.PYTHON.name(),
                spec(all, "debug.python").placeholder().installArg());
        assertEquals(
                InstallCatalog.Lang.JAVASCRIPT.name(),
                spec(all, "debug.javascript").placeholder().installArg());
        // A js-debug folder that does not exist: reported before node is even looked for.
        host.settings.setJsDebugPath(dir.resolve("no-js-debug").toString());
        DoctorCheck js = spec(specs(host, ops), "debug.javascript").probe().get();
        assertEquals(DoctorStatus.MISSING, js.status());
        assertEquals(List.of("js-debug"), js.tipArgs());
    }

    @Test
    void theSelectedJdkIsJudgedByTheVersionItReports(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Path current = dir.resolve("jdk-current");
        executable(current.resolve("bin/java"), "#!/bin/sh\necho 'openjdk version \"25.0.1\" 2026-01-20' >&2\n");
        host.settings.setMavenJdkHome(current.toString());
        DoctorService.CheckSpec ok = spec(specs(host, new Ops()), "run.java");
        assertEquals(current.resolve("bin/java").toString(), ok.placeholder().command());
        DoctorCheck okResult = ok.probe().get();
        assertEquals(DoctorStatus.OK, okResult.status());
        assertEquals("openjdk version \"25.0.1\" 2026-01-20", okResult.detail());

        Path old = dir.resolve("jdk-old");
        executable(old.resolve("bin/java"), "#!/bin/sh\necho 'openjdk version \"17.0.9\" 2023-10-17' >&2\n");
        host.settings.setMavenJdkHome(old.toString());
        DoctorCheck oldResult = spec(specs(host, new Ops()), "run.java").probe().get();
        assertEquals(DoctorStatus.WARN, oldResult.status());
        assertEquals("doctor.tip.javaOld", oldResult.tipKey());
        assertEquals(List.of("17"), oldResult.tipArgs());

        Path gone = dir.resolve("jdk-gone");
        host.settings.setMavenJdkHome(gone.toString());
        DoctorCheck missing = spec(specs(host, new Ops()), "run.java").probe().get();
        assertEquals(DoctorStatus.MISSING, missing.status());
        assertEquals(List.of(gone.resolve("bin/java").toString()), missing.tipArgs());

        // No JDK selected: whatever "java" is on PATH.
        host.settings.setMavenJdkHome("");
        assertEquals(
                "java", spec(specs(host, new Ops()), "run.java").placeholder().command());
    }

    @Test
    void theAgentRowFollowsTheAiSwitchesAndResolvesTheSelectedAgentsCommand(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        List<DoctorService.CheckSpec> off = specs(host, ops);
        assertEquals(DoctorStatus.DISABLED, spec(off, "ai").placeholder().status(), "AI is off by default");
        assertFalse(has(off, "agent"));

        host.settings.setAiSupport(true);
        host.settings.setAiEnabled(true);
        host.settings.setAgentSupport(false);
        List<DoctorService.CheckSpec> noAgent = specs(host, ops);
        assertFalse(has(noAgent, "ai"));
        assertFalse(has(noAgent, "agent"), "AI actions alone need no agent");

        host.settings.setAgentSupport(true);
        Path agent = executable(dir.resolve("my-agent"), "#!/bin/sh\nexit 0\n");
        host.settings.setAgentCommand(agent + " --acp");
        DoctorService.CheckSpec claude = spec(specs(host, ops), "agent");
        assertEquals("Claude Code", claude.placeholder().label(), "no agent chosen means the default one");
        assertEquals(agent + " --acp", claude.placeholder().command());
        DoctorCheck found = claude.probe().get();
        assertEquals(DoctorStatus.OK, found.status());
        assertEquals(agent.toString(), found.detail());

        host.settings.setAgentClient("gemini");
        String missingGemini = dir.resolve("no-gemini").toString();
        host.settings.setGeminiAgentCommand(missingGemini);
        DoctorService.CheckSpec gemini = spec(specs(host, ops), "agent");
        assertEquals("Gemini CLI", gemini.placeholder().label());
        DoctorCheck missing = gemini.probe().get();
        assertEquals(DoctorStatus.MISSING, missing.status());
        assertEquals(List.of(missingGemini), missing.tipArgs());

        // Simple mode hides AI and the build tools altogether.
        host.simple = true;
        List<DoctorService.CheckSpec> simple = specs(host, ops);
        assertEquals(DoctorStatus.DISABLED, spec(simple, "ai").placeholder().status());
        assertFalse(has(simple, "agent"));
        assertFalse(has(simple, "build.maven"));
        host.simple = false;
        assertTrue(has(specs(host, ops), "build.maven"));
    }

    @Test
    void theElevatedSaveRowAppearsOnlyWhereItIsSupportedAndSwitchedOn() throws Exception {
        Host host = new Host();
        String os = System.getProperty("os.name", "");
        host.settings.setAdminSave(false);
        assertFalse(has(specs(host, new Ops()), "adminSave"));

        host.settings.setAdminSave(true);
        List<DoctorService.CheckSpec> specs = specs(host, new Ops());
        assertEquals(com.editora.process.ElevatedSave.supportedOnOs(os), has(specs, "adminSave"));
        if (has(specs, "adminSave")) {
            DoctorCheck row = spec(specs, "adminSave").placeholder();
            assertEquals("editor", row.settingsKey());
            assertEquals(
                    com.editora.process.ElevatedSave.isMac(os)
                            ? com.editora.process.ElevatedSave.OSASCRIPT
                            : com.editora.process.ElevatedSave.PKEXEC,
                    row.command());
        }
    }

    @Test
    void thePanesOwnActionsRefreshInstallAndOpenSettings() throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        int[] probes = {0};
        doctor.probeOverrideForTest = spec -> {
            probes[0]++;
            return spec.placeholder().ok("checked");
        };
        DoctorPane.Actions actions = FxTestSupport.field(doctor.pane(), "actions");

        FxTestSupport.runOnFx(actions::refresh);
        int afterRefresh = probes[0];
        assertTrue(afterRefresh > 0);
        assertTrue(FxTestSupport.callOnFx(() -> doctor.pane().currentChecks()).stream()
                .noneMatch(r -> r.status() == DoctorStatus.CHECKING));

        DoctorCheck typst = DoctorCheck.checking("typst", "preview", "Typst", "typst")
                .withInstall(DoctorCheck.Install.TYPST_CLI, "")
                .missing("doctor.tip.missing", "typst");
        FxTestSupport.runOnFx(() -> actions.install(typst));
        assertTrue(ops.installedTypstCli);
        assertEquals(2 * afterRefresh, probes[0], "the rows are probed again even when the install failed");

        // A row with nothing to install does nothing.
        DoctorCheck plain = DoctorCheck.checking("git", "vcs", "Git", "git").missing("doctor.tip.missing", "git");
        FxTestSupport.runOnFx(() -> actions.install(plain));
        assertEquals(2 * afterRefresh, probes[0]);

        FxTestSupport.runOnFx(() -> actions.openSettings("lsp"));
        assertEquals("lsp", ops.openedSettings);
    }

    @Test
    void theHttpHandlersRowFollowsItsSettingAndJudgesTheNodeVersion(@TempDir Path dir) throws Exception {
        Host host = new Host();
        host.settings.setHttpClientSupport(true);
        host.settings.setHttpScriptsEnabled(false);
        DoctorService.CheckSpec off = spec(specs(host, new Ops()), "http.scripts");
        assertEquals(DoctorStatus.DISABLED, off.placeholder().status());
        assertEquals("web", off.placeholder().settingsKey());

        host.settings.setHttpScriptsEnabled(true);
        String noNode = dir.resolve("no-node").toString();
        host.settings.setHttpNodePath(noNode);
        DoctorCheck missing =
                spec(specs(host, new Ops()), "http.scripts").probe().get();
        assertEquals(DoctorStatus.MISSING, missing.status());
        assertEquals("doctor.tip.httpNode", missing.tipKey());
        assertEquals(List.of(noNode), missing.tipArgs());

        if (!System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win")) {
            Path old = executable(dir.resolve("old/node"), "#!/bin/sh\necho v18.19.0\n");
            host.settings.setHttpNodePath(old.toString());
            DoctorCheck warn =
                    spec(specs(host, new Ops()), "http.scripts").probe().get();
            assertEquals(DoctorStatus.WARN, warn.status());
            assertEquals("doctor.tip.httpNodeOld", warn.tipKey());

            Path fresh = executable(dir.resolve("new/node"), "#!/bin/sh\necho v24.1.0\n");
            host.settings.setHttpNodePath(fresh.toString());
            DoctorCheck ok =
                    spec(specs(host, new Ops()), "http.scripts").probe().get();
            assertEquals(DoctorStatus.OK, ok.status());
            assertTrue(ok.detail().contains("v24.1.0"), ok.detail());
        }

        host.settings.setHttpClientSupport(false);
        assertFalse(has(specs(host, new Ops()), "http.scripts"), "no HTTP Client, no row");
    }
}
