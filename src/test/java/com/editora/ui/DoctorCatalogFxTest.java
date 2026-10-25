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
        ProjectScope scope = ProjectScope.NONE;

        @Override
        public ProjectScope projectScope() {
            return scope;
        }

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
        Path root;
        Set<String> configured = Set.of();
        final List<String> commandsRun = new java.util.ArrayList<>();

        @Override
        public Path projectRoot() {
            return root;
        }

        @Override
        public boolean lspServerCommandSet(String serverId) {
            return configured.contains(serverId);
        }

        @Override
        public void runCommand(String commandId) {
            commandsRun.add(commandId);
        }

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

        final Map<String, String> failures = new HashMap<>();

        @Override
        public String lspServerFailure(String serverId) {
            return failures.get(serverId);
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
        // No shell script is open, so the missing formatter is not a problem yet; Install… stays offered.
        DoctorCheck shfmt = spec(specs, "lsp.bash.shfmt").probe().get();
        assertEquals(DoctorStatus.OPTIONAL, shfmt.status());
        assertEquals("format", shfmt.sectionKey());
        assertEquals(DoctorCheck.Install.TOOL, shfmt.install());
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

    /** A file watcher the system refuses (Linux: the inotify instances used up) is a warning with the reason. */
    @Test
    void theFileWatchingRowSaysWhenTheSystemRefusesAWatcher() throws Exception {
        String limit = "User limit of inotify instances reached or too many open files";
        com.editora.io.WatchServices.Opener previous = com.editora.io.WatchServices.setOpenerForTest(() -> {
            throw new java.io.IOException(limit);
        });
        try {
            DoctorCheck row =
                    spec(specs(new Host(), new Ops()), "fileWatch").probe().get();
            assertEquals(com.editora.doctor.DoctorStatus.WARN, row.status());
            assertEquals(limit, row.detail());
            assertEquals("doctor.tip.fileWatchPolling", row.tipKey());
        } finally {
            com.editora.io.WatchServices.setOpenerForTest(previous);
        }
        com.editora.io.WatchServices.setOpenerForTest(() -> new com.editora.io.PollingWatchService());
        try {
            DoctorCheck row =
                    spec(specs(new Host(), new Ops()), "fileWatch").probe().get();
            assertEquals(com.editora.doctor.DoctorStatus.OK, row.status(), "a watcher the system gives is fine");
        } finally {
            com.editora.io.WatchServices.setOpenerForTest(previous);
        }
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
        // Without ripgrep the search still works, more slowly: not installed, not a warning.
        DoctorCheck rg = spec(specs, "ripgrep").probe().get();
        assertEquals(DoctorStatus.OPTIONAL, rg.status());
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

        // No command configured at all: says so (not "not found on PATH"), and nothing here needs it.
        DoctorCheck java = spec(specs, "lsp.java").placeholder();
        assertEquals("", java.command());
        assertEquals(DoctorCheck.Install.LANG, java.install());
        assertEquals(InstallCatalog.Lang.JAVA.name(), java.installArg());
        DoctorCheck javaResult = spec(specs, "lsp.java").probe().get();
        assertEquals(DoctorStatus.OPTIONAL, javaResult.status());
        assertEquals("doctor.tip.notConfigured", javaResult.tipKey());

        // Missing, but no TypeScript file is open and the project has no package.json: not a problem (review A1).
        DoctorCheck ts = spec(specs, "lsp.typescript").placeholder();
        assertEquals(InstallCatalog.Lang.JAVASCRIPT.name(), ts.installArg());
        DoctorCheck tsResult = spec(specs, "lsp.typescript").probe().get();
        assertEquals(DoctorStatus.OPTIONAL, tsResult.status());
        assertEquals("doctor.tip.serverOptional", tsResult.tipKey());

        // A project that calls for it makes the same absence a problem, named by its command.
        Files.writeString(Files.createDirectories(dir.resolve("proj")).resolve("package.json"), "{}");
        ops.root = dir.resolve("proj");
        DoctorCheck needed = spec(specs(host, ops), "lsp.typescript").probe().get();
        assertEquals(DoctorStatus.MISSING, needed.status());
        assertEquals("doctor.tip.missingInstallable", needed.tipKey());
        assertEquals(List.of(dir.resolve("no-such-ts-server").toString()), needed.tipArgs());
        ops.root = null;

        // So does a command the user set themselves.
        ops.configured = Set.of("markdown");
        DoctorCheck md = spec(specs(host, ops), "lsp.markdown").probe().get();
        assertEquals(DoctorStatus.MISSING, md.status());
        assertEquals("doctor.tip.missing", md.tipKey(), "no installer, so no \"Click Install…\"");
        ops.configured = Set.of();

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
        assertEquals("doctor.tip.missingInstallable", missing.tipKey());
        assertEquals(List.of("java-debug"), missing.tipArgs());

        // A jdtls that ships java-debug has no jar to find; with LSP off nothing else matters (review A7).
        ops.javaDebugFromServer = true;
        assertEquals(
                DoctorStatus.OK,
                spec(specs(host, ops), "debug.java").probe().get().status());
        ops.lsp = false;
        // The fix is turning LSP on, not an install (review A3): no probe, no Install…, a Turn on.
        DoctorService.CheckSpec needsLspSpec = spec(specs(host, ops), "debug.java");
        assertNull(needsLspSpec.probe());
        DoctorCheck needsLsp = needsLspSpec.placeholder();
        assertEquals(DoctorStatus.MISSING, needsLsp.status());
        assertEquals("doctor.tip.needsLsp", needsLsp.tipKey());
        assertEquals(DoctorCheck.Install.NONE, needsLsp.install());
        assertEquals("lsp", needsLsp.enableKey());
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
        assertEquals(List.of("17", "25"), oldResult.tipArgs(), "the version found, then the floor (VersionFloor)");

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

    /** A feature-off row's Turn on runs that feature's own toggle, and only while the feature is off (review C5). */
    @Test
    void turnOnRunsTheFeaturesToggleOnlyWhileItIsOff() throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        doctor.probeOverrideForTest = spec -> spec.placeholder().ok("");
        host.settings.setDebugSupport(false);
        host.settings.setLspSupport(true);
        FxTestSupport.runOnFx(() -> doctor.enableFeature("debug"));
        FxTestSupport.runOnFx(() -> doctor.enableFeature("lsp"));
        assertEquals(List.of("view.toggleDebug"), ops.commandsRun, "LSP is already on: never toggled off");

        host.settings.setHttpScriptsEnabled(false);
        FxTestSupport.runOnFx(() -> doctor.enableFeature("httpScripts"));
        assertTrue(host.settings.isHttpScriptsEnabled());
    }

    /** A trusted project's build command and debugger program are what the Doctor checks (F4). */
    @Test
    void buildAndDebugRowsCheckATrustedProjectsCommands(@TempDir Path dir) throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path file = com.editora.config.ProjectSettings.fileFor(project);
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                {"buildCommands": {"maven": "/opt/team/mvn"}, "debugTools": {"python": "/opt/team/python"}}
                """);
        com.editora.config.ConfigManager config =
                new com.editora.config.ConfigManager(Files.createDirectories(dir.resolve("config")));
        Host host = new Host();
        host.scope = new ProjectScope(() -> project, () -> config);
        Ops ops = new Ops();
        ops.root = project;
        ops.debug = true;

        assertFalse(
                spec(specs(host, ops), "build.maven").placeholder().command().contains("/opt/team/mvn"),
                "untrusted: the user's command");
        config.getTrustStore().trust(project);
        host.scope.reload();
        List<DoctorService.CheckSpec> trusted = specs(host, ops);
        assertTrue(spec(trusted, "build.maven").placeholder().command().contains("/opt/team/mvn"));
        assertTrue(spec(trusted, "debug.python").placeholder().command().contains("/opt/team/python"));
    }

    /**
     * Review E6: one Run → Node.js row names the Node.js every Node feature shares, with its version and why, and
     * the HTTP row runs that Node.js too while its own field is blank.
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS) // runs a sh script
    void oneNodeRowNamesTheSharedNodeAndHttpScriptsUseIt(@TempDir Path dir) throws Exception {
        Path node = executable(dir.resolve("node-24/bin/node"), "#!/bin/sh\necho v24.99.0\n");
        Host host = new Host();
        host.settings.setNodePath(node.toString());
        host.settings.setHttpClientSupport(true);
        host.settings.setHttpScriptsEnabled(true);
        host.settings.setHttpNodePath("");
        Ops ops = new Ops();
        ops.root = Files.createDirectories(dir.resolve("proj"));
        Files.writeString(ops.root.resolve("package.json"), "{}");

        List<DoctorService.CheckSpec> specs = specs(host, ops);
        DoctorService.CheckSpec nodeSpec = spec(specs, "run.node");
        assertEquals("debug", nodeSpec.placeholder().settingsKey(), "the Node.js field is on the Debugging page");
        DoctorCheck row = nodeSpec.probe().get();
        assertEquals(DoctorStatus.OK, row.status());
        assertEquals("v24.99.0 — " + node + " (Node.js setting)", row.detail());
        assertEquals(node.toString(), spec(specs, "http.scripts").placeholder().command(), "blank HTTP field");

        host.settings.setNodePath(dir.resolve("missing/node").toString());
        DoctorCheck missing = spec(specs(host, ops), "run.node").probe().get();
        assertEquals(DoctorStatus.MISSING, missing.status(), "a package.json project needs Node.js");
    }

    /**
     * The Maven pom.xml server runs as {@code java -cp "<dir>/*"}: {@code java} found is not "installed". A folder
     * lacking the jars the upstream bundle leaves out is "incomplete — Install… to repair"; a complete one is OK;
     * a server that starts but fails its requests (Maven review C1/C2/C3) is a warning that says so.
     */
    @Test
    void theMavenServerRowChecksItsFolderAndReportsAFailingServer(@TempDir Path dir) throws Exception {
        Host host = new Host();
        Ops ops = new Ops();
        Path javaExe = executable(dir.resolve("bin/java"), "#!/bin/sh\nexit 0\n");
        Path maven = Files.createDirectories(dir.resolve("maven"));
        Files.writeString(maven.resolve("org.eclipse.lemminx-0.30.1.jar"), "");
        Files.writeString(maven.resolve("lemminx-maven-0.12.0.jar"), "");
        ops.servers = List.of("maven-pom", "json");
        ops.argv.put(
                "maven-pom",
                List.of(
                        javaExe.toString(),
                        "-cp",
                        maven + java.io.File.separator + "*",
                        "org.eclipse.lemminx.XMLServerLauncher"));
        ops.argv.put("json", List.of(javaExe.toString(), "--stdio"));
        ops.failures.put("json", "Internal error. — java.lang.NoClassDefFoundError: x");

        DoctorCheck incomplete = spec(specs(host, ops), "lsp.maven-pom").probe().get();
        assertEquals(DoctorStatus.MISSING, incomplete.status());
        assertEquals("doctor.tip.mavenIncomplete", incomplete.tipKey());
        assertEquals(List.of("guava-*.jar, commons-lang3-*.jar, jsoup-*.jar"), incomplete.tipArgs());
        assertEquals(DoctorCheck.Install.SERVER, incomplete.install(), "Install… repairs it");

        for (var jar : InstallCatalog.LEMMINX_MAVEN_EXTRA_JARS) {
            Files.writeString(maven.resolve(jar.fileName()), "");
        }
        DoctorCheck complete = spec(specs(host, ops), "lsp.maven-pom").probe().get();
        assertEquals(DoctorStatus.OK, complete.status());

        DoctorCheck failing = spec(specs(host, ops), "lsp.json").probe().get();
        assertEquals(DoctorStatus.WARN, failing.status());
        assertEquals("doctor.tip.serverFailing", failing.tipKey());
        assertEquals("Internal error. — java.lang.NoClassDefFoundError: x", failing.detail());
    }
}
