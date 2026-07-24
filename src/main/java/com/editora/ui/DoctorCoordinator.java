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
import com.editora.doctor.DoctorStatus;
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

        /** Opens Settings focused on the page for a Doctor settings key ({@code git}/{@code lsp}/…). */
        void openSettingsFor(String settingsKey);
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

    private void install(DoctorCheck check) {
        Consumer<Boolean> done = ok -> runChecks(); // re-probe either way so the row reflects the outcome
        switch (check.install()) {
            case SERVER -> ops.installServer(check.installArg(), done);
            case LANG -> ops.installLang(InstallCatalog.Lang.valueOf(check.installArg()), done);
            case TYPST_CLI -> ops.installTypstCli(done);
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
        DoctorCheck git = DoctorCheck.checking("git", "vcs", "Git", "git").withSettings("git");
        if (gitOn) {
            specs.add(probe(git, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(List.of("git"));
                return p.present() ? base.ok(p.version()) : base.missing("doctor.tip.missing", "git");
            }));
        } else {
            specs.add(terminal(git.disabled()));
        }
        boolean ghOn = gitOn && s.isGithubSupport();
        List<String> ghCmd = ghCommand(s.getGhPath());
        DoctorCheck gh = DoctorCheck.checking("github", "vcs", "GitHub CLI", String.join(" ", ghCmd))
                .withSettings("github");
        if (ghOn) {
            specs.add(probe(gh, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(ghCmd);
                if (!p.present()) {
                    return base.missing("doctor.tip.missing", ghCmd.get(0));
                }
                boolean auth = DoctorProbes.succeeds(withArgs(ghCmd, "auth", "status"));
                return DoctorRules.ghStatus(true, auth) == DoctorStatus.OK
                        ? base.ok(p.version())
                        : base.warn(p.version(), "doctor.tip.ghAuth");
            }));
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
            specs.add(versionedTool(maid, maidCmd, "maid"));
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
                specs.add(probe(row, base -> {
                    String path = DoctorProbes.resolvedPath(argv);
                    return path.isEmpty()
                            ? base.missing("doctor.tip.missing", argv.isEmpty() ? base.label() : argv.get(0))
                            : base.ok(path);
                }));
            }
        } else {
            specs.add(terminal(DoctorCheck.checking("lsp", "lsp", tr("settings.cat.lsp"), "")
                    .withSettings("lsp")
                    .disabled()));
        }

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
                    var located = DebugAdapterLocator.locateDebugpy("", home);
                    if (located.isPresent()) {
                        return base.ok(located.get().toString());
                    }
                    boolean importable = !py.isEmpty() && DoctorProbes.succeeds(withArgs(py, "-c", "import debugpy"));
                    return importable ? base.ok("import debugpy") : base.missing("doctor.tip.missing", "debugpy");
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

        // Run (rides the LSP feature — the gutter ▶) ----------------------------------------------
        if (ops.lspFeatureEnabled()) {
            DoctorCheck java = DoctorCheck.checking("run.java", "run", "Java", "java");
            specs.add(probe(java, base -> {
                String out = DoctorProbes.output(List.of("java", "-version"));
                int major = RunService.javaMajorOf(out);
                return switch (DoctorRules.javaRunStatus(major)) {
                    case OK -> base.ok(DoctorRules.firstLine(out, ""));
                    case WARN -> base.warn(DoctorRules.firstLine(out, ""), "doctor.tip.javaOld", String.valueOf(major));
                    default -> base.missing("doctor.tip.missing", "java");
                };
            }));
            specs.add(optionalRunTool("run.python", "Python", List.of("python3"), List.of("python"), "Python"));
            specs.add(optionalRunTool("run.shell", "Bash", List.of("bash"), null, "shell"));
            specs.add(optionalRunTool("run.make", "Make", List.of("make"), null, "Makefile"));
        }

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

    /** The configured gh command, tokenized exactly like {@code GitHubService.setCommand}. */
    private static List<String> ghCommand(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of("gh");
        }
        List<String> tokens = new ArrayList<>();
        for (String t : configured.strip().split("\\s+")) {
            if (!t.isBlank()) {
                tokens.add(t);
            }
        }
        return tokens.isEmpty() ? List.of("gh") : List.copyOf(tokens);
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
