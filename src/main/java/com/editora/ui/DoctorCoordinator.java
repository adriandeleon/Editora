package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import com.editora.agent.AcpAgentRegistry;
import com.editora.config.Settings;
import com.editora.dap.DapServerRegistry;
import com.editora.dap.DebugAdapterLocator;
import com.editora.diagram.DiagramKind;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorProbes;
import com.editora.doctor.DoctorRules;
import com.editora.doctor.DoctorService;
import com.editora.git.GitService;
import com.editora.github.GitHubService;
import com.editora.install.InstallCatalog;
import com.editora.process.ElevatedSave;
import com.editora.run.RunService;
import com.editora.search.Ripgrep;
import com.editora.web.Browsers;

import static com.editora.i18n.Messages.tr;

/**
 * The Doctor feature coordinator (the {@code CoordinatorHost} pattern): owns the {@link DoctorPane} + the
 * {@link DoctorService} probe engine and builds the check catalog from live state — feature toggles +
 * configured commands are read on the FX thread at {@link #buildSpecs()} time, and each probe lambda closes
 * over the resolved immutable argv, so the off-thread probes never touch {@code Settings}. A disabled
 * feature contributes a gray terminal row and spawns no subprocess.
 *
 * <p>Probes are <b>fresh</b> per run (unlike the per-feature services' cached detects — a doctor must
 * reflect the machine now), reusing the same command-resolution helpers those features use
 * ({@code Ripgrep.command}, {@code MermaidService.mmdcCommand}, {@code LspServerRegistry.commandFor} via
 * {@link Ops#lspServerArgv}, {@code DapServerRegistry.interpreterArgv}, …). Fix actions route back into the
 * existing flows: {@code InstallCoordinator} installs, {@code SettingsWindow} pages.
 */
final class DoctorCoordinator {

    /** Window reach beyond {@link CoordinatorHost}: per-feature services, LSP registry reads, fixes. */
    interface Ops {
        com.editora.mermaid.MermaidService mermaidService();

        com.editora.diagram.DiagramService diagramService();

        com.editora.typst.TypstService typstService();

        /** The effective Git feature state ({@code gitEnabled()}: setting + Simple-mode folded). */
        boolean gitFeatureEnabled();

        /** The effective LSP feature state ({@code lspEnabled()}). */
        boolean lspFeatureEnabled();

        /** The effective Debug feature state ({@code debugSupportEnabled()}). */
        boolean debugFeatureEnabled();

        /** Known LSP server ids, in Settings-page order. */
        List<String> lspServerIds();

        /** Whether a server's own per-server toggle is on. */
        boolean lspServerEnabled(String serverId);

        /** The server's tokenized launch argv (configured command, blank ⇒ default; empty = unconfigured). */
        List<String> lspServerArgv(String serverId);

        void installServer(String serverId, Consumer<Boolean> onDone);

        void installLang(InstallCatalog.Lang lang, Consumer<Boolean> onDone);

        void installTypstCli(Consumer<Boolean> onDone);

        /** Installs a pinned command-line tool ({@code shellcheck}/{@code shfmt}). */
        default void installTool(String toolId, Consumer<Boolean> onDone) {
            onDone.accept(false);
        }

        /**
         * The command bash-language-server is told to run for {@code toolId} ({@code shellcheck}/{@code shfmt}):
         * the configured path, else Editora's installed copy, else the bare name.
         */
        default String bashTool(String toolId) {
            return toolId;
        }

        /** Opens Settings focused on the page for a Doctor settings key ({@code git}/{@code lsp}/…). */
        void openSettingsFor(String settingsKey);

        /** This window's project root (where Python's venv is looked for), or null. */
        default java.nio.file.Path projectRoot() {
            return null;
        }

        /** The Python interpreter picked for this window's project ("" = detect it). */
        default String pythonInterpreterChoice() {
            return "";
        }

        /**
         * Resolves the window's Python interpreter — what Pyright is told and the formatter is looked for beside
         * — or null when the window cannot say. Called on the FX thread; the returned supplier runs off it.
         */
        default java.util.function.Supplier<com.editora.run.PythonInterpreters.PythonInterpreter> pythonInterpreter() {
            return null;
        }
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final DoctorService service = new DoctorService();
    private final DoctorPane pane;

    /** Test seam: when set, probes resolve synchronously through this — no subprocess ever spawns. */
    Function<DoctorService.CheckSpec, DoctorCheck> probeOverrideForTest;

    DoctorCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.pane = new DoctorPane(new DoctorPane.Actions() {
            @Override
            public void refresh() {
                runChecks();
            }

            @Override
            public void install(DoctorCheck check) {
                DoctorCoordinator.this.install(check);
            }

            @Override
            public void openSettings(String settingsKey) {
                ops.openSettingsFor(settingsKey);
            }
        });
    }

    DoctorPane pane() {
        return pane;
    }

    /** Builds the catalog for the current state and (re)runs every probe; rows fill in as results land. */
    void runChecks() {
        List<DoctorService.CheckSpec> specs = buildSpecs();
        pane.setChecks(specs.stream().map(DoctorService.CheckSpec::placeholder).toList());
        pane.setFontScale(host.settings().getFontZoom());
        if (probeOverrideForTest != null) {
            for (DoctorService.CheckSpec spec : specs) {
                if (spec.probe() != null) {
                    pane.updateCheck(probeOverrideForTest.apply(spec));
                }
            }
            return;
        }
        service.run(specs, pane::updateCheck, () -> {});
    }

    /** A row for one of bash-language-server's tools: its version when it runs, else Install…. */
    private DoctorService.CheckSpec bashToolRow(String toolId) {
        String command = ops.bashTool(toolId);
        String label = "shellcheck".equals(toolId) ? "ShellCheck" : toolId;
        DoctorCheck row = DoctorCheck.checking("lsp.bash." + toolId, "lsp", label, command)
                .withSettings("lsp")
                .withInstall(DoctorCheck.Install.TOOL, toolId);
        List<String> argv = List.of(command);
        return probe(row, base -> {
            DoctorProbes.Presence p = DoctorProbes.version(argv);
            return p.present() ? base.ok(p.version()) : base.missing("doctor.tip.bashTool", command);
        });
    }

    /** Which Python the Python language server analyses with, and why (review B16). */
    private DoctorService.CheckSpec pythonInterpreterRow() {
        var resolver = ops.pythonInterpreter();
        DoctorCheck row = DoctorCheck.checking(
                        "lsp.python.interpreter", "lsp", tr("doctor.label.pythonInterpreter"), "")
                .withSettings("debug");
        if (resolver == null) {
            return terminal(row.disabled());
        }
        return probe(row, base -> {
            var interp = resolver.get();
            List<String> command = interp.command();
            DoctorProbes.Presence p = DoctorProbes.version(command);
            String what = String.join(" ", command) + " (" + PythonInterpreterView.reason(interp) + ")";
            return p.present()
                    ? base.ok(p.version() + " — " + what)
                    : base.missing("doctor.tip.pythonInterpreter", what);
        });
    }

    /** Ruff or Black for Format Document on Python files (adl's decision 4). */
    private DoctorService.CheckSpec pythonFormatterRow() {
        var resolver = ops.pythonInterpreter();
        DoctorCheck row =
                DoctorCheck.checking("python.formatter", "lsp", tr("doctor.label.pythonFormatter"), "ruff / black");
        return probe(row, base -> {
            Path venv = resolver == null ? null : resolver.get().venv();
            var found = com.editora.format.PythonFormatter.findLocal(venv);
            if (found.isEmpty()) {
                return base.warn("", "doctor.tip.pythonFormatter");
            }
            DoctorProbes.Presence p = DoctorProbes.version(List.of(found.get().executable()));
            String version = p.present() && !p.version().isBlank()
                    ? p.version()
                    : found.get().name();
            return base.ok(version + " — " + found.get().executable());
        });
    }

    private void install(DoctorCheck check) {
        Consumer<Boolean> done = ok -> runChecks(); // re-probe either way so the row reflects the outcome
        switch (check.install()) {
            case SERVER -> ops.installServer(check.installArg(), done);
            case LANG -> ops.installLang(InstallCatalog.Lang.valueOf(check.installArg()), done);
            case TYPST_CLI -> ops.installTypstCli(done);
            case TOOL -> ops.installTool(check.installArg(), done);
            case NONE -> {
                /* no action */
            }
        }
    }

    // --- the check catalog -------------------------------------------------------------------------

    /** The full check list for the current settings/OS. FX thread; probe lambdas run on the doctor pool. */
    List<DoctorService.CheckSpec> buildSpecs() {
        Settings s = host.settings();
        boolean simple = host.simpleModeActive();
        List<DoctorService.CheckSpec> specs = new ArrayList<>();

        // Version control -------------------------------------------------------------------------
        boolean gitOn = ops.gitFeatureEnabled();
        List<String> gitCmd = GitService.commandTokens(s.getGitPath());
        DoctorCheck git = DoctorCheck.checking("git", "vcs", "Git", String.join(" ", gitCmd))
                .withSettings("git");
        if (gitOn) {
            specs.add(probe(git, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(gitCmd);
                return p.present() ? base.ok(p.version()) : base.missing("doctor.tip.missing", gitCmd.get(0));
            }));
        } else {
            specs.add(terminal(git.disabled()));
        }
        boolean ghOn = gitOn && s.isGithubSupport();
        List<String> ghCmd = GitHubService.commandTokens(s.getGhPath());
        DoctorCheck gh = DoctorCheck.checking("github", "vcs", "GitHub CLI", String.join(" ", ghCmd))
                .withSettings("github");
        if (ghOn) {
            specs.add(probe(gh, base -> ghCheck(base, ghCmd.get(0), GitHubService.probe(ghCmd, GH_PROBE_TIMEOUT))));
        } else {
            specs.add(terminal(gh.disabled()));
        }

        // Search ----------------------------------------------------------------------------------
        List<String> rgCmd = Ripgrep.command(s.getRipgrepCommand());
        DoctorCheck rg = DoctorCheck.checking("ripgrep", "search", "ripgrep", String.join(" ", rgCmd))
                .withSettings("search");
        if (s.isRipgrepSearch()) {
            specs.add(probe(rg, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(rgCmd);
                return p.present() ? base.ok(p.version()) : base.warn("", "doctor.tip.ripgrepOptional");
            }));
        } else {
            specs.add(terminal(rg.disabled()));
        }

        // Preview & diagrams ----------------------------------------------------------------------
        List<String> mmdcCmd = ops.mermaidService().mmdcCommand();
        List<String> maidCmd = ops.mermaidService().maidCommand();
        DoctorCheck mmdc = DoctorCheck.checking("mmdc", "preview", "Mermaid CLI", String.join(" ", mmdcCmd))
                .withSettings("mermaid")
                .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.MERMAID.name());
        DoctorCheck maid = DoctorCheck.checking("maid", "preview", "Mermaid linter", String.join(" ", maidCmd))
                .withSettings("mermaid");
        if (s.isMermaidSupport()) {
            specs.add(versionedTool(mmdc, mmdcCmd, "mmdc"));
            // The linter is optional: without it diagrams still render, only live linting is off.
            specs.add(optionalVersionedTool(maid, maidCmd, "doctor.tip.maidOptional"));
        } else {
            specs.add(terminal(mmdc.disabled()));
        }
        List<String> dotCmd = ops.diagramService().command(DiagramKind.DOT);
        List<String> pumlCmd = ops.diagramService().command(DiagramKind.PLANTUML);
        DoctorCheck dot = DoctorCheck.checking("dot", "preview", "Graphviz", String.join(" ", dotCmd))
                .withSettings("diagrams");
        DoctorCheck puml = DoctorCheck.checking("plantuml", "preview", "PlantUML", String.join(" ", pumlCmd))
                .withSettings("diagrams");
        if (s.isDiagramSupport()) {
            specs.add(versionedTool(dot, dotCmd, "dot"));
            specs.add(versionedTool(puml, pumlCmd, "plantuml"));
        } else {
            specs.add(terminal(dot.disabled()));
        }
        List<String> typstCmd = ops.typstService().command();
        DoctorCheck typst = DoctorCheck.checking("typst", "preview", "Typst", String.join(" ", typstCmd))
                .withSettings("typst")
                .withInstall(DoctorCheck.Install.TYPST_CLI, "");
        if (s.isTypstSupport()) {
            specs.add(versionedTool(typst, typstCmd, "typst"));
        } else {
            specs.add(terminal(typst.disabled()));
        }

        // Language servers ------------------------------------------------------------------------
        if (ops.lspFeatureEnabled()) {
            for (String serverId : ops.lspServerIds()) {
                if (!ops.lspServerEnabled(serverId)) {
                    continue;
                }
                List<String> argv = ops.lspServerArgv(serverId);
                String display = argv.isEmpty() ? tr("doctor.notConfigured") : String.join(" ", argv);
                DoctorCheck row = DoctorCheck.checking("lsp." + serverId, "lsp", serverLabel(serverId), display)
                        .withSettings("lsp");
                DoctorCheck.Install install = installKindFor(serverId);
                if (install != DoctorCheck.Install.NONE) {
                    row = row.withInstall(install, installArgFor(serverId));
                }
                boolean python = "python".equals(serverId);
                specs.add(probe(row, base -> {
                    String path = DoctorProbes.resolvedPath(argv);
                    if (path.isEmpty()) {
                        return base.missing("doctor.tip.missing", argv.isEmpty() ? base.label() : argv.get(0));
                    }
                    // Pyright's launcher prints no version; its npm package.json beside it says (review B16).
                    String version = python ? com.editora.doctor.DoctorRules.npmPackageVersion(Path.of(path)) : "";
                    return base.ok(version.isEmpty() ? path : version + " — " + path);
                }));
                if (python) {
                    specs.add(pythonInterpreterRow());
                }
                if ("bash".equals(serverId)) {
                    // What bash-language-server lints and formats with. Without them a shell script silently
                    // gets no diagnostics and Format Document changes nothing.
                    for (String toolId : InstallCatalog.PINNED_TOOL_IDS) {
                        specs.add(bashToolRow(toolId));
                    }
                }
            }
        } else {
            specs.add(terminal(DoctorCheck.checking("lsp", "lsp", tr("settings.cat.lsp"), "")
                    .withSettings("lsp")
                    .disabled()));
        }
        specs.add(pythonFormatterRow()); // Format Document on .py works with the language server off too

        // Debugging -------------------------------------------------------------------------------
        if (ops.debugFeatureEnabled()) {
            Path home = Path.of(System.getProperty("user.home", ""));
            String javaPluginPath = s.getJavaDebugPluginPath();
            DoctorCheck javaDebug = DoctorCheck.checking("debug.java", "debug", tr("doctor.label.javaDebug"), "")
                    .withSettings("debug")
                    .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.JAVA.name());
            specs.add(probe(
                    javaDebug,
                    base -> DebugAdapterLocator.locate(javaPluginPath, home)
                            .map(p -> base.ok(p.toString()))
                            .orElseGet(() -> base.missing("doctor.tip.missing", "java-debug"))));
            if (s.isPythonDebugEnabled()) {
                List<String> py = DapServerRegistry.interpreterArgv("python", s.getPythonDebugCommand());
                DoctorCheck debugpy = DoctorCheck.checking(
                                "debug.python", "debug", tr("doctor.label.debugpy"), String.join(" ", py))
                        .withSettings("debug")
                        .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.PYTHON.name());
                specs.add(probe(debugpy, base -> {
                    if (py.isEmpty()) {
                        return base.missing("doctor.tip.missing", "python3");
                    }
                    // The same test DapManager.probeDebugpy runs: the configured interpreter must import
                    // debugpy, with any located bundle on PYTHONPATH (finding the bundle alone isn't enough).
                    Path dir = DebugAdapterLocator.locateDebugpy("", home).orElse(null);
                    boolean importable = DoctorProbes.succeeds(
                            withArgs(py, "-c", "import debugpy"), DoctorProbes.pythonPathWith(dir));
                    boolean interpreterRuns =
                            importable || DoctorProbes.version(py).present();
                    String blocker = DoctorRules.debugpyBlocker(importable, interpreterRuns, py.get(0));
                    if (!blocker.isEmpty()) {
                        return base.missing("doctor.tip.missing", blocker);
                    }
                    return base.ok(dir != null ? dir.toString() : "import debugpy");
                }));
            }
            if (s.isJsDebugEnabled()) {
                String jsPath = s.getJsDebugPath();
                List<String> node = DapServerRegistry.interpreterArgv("javascript", "");
                DoctorCheck jsDebug = DoctorCheck.checking("debug.javascript", "debug", tr("doctor.label.jsDebug"), "")
                        .withSettings("debug")
                        .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.JAVASCRIPT.name());
                specs.add(probe(jsDebug, base -> {
                    var server = DebugAdapterLocator.locateJsDebugServer(jsPath, home);
                    if (server.isEmpty()) {
                        return base.missing("doctor.tip.missing", "js-debug");
                    }
                    DoctorProbes.Presence nodeP = DoctorProbes.version(node);
                    return nodeP.present()
                            ? base.ok(server.get().toString())
                            : base.missing("doctor.tip.missing", "node");
                }));
            }
        } else {
            specs.add(terminal(DoctorCheck.checking("debug", "debug", tr("settings.cat.debug"), "")
                    .withSettings("debug")
                    .disabled()));
        }

        // The HTTP Client's JavaScript response handlers run on the user's Node.js.
        if (s.isHttpClientSupport() && !simple) {
            String nodeSetting = s.getHttpNodePath().isBlank() ? "node" : s.getHttpNodePath();
            DoctorCheck handlers = DoctorCheck.checking(
                            "http.scripts", "run", tr("doctor.label.httpScripts"), nodeSetting)
                    .withSettings("web");
            if (s.isHttpScriptsEnabled()) {
                specs.add(probe(handlers, base -> {
                    String exe = com.editora.process.ProcessRunner.resolveExecutable(List.of(nodeSetting))
                            .get(0);
                    int[] v = com.editora.http.NodeResponseScripts.version(exe);
                    if (v == null) {
                        return base.missing("doctor.tip.httpNode", nodeSetting);
                    }
                    String version = "v" + v[0] + "." + v[1] + "." + v[2];
                    return com.editora.http.HttpScripts.guardFor(v) == com.editora.http.HttpScripts.Guard.NONE
                            ? base.warn(version, "doctor.tip.httpNodeOld", version)
                            : base.ok(tr("doctor.detail.httpScripts", version));
                }));
            } else {
                specs.add(terminal(handlers.disabled()));
            }
        }

        // Standalone Java/Python/Make runs work without LSP; Bash Run still follows its LSP gate.
        String selectedJava = com.editora.run.JdkToolchain.javaExecutable(s.getMavenJdkHome());
        String javaExecutable = selectedJava.isBlank() ? "java" : selectedJava;
        // The JDK is the Build Tools page's Maven JDK, so that is where "Settings…" leads.
        DoctorCheck java =
                DoctorCheck.checking("run.java", "run", "Java", javaExecutable).withSettings("buildTools");
        specs.add(probe(java, base -> {
            String out = DoctorProbes.output(List.of(javaExecutable, "-version"));
            int major = RunService.javaMajorOf(out);
            return switch (DoctorRules.javaRunStatus(major)) {
                case OK -> base.ok(DoctorRules.firstLine(out, ""));
                case WARN -> base.warn(DoctorRules.firstLine(out, ""), "doctor.tip.javaOld", String.valueOf(major));
                default -> base.missing("doctor.tip.missing", javaExecutable);
            };
        }));
        specs.add(pythonRun(s));
        specs.add(optionalRunTool("run.make", "Make", List.of("make"), null, "Makefile"));
        // Shell Run no longer depends on LSP, so neither does the row that says whether it can work.
        specs.add(optionalRunTool("run.shell", "Bash", List.of("bash"), null, "shell"));

        // Build tools (informational; the tool windows self-gate on project markers) --------------
        if (!simple) {
            specs.add(buildTool("build.maven", "Maven", "mvn"));
            specs.add(buildTool("build.npm", "npm", "npm"));
            specs.add(buildTool("build.cargo", "Cargo", "cargo"));
            specs.add(buildTool("build.go", "Go", "go"));
            specs.add(buildTool("build.gradle", "Gradle", "gradle"));
        }

        // AI & agents -----------------------------------------------------------------------------
        boolean aiOn = s.isAiSupport() && s.isAiEnabled() && !simple;
        if (!aiOn) {
            specs.add(terminal(DoctorCheck.checking("ai", "ai", tr("settings.cat.aiGeneral"), "")
                    .withSettings("ai")
                    .disabled()));
        } else if (s.isAgentSupport()) {
            String agentId = s.getAgentClient().isBlank() ? "claude" : s.getAgentClient();
            List<String> agentCmd = AcpAgentRegistry.commandFor(agentId, agentOverrides(s));
            String agentName = AcpAgentRegistry.displayNameFor(agentId);
            DoctorCheck agent = DoctorCheck.checking(
                            "agent", "ai", agentName.isBlank() ? agentId : agentName, String.join(" ", agentCmd))
                    .withSettings("ai");
            specs.add(probe(agent, base -> {
                String path = DoctorProbes.resolvedPath(agentCmd);
                return path.isEmpty()
                        ? base.missing("doctor.tip.missing", agentCmd.isEmpty() ? base.label() : agentCmd.get(0))
                        : base.ok(path);
            }));
        }

        // System ----------------------------------------------------------------------------------
        String os = System.getProperty("os.name", "");
        if (s.isAdminSave() && ElevatedSave.supportedOnOs(os)) {
            DoctorCheck admin = DoctorCheck.checking(
                            "adminSave",
                            "system",
                            tr("doctor.label.adminSave"),
                            ElevatedSave.isMac(os) ? ElevatedSave.OSASCRIPT : ElevatedSave.PKEXEC)
                    .withSettings("editor");
            if (ElevatedSave.isMac(os)) {
                specs.add(terminal(admin.ok(""))); // osascript ships with macOS
            } else {
                specs.add(probe(admin, base -> {
                    DoctorProbes.Presence p = DoctorProbes.version(List.of(ElevatedSave.PKEXEC));
                    return p.present() ? base.ok(p.version()) : base.missing("doctor.tip.missing", ElevatedSave.PKEXEC);
                }));
            }
        }
        if (s.isHtmlPreviewSupport()) {
            DoctorCheck browsers = DoctorCheck.checking("browsers", "system", tr("doctor.label.browsers"), "")
                    .withSettings("web");
            specs.add(probe(
                    browsers,
                    base -> base.ok(Browsers.detect().stream()
                            .map(Browsers.Browser::displayName)
                            .reduce((a, b) -> a + " · " + b)
                            .orElse(""))));
        }
        DoctorCheck prereqs = DoctorCheck.checking("prereqs", "system", tr("doctor.label.prereqs"), "");
        specs.add(probe(prereqs, base -> {
            List<String> found = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            prereq(found, missing, "npm", List.of("npm"), null);
            prereq(found, missing, "python", List.of("python3"), List.of("python"));
            prereq(found, missing, "tar", List.of("tar"), null);
            prereq(found, missing, "node", List.of("node"), null);
            String detail = String.join(", ", found);
            return missing.isEmpty()
                    ? base.ok(detail)
                    : base.warn(detail, "doctor.tip.prereqs", String.join(", ", missing));
        }));

        return specs;
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** A standard "present + version, else missing" tool row. */
    private static DoctorService.CheckSpec versionedTool(DoctorCheck placeholder, List<String> cmd, String toolName) {
        return probe(placeholder, base -> {
            DoctorProbes.Presence p = DoctorProbes.version(cmd);
            return p.present() ? base.ok(p.version()) : base.missing("doctor.tip.missing", toolName);
        });
    }

    /** A tool whose absence only degrades its feature: present + version, else a WARN carrying {@code tipKey}. */
    private static DoctorService.CheckSpec optionalVersionedTool(
            DoctorCheck placeholder, List<String> cmd, String tipKey) {
        return probe(placeholder, base -> {
            DoctorProbes.Presence p = DoctorProbes.version(cmd);
            return p.present() ? base.ok(p.version()) : base.warn("", tipKey);
        });
    }

    /**
     * The Python that Run, Debug and the tests use for this window's project — the same resolver they use (its
     * venv, the picked or global interpreter, else {@code python3}/{@code python}, {@code py -3} on Windows) —
     * and its version, rather than whether a {@code python3} happens to be on PATH.
     */
    private DoctorService.CheckSpec pythonRun(Settings s) {
        java.nio.file.Path root = ops.projectRoot();
        String choice = ops.pythonInterpreterChoice();
        String setting = s.getPythonDebugCommand();
        DoctorCheck row = DoctorCheck.checking("run.python", "run", "Python", "python");
        return probe(row, base -> {
            com.editora.run.PythonInterpreters.PythonInterpreter py =
                    com.editora.run.PythonInterpreters.resolveLocal(null, root, "", choice, setting);
            List<String> argv = new ArrayList<>(com.editora.run.PythonLaunch.withoutEnvPrefix(py.command()));
            argv.add("--version");
            String version = DoctorRules.firstLine(DoctorProbes.output(argv).strip(), "");
            if (!version.startsWith("Python")) {
                return base.warn(String.join(" ", py.command()), "doctor.tip.runOptional", "Python");
            }
            return base.ok(version + " — " + String.join(" ", py.command()));
        });
    }

    /** A Run-section interpreter row: absent is only a WARN (needed just for that file type). */
    private static DoctorService.CheckSpec optionalRunTool(
            String id, String label, List<String> cmd, List<String> fallback, String fileKind) {
        DoctorCheck row = DoctorCheck.checking(id, "run", label, cmd.get(0));
        return probe(row, base -> {
            String path = DoctorProbes.resolvedPath(cmd);
            if (path.isEmpty() && fallback != null) {
                path = DoctorProbes.resolvedPath(fallback);
            }
            return path.isEmpty() ? base.warn("", "doctor.tip.runOptional", fileKind) : base.ok(path);
        });
    }

    /** A Build-tools row: PATH presence only (a project wrapper makes the global tool optional). */
    private static DoctorService.CheckSpec buildTool(String id, String label, String exe) {
        DoctorCheck row = DoctorCheck.checking(id, "build", label, exe).withSettings("buildTools");
        return probe(row, base -> {
            String path = DoctorProbes.resolvedPath(List.of(exe));
            return path.isEmpty() ? base.warn("", "doctor.tip.buildOptional", base.label()) : base.ok(path);
        });
    }

    private static void prereq(
            List<String> found, List<String> missing, String name, List<String> cmd, List<String> fallback) {
        boolean present = DoctorProbes.onPath(cmd) || (fallback != null && DoctorProbes.onPath(fallback));
        (present ? found : missing).add(name);
    }

    /** Display label for an LSP server id (the install catalog's names, e.g. {@code json} → "JSON"). */
    private static String serverLabel(String serverId) {
        return tr("install.lang." + serverId);
    }

    private static DoctorCheck.Install installKindFor(String serverId) {
        if (InstallCatalog.installableServerIds().contains(serverId)) {
            return DoctorCheck.Install.SERVER;
        }
        return switch (serverId) {
            case "java", "python", "typescript" -> DoctorCheck.Install.LANG;
            default -> DoctorCheck.Install.NONE;
        };
    }

    private static String installArgFor(String serverId) {
        return switch (serverId) {
            case "java" -> InstallCatalog.Lang.JAVA.name();
            case "python" -> InstallCatalog.Lang.PYTHON.name();
            case "typescript" -> InstallCatalog.Lang.JAVASCRIPT.name();
            default -> serverId;
        };
    }

    private static final java.time.Duration GH_PROBE_TIMEOUT = java.time.Duration.ofSeconds(20);

    /**
     * The gh row for a probe result ({@code null} = gh did not answer in time). The same probe the GitHub
     * integration uses, so the row cannot disagree with it: being offline is "could not check", not "not
     * signed in", and a gh too old for the CI-checks indicator is named as such. Pure.
     */
    static DoctorCheck ghCheck(DoctorCheck base, String executable, GitHubService.Availability a) {
        if (a == null || !a.found()) {
            return base.missing("doctor.tip.missing", executable);
        }
        return switch (a.auth()) {
            case SIGNED_OUT, REJECTED -> base.warn(a.version(), "doctor.tip.ghAuth");
            case UNVERIFIED -> base.warn(a.version(), "doctor.tip.ghUnverified");
            case SIGNED_IN ->
                a.supportsChecks()
                        ? base.ok(a.version())
                        : base.warn(a.version(), "doctor.tip.ghOld", com.editora.github.GhVersion.MINIMUM);
        };
    }

    /** The per-agent command overrides map ({@code AcpAgentRegistry.commandFor}'s second argument). */
    private static Map<String, String> agentOverrides(Settings s) {
        return Map.of(
                "claude", s.getAgentCommand(),
                "gemini", s.getGeminiAgentCommand(),
                "copilot", s.getCopilotAgentCommand(),
                "codex", s.getCodexAgentCommand(),
                "qwen", s.getQwenAgentCommand(),
                "opencode", s.getOpencodeAgentCommand());
    }

    private static List<String> withArgs(List<String> base, String... args) {
        List<String> cmd = new ArrayList<>(base);
        cmd.addAll(List.of(args));
        return cmd;
    }

    private static DoctorService.CheckSpec probe(DoctorCheck placeholder, UnaryOperator<DoctorCheck> probe) {
        return new DoctorService.CheckSpec(placeholder, () -> probe.apply(placeholder));
    }

    private static DoctorService.CheckSpec terminal(DoctorCheck row) {
        return new DoctorService.CheckSpec(row, null);
    }

    void shutdown() {
        service.shutdown();
    }
}
