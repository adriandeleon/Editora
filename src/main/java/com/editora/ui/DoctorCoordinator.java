package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import com.editora.agent.AcpAgentRegistry;
import com.editora.config.Settings;
import com.editora.dap.DapServerRegistry;
import com.editora.dap.DebugAdapterLocator;
import com.editora.diagram.DiagramKind;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorNeeds;
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

        /** Whether the running jdtls provides java-debug itself (a distribution that ships it: no jar to find). */
        default boolean javaDebugFromServer() {
            return false;
        }

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

        /** The shared plugin manager (the Plugins section), or null when there is none. */
        default com.editora.plugin.PluginManager plugins() {
            return null;
        }

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

        /** Whether that interpreter runs on another machine (a remote host, WSL, a container): not run here. */
        default boolean pythonInterpreterElsewhere() {
            return false;
        }

        /** Whether the user set this server's command (a non-default one): then it is needed even with nothing open. */
        default boolean lspServerCommandSet(String serverId) {
            return false;
        }

        /** Runs a registered command (a feature's {@code view.toggle…} for a row's "Turn on"). */
        default void runCommand(String commandId) {}

        /** Shows {@code path} in the system file manager (a row's "Reveal"). */
        default void reveal(Path path) {}
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

            @Override
            public void enable(DoctorCheck check) {
                enableFeature(check.enableKey());
            }

            @Override
            public void openUrl(String url) {
                host.openExternalUrl(url);
            }

            @Override
            public void reveal(Path path) {
                ops.reveal(path);
            }

            @Override
            public void reportCopied() {
                host.setStatus(tr("doctor.reportCopied"));
            }
        });
    }

    DoctorPane pane() {
        return pane;
    }

    /**
     * Builds the catalog for the current state and (re)runs every probe. Rows keep their last result, marked as
     * being checked again, until the new one lands (review C3), and the pane learns when the run is done.
     */
    void runChecks() {
        List<DoctorService.CheckSpec> specs = buildSpecs();
        pane.setMachine(remoteTarget());
        pane.setFontScale(host.settings().getFontZoom());
        pane.beginRun(specs.stream().map(DoctorService.CheckSpec::placeholder).toList());
        if (probeOverrideForTest != null) {
            for (DoctorService.CheckSpec spec : specs) {
                if (spec.probe() != null) {
                    pane.updateCheck(probeOverrideForTest.apply(spec));
                }
            }
            pane.endRun();
            return;
        }
        service.run(specs, pane::updateCheck, pane::endRun);
    }

    /**
     * The machine this window's project runs its tools on, when that is not this one (an SSH host, WSL, a
     * container) — the header then says the rows describe this computer (review D2) — else null.
     */
    private String remoteTarget() {
        Path root = ops.projectRoot();
        if (root == null) {
            return null;
        }
        try {
            com.editora.process.ExecTarget target = com.editora.process.ExecTargets.forPath(root);
            return target.isLocal() ? null : target.id();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A row's "Turn on": runs the feature's own toggle command when the feature is off (never turning it off),
     * then checks again. The command keys are the catalog's {@code enableKey}s.
     */
    void enableFeature(String key) {
        Settings s = host.settings();
        switch (key) {
            case "git" -> toggleIfOff(s.isGitSupport(), "view.toggleGit");
            case "github" -> toggleIfOff(s.isGithubSupport(), "view.toggleGithub");
            case "search" -> toggleIfOff(s.isRipgrepSearch(), "view.toggleRipgrep");
            case "mermaid" -> toggleIfOff(s.isMermaidSupport(), "view.toggleMermaid");
            case "diagrams" -> toggleIfOff(s.isDiagramSupport(), "view.toggleDiagramSupport");
            case "typst" -> toggleIfOff(s.isTypstSupport(), "view.toggleTypstSupport");
            case "lsp" -> toggleIfOff(s.isLspSupport(), "view.toggleLsp");
            case "debug" -> toggleIfOff(s.isDebugSupport(), "view.toggleDebug");
            case "ai" -> {
                toggleIfOff(s.isAiSupport(), "view.toggleAi");
                toggleIfOff(s.isAiEnabled(), "view.toggleAiEnabled");
            }
            case "httpScripts" -> {
                // Read live by the HTTP Client, so the setting alone turns it on; there is no toggle command.
                if (!s.isHttpScriptsEnabled()) {
                    s.setHttpScriptsEnabled(true);
                    host.requestSave();
                    host.syncSettingsWindow();
                }
            }
            default -> {
                return;
            }
        }
        runChecks();
    }

    private void toggleIfOff(boolean on, String commandId) {
        if (!on) {
            ops.runCommand(commandId);
        }
    }

    private void install(DoctorCheck check) {
        pane.installStarted(check.id()); // the row's button reads "Installing…" until the installer reports back
        Consumer<Boolean> done = ok -> {
            pane.installFinished(check.id());
            runChecks(); // re-probe either way so the row reflects the outcome; the page keeps its results meanwhile
        };
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

    /** Where the Python formatter row sends a user who has neither formatter (Editora has no installer for it). */
    static final String RUFF_INSTALL_URL = "https://docs.astral.sh/ruff/installation/";

    /**
     * What this window needs, read on the FX thread when the catalog is built: the language servers its open
     * files use, whether a shell or Python file is open, and — lazily, on the probe pool — the file names in the
     * project root (a local root only; a remote one is not listed). A tool nothing here needs is neutral when
     * absent rather than red (review A1/A2).
     */
    record Needs(Set<String> openServers, boolean shellOpen, boolean pythonOpen, Supplier<List<String>> rootFiles) {

        Set<String> rootServers() {
            return DoctorNeeds.serversForRootFiles(rootFiles.get());
        }

        boolean rootHasAny(List<String> markers) {
            List<String> files = rootFiles.get();
            return markers.stream().anyMatch(files::contains);
        }
    }

    private Needs needs() {
        Set<String> servers = new HashSet<>();
        boolean[] shellPython = new boolean[2];
        host.forEachBuffer(b -> {
            String id = com.editora.lsp.LspServerRegistry.serverIdFor(b.getLanguage());
            if (id != null) {
                servers.add(id);
            }
            Path p = b.getPath();
            if (p != null
                    && p.getFileName() != null
                    && com.editora.lsp.LspServerRegistry.isPomFile(
                            p.getFileName().toString())) {
                servers.add(com.editora.lsp.LspServerRegistry.MAVEN_POM_SERVER_ID);
            }
            shellPython[0] |= b.isShell();
            shellPython[1] |= b.isPython();
        });
        Path root = ops.projectRoot();
        boolean localRoot = root != null && root.getFileSystem() == java.nio.file.FileSystems.getDefault();
        Supplier<List<String>> files = memoize(() -> localRoot ? rootFileNames(root) : List.of());
        return new Needs(Set.copyOf(servers), shellPython[0], shellPython[1], files);
    }

    /** The names of the files directly in {@code root} (its markers); empty when it cannot be listed. Blocking. */
    static List<String> rootFileNames(Path root) {
        try (var stream = java.nio.file.Files.list(root)) {
            return stream.limit(2_000).map(p -> p.getFileName().toString()).toList();
        } catch (java.io.IOException | RuntimeException e) {
            return List.of();
        }
    }

    private static <T> Supplier<T> memoize(Supplier<T> source) {
        return new Supplier<>() {
            private T value;
            private boolean done;

            @Override
            public synchronized T get() {
                if (!done) {
                    value = source.get();
                    done = true;
                }
                return value;
            }
        };
    }

    /** The full check list for the current settings/OS. FX thread; probe lambdas run on the doctor pool. */
    List<DoctorService.CheckSpec> buildSpecs() {
        Settings s = host.settings();
        boolean simple = host.simpleModeActive();
        Needs needs = needs();
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
            specs.add(terminal(offRow(git, simple ? "" : "git")));
        }
        boolean ghOn = gitOn && s.isGithubSupport();
        List<String> ghCmd = GitHubService.commandTokens(s.getGhPath());
        DoctorCheck gh = DoctorCheck.checking("github", "vcs", "GitHub CLI", String.join(" ", ghCmd))
                .withSettings("github");
        if (ghOn) {
            specs.add(probe(gh, base -> ghCheck(base, ghCmd.get(0), GitHubService.probe(ghCmd, GH_PROBE_TIMEOUT))));
        } else {
            // With Git off, GitHub cannot be turned on alone: the Git row's Turn on comes first.
            specs.add(terminal(offRow(gh, gitOn && !simple ? "github" : "")));
        }

        // Search ----------------------------------------------------------------------------------
        List<String> rgCmd = Ripgrep.command(s.getRipgrepCommand());
        DoctorCheck rg = DoctorCheck.checking("ripgrep", "search", "ripgrep", String.join(" ", rgCmd))
                .withSettings("search");
        if (s.isRipgrepSearch()) {
            // An accelerator: Find in Files works without it, so its absence is a fact, not a fault.
            specs.add(probe(rg, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(rgCmd);
                return p.present() ? base.ok(p.version()) : base.optional("doctor.tip.ripgrepOptional");
            }));
        } else {
            specs.add(terminal(offRow(rg, "search")));
        }

        // Preview & diagrams ----------------------------------------------------------------------
        List<String> mmdcCmd = ops.mermaidService().mmdcCommand();
        List<String> maidCmd = ops.mermaidService().maidCommand();
        DoctorCheck mmdc = DoctorCheck.checking("mmdc", "preview", "Mermaid CLI", String.join(" ", mmdcCmd))
                .withSettings("mermaid")
                .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.MERMAID.name());
        DoctorCheck maid = DoctorCheck.checking(
                        "maid", "preview", tr("doctor.label.mermaidLinter"), String.join(" ", maidCmd))
                .withSettings("mermaid");
        if (s.isMermaidSupport()) {
            specs.add(versionedTool(mmdc, mmdcCmd, "mmdc", "--version"));
            // The linter is optional: without it diagrams still render, only live linting is off.
            specs.add(probe(maid, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(maidCmd);
                return p.present()
                        ? base.ok(p.version().isBlank() ? DoctorProbes.resolvedPath(maidCmd) : p.version())
                        : base.optional("doctor.tip.maidOptional");
            }));
        } else {
            // One row for the feature, named for it and listing its tools (review A6).
            specs.add(terminal(offRow(
                    DoctorCheck.checking("mermaid", "preview", tr("doctor.label.mermaidOff"), "")
                            .withSettings("mermaid"),
                    "mermaid")));
        }
        List<String> dotCmd = ops.diagramService().command(DiagramKind.DOT);
        List<String> pumlCmd = ops.diagramService().command(DiagramKind.PLANTUML);
        DoctorCheck dot = DoctorCheck.checking("dot", "preview", "Graphviz", String.join(" ", dotCmd))
                .withSettings("diagrams");
        DoctorCheck puml = DoctorCheck.checking("plantuml", "preview", "PlantUML", String.join(" ", pumlCmd))
                .withSettings("diagrams");
        if (s.isDiagramSupport()) {
            // Both reject --version: dot prints its version only for -V, PlantUML for -version (review D3).
            specs.add(versionedTool(dot, dotCmd, "dot", "-V"));
            specs.add(versionedTool(puml, pumlCmd, "plantuml", "-version"));
        } else {
            specs.add(terminal(offRow(
                    DoctorCheck.checking("diagrams", "preview", tr("doctor.label.diagramsOff"), "")
                            .withSettings("diagrams"),
                    "diagrams")));
        }
        List<String> typstCmd = ops.typstService().command();
        DoctorCheck typst = DoctorCheck.checking(
                        "typst", "preview", tr("doctor.label.typstCli"), String.join(" ", typstCmd))
                .withSettings("typst")
                .withInstall(DoctorCheck.Install.TYPST_CLI, "");
        if (s.isTypstSupport()) {
            specs.add(versionedTool(typst, typstCmd, "typst", "--version"));
        } else {
            specs.add(terminal(offRow(typst.withoutInstall(), "typst")));
        }

        // Language servers ------------------------------------------------------------------------
        boolean lspOn = ops.lspFeatureEnabled();
        if (lspOn) {
            for (String serverId : ops.lspServerIds()) {
                if (!ops.lspServerEnabled(serverId)) {
                    continue;
                }
                specs.add(serverRow(serverId, needs));
                if ("bash".equals(serverId)) {
                    // What bash-language-server lints and formats with. Without them a shell script silently
                    // gets no diagnostics and Format Document changes nothing.
                    for (String toolId : InstallCatalog.PINNED_TOOL_IDS) {
                        specs.add(bashToolRow(toolId, needs));
                    }
                }
            }
        } else {
            specs.add(terminal(offRow(
                    DoctorCheck.checking("lsp", "lsp", tr("doctor.label.sectionOff"), "")
                            .withSettings("lsp"),
                    "lsp")));
        }
        specs.add(pythonFormatterRow(needs)); // Format Document on .py works with the language server off too

        // Debugging -------------------------------------------------------------------------------
        if (ops.debugFeatureEnabled()) {
            Path home = Path.of(System.getProperty("user.home", ""));
            ProjectScope scope = host.projectScope(); // a trusted project's debugger paths, else the user's
            String javaPluginPath = scope.debugTool("java", s.getJavaDebugPluginPath());
            DoctorCheck javaDebug = DoctorCheck.checking("debug.java", "debug", tr("doctor.label.javaDebug"), "")
                    .withSettings("debug")
                    .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.JAVA.name());
            boolean fromServer = ops.javaDebugFromServer();
            if (!lspOn) {
                // Java debugging runs through the Java language server: the fix is turning LSP on, not
                // installing anything (review A3), and with LSP off nothing else here matters.
                specs.add(terminal(javaDebug
                        .missing("doctor.tip.needsLsp")
                        .withoutInstall()
                        .withEnable("lsp")
                        .withSettings("lsp")));
            } else {
                // A jdtls that ships java-debug has no jar to find (review A7).
                specs.add(probe(
                        javaDebug,
                        base -> DebugAdapterLocator.locate(javaPluginPath, home)
                                .map(p -> base.ok(p.toString()))
                                .orElseGet(() -> fromServer
                                        ? base.ok("jdtls")
                                        : base.missing("doctor.tip.missingInstallable", "java-debug"))));
            }
            if (s.isPythonDebugEnabled()) {
                List<String> py = DapServerRegistry.interpreterArgv(
                        "python", scope.debugTool("python", s.getPythonDebugCommand()));
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
                    if (blocker.isEmpty()) {
                        return base.ok(dir != null ? dir.toString() : "import debugpy");
                    }
                    // debugpy is a module the interpreter imports, not a program on PATH (review A3).
                    return "debugpy".equals(blocker)
                            ? base.missing("doctor.tip.debugpy", String.join(" ", py))
                            : base.missing("doctor.tip.missing", blocker);
                }));
            }
            if (s.isJsDebugEnabled()) {
                String jsPath = scope.debugTool("javascript", s.getJsDebugPath());
                List<String> node = DapServerRegistry.interpreterArgv("javascript", "");
                DoctorCheck jsDebug = DoctorCheck.checking("debug.javascript", "debug", tr("doctor.label.jsDebug"), "")
                        .withSettings("debug")
                        .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.JAVASCRIPT.name());
                specs.add(probe(jsDebug, base -> {
                    var server = DebugAdapterLocator.locateJsDebugServer(jsPath, home);
                    if (server.isEmpty()) {
                        return base.missing("doctor.tip.missingInstallable", "js-debug");
                    }
                    DoctorProbes.Presence nodeP = DoctorProbes.version(node);
                    return nodeP.present()
                            ? base.ok(server.get().toString())
                            : base.missing("doctor.tip.missing", "node");
                }));
            }
        } else {
            specs.add(terminal(offRow(
                    DoctorCheck.checking("debug", "debug", tr("doctor.label.sectionOff"), "")
                            .withSettings("debug"),
                    simple ? "" : "debug")));
        }

        // Run ------------------------------------------------------------------------------------
        // Standalone Java/Python/Make runs work without LSP; Bash Run no longer depends on it either.
        String selectedJava = com.editora.run.JdkToolchain.javaExecutable(s.getMavenJdkHome());
        String javaExecutable = selectedJava.isBlank() ? "java" : selectedJava;
        // The JDK is the Build Tools page's Maven JDK, so that is where "Settings…" leads.
        DoctorCheck java = DoctorCheck.checking("run.java", "run", tr("doctor.label.jdk"), javaExecutable)
                .withSettings("buildTools");
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
        specs.add(optionalRunTool("run.shell", "Bash", List.of("bash"), null, "shell"));

        // HTTP Client: its JavaScript response handlers run on the user's Node.js ------------------
        if (s.isHttpClientSupport() && !simple) {
            String nodeSetting = s.getHttpNodePath().isBlank() ? "node" : s.getHttpNodePath();
            DoctorCheck handlers = DoctorCheck.checking(
                            "http.scripts", "http", tr("doctor.label.httpScripts"), nodeSetting)
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
                specs.add(terminal(offRow(handlers, "httpScripts")));
            }
        }

        // Build tools (the tool windows self-gate on project markers) ------------------------------
        if (!simple) {
            for (com.editora.build.BuildTool tool : com.editora.build.BuildTool.values()) {
                specs.add(buildTool(tool, s, needs));
            }
        }

        // AI & agents -----------------------------------------------------------------------------
        boolean aiOn = s.isAiSupport() && s.isAiEnabled() && !simple;
        if (!aiOn) {
            specs.add(terminal(offRow(
                    DoctorCheck.checking("ai", "ai", tr("doctor.label.sectionOff"), "")
                            .withSettings("ai"),
                    simple ? "" : "ai")));
        } else {
            specs.add(aiProviderRow(s));
            if (s.isAgentSupport()) {
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
        }

        // Remote & containers: the engines that open a project somewhere else (review D4) ----------
        if (!simple) {
            List<String> engine = com.editora.container.ContainerEngine.of(s.getContainerEngine())
                    .command();
            DoctorCheck containers = DoctorCheck.checking(
                            "containers", "remote", tr("doctor.label.containerEngine"), String.join(" ", engine))
                    .withSettings("remote");
            specs.add(probe(containers, base -> {
                DoctorProbes.Presence p = DoctorProbes.version(engine);
                return p.present() ? base.ok(p.version()) : base.optional("doctor.tip.containerOptional");
            }));
            if (isWindows()) {
                DoctorCheck wsl = DoctorCheck.checking("wsl", "remote", "WSL", "wsl.exe");
                specs.add(probe(wsl, base -> {
                    String path = DoctorProbes.resolvedPath(List.of("wsl.exe"));
                    return path.isEmpty() ? base.optional("doctor.tip.wslOptional") : base.ok(path);
                }));
            }
        }

        // System ----------------------------------------------------------------------------------
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
        // The project tree and language servers follow the disk through the system's file watcher; without one
        // (Linux: the user's inotify instances used up) folders are polled, which the user should hear about.
        DoctorCheck watch = DoctorCheck.checking("fileWatch", "system", tr("doctor.label.fileWatch"), "");
        specs.add(probe(watch, base -> {
            String why = com.editora.io.WatchServices.probe();
            why = why != null ? why : com.editora.io.WatchServices.fallbackReason();
            return why == null ? base.ok(tr("doctor.fileWatch.native")) : base.warn(why, "doctor.tip.fileWatchPolling");
        }));
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

        // Plugins ---------------------------------------------------------------------------------
        specs.addAll(pluginRows(s, ops.plugins()));

        return specs;
    }

    // --- rows --------------------------------------------------------------------------------------

    /** A feature-off row: gray, never probed, with a "Turn on" for {@code enableKey} ("" = none offered). */
    /**
     * The Plugins section: what this session runs, then one row per plugin that failed to load or to start,
     * started slowly, or changed since it was enabled — what used to show nowhere but the log. No "Turn on":
     * enabling plugins is a security decision made on the Settings page. Pure over the manager's state.
     */
    static List<DoctorService.CheckSpec> pluginRows(Settings s, com.editora.plugin.PluginManager plugins) {
        List<DoctorService.CheckSpec> rows = new ArrayList<>();
        if (plugins == null) {
            return rows;
        }
        DoctorCheck summary = DoctorCheck.checking("plugins", "plugins", tr("doctor.label.plugins"), "")
                .withSettings("plugins");
        if (com.editora.plugin.PluginManager.disabledForSession()) {
            rows.add(terminal(summary.warn(tr("doctor.plugins.disabledForSession"), "")));
            return rows;
        }
        if (!s.isPluginSupport()) {
            rows.add(terminal(summary.disabled()));
            return rows;
        }
        long running = plugins.launchSet().stream()
                .filter(d -> d.enabled() && d.loadError() == null)
                .count();
        rows.add(terminal(summary.ok(
                tr("doctor.plugins.summary", running, plugins.descriptors().size()))));
        for (com.editora.plugin.PluginDescriptor d : plugins.descriptors()) {
            com.editora.plugin.PluginDescriptor.LoadProblem runtime = plugins.runtimeProblem(d.id());
            String problem = d.loadError() != null
                    ? PluginLoadProblems.text(d)
                    : runtime != null ? PluginLoadProblems.text(runtime) : null;
            if (problem == null) {
                continue;
            }
            DoctorCheck row = DoctorCheck.checking("plugin:" + d.id(), "plugins", d.displayName(), d.id())
                    .withSettings("plugins");
            boolean slow = d.loadError() == null
                    && runtime.kind() == com.editora.plugin.PluginDescriptor.LoadProblem.Kind.SLOW_START;
            rows.add(terminal(slow ? row.warn(problem, "") : row.missing("doctor.tip.pluginProblem", problem)));
        }
        return rows;
    }

    private static DoctorCheck offRow(DoctorCheck placeholder, String enableKey) {
        // A whole section that is off says so in its label; a tip repeating it would be noise.
        boolean section = placeholder.label().equals(tr("doctor.label.sectionOff"));
        DoctorCheck off = section ? placeholder.disabled("") : placeholder.disabled();
        return enableKey.isEmpty() ? off : off.withEnable(enableKey);
    }

    /**
     * One language server: its resolved path (and Pyright's npm version) when found. Missing, it is a problem
     * only when something in this window needs it (review A1); otherwise it is "not installed", Install… kept.
     */
    private DoctorService.CheckSpec serverRow(String serverId, Needs needs) {
        List<String> argv = ops.lspServerArgv(serverId);
        DoctorCheck row = DoctorCheck.checking("lsp." + serverId, "lsp", serverLabel(serverId), String.join(" ", argv))
                .withSettings("lsp");
        DoctorCheck.Install install = installKindFor(serverId);
        if (install != DoctorCheck.Install.NONE) {
            row = row.withInstall(install, installArgFor(serverId));
        }
        boolean python = "python".equals(serverId);
        boolean configured = ops.lspServerCommandSet(serverId);
        return probe(row, base -> {
            if (argv.isEmpty()) {
                // No command at all (an uninstalled maven-pom): say that, not "not found on PATH" (review A3).
                boolean needed =
                        DoctorNeeds.serverNeeded(serverId, needs.openServers(), needs.rootServers(), configured);
                return needed ? base.missing("doctor.tip.notConfigured") : base.optional("doctor.tip.notConfigured");
            }
            String path = DoctorProbes.resolvedPath(argv);
            if (path.isEmpty()) {
                boolean needed =
                        DoctorNeeds.serverNeeded(serverId, needs.openServers(), needs.rootServers(), configured);
                if (!needed) {
                    return base.optional("doctor.tip.serverOptional");
                }
                return base.missing(
                        base.install() == DoctorCheck.Install.NONE
                                ? "doctor.tip.missing"
                                : "doctor.tip.missingInstallable",
                        argv.get(0));
            }
            // Pyright's launcher prints no version; its npm package.json beside it says (review B16).
            String version = python ? DoctorRules.npmPackageVersion(Path.of(path)) : "";
            return base.ok(version.isEmpty() ? path : version + " — " + path);
        });
    }

    /** A row for one of bash-language-server's tools: its version when it runs, else Install…. */
    private DoctorService.CheckSpec bashToolRow(String toolId, Needs needs) {
        String command = ops.bashTool(toolId);
        String label = "shellcheck".equals(toolId) ? "ShellCheck" : toolId;
        DoctorCheck row = DoctorCheck.checking("lsp.bash." + toolId, "format", label, command)
                .withSettings("lsp")
                .withInstall(DoctorCheck.Install.TOOL, toolId);
        List<String> argv = List.of(command);
        boolean needed = needs.shellOpen();
        return probe(row, base -> {
            DoctorProbes.Presence p = DoctorProbes.version(argv);
            if (p.present()) {
                return base.ok(p.version());
            }
            return needed
                    ? base.missing("doctor.tip.bashTool", command)
                    : base.optional("doctor.tip.bashTool", command);
        });
    }

    /** Ruff or Black for Format Document on Python files (adl's decision 4); needed once Python is in play. */
    private DoctorService.CheckSpec pythonFormatterRow(Needs needs) {
        var resolver = ops.pythonInterpreter();
        DoctorCheck row = DoctorCheck.checking(
                        "python.formatter", "format", tr("doctor.label.pythonFormatter"), "ruff / black")
                .withHelp(RUFF_INSTALL_URL);
        return probe(row, base -> {
            Path venv = resolver == null ? null : resolver.get().venv();
            if (venv != null && venv.getFileSystem() != java.nio.file.FileSystems.getDefault()) {
                venv = null; // Ruff/Black run on this machine: a remote environment's tools cannot
            }
            var found = com.editora.format.PythonFormatter.findLocal(venv);
            if (found.isEmpty()) {
                boolean needed = needs.pythonOpen()
                        || DoctorNeeds.pythonProject(needs.rootFiles().get());
                return needed
                        ? base.warn("", "doctor.tip.pythonFormatter")
                        : base.optional("doctor.tip.pythonFormatter");
            }
            DoctorProbes.Presence p = DoctorProbes.version(List.of(found.get().executable()));
            String version = p.present() && !p.version().isBlank()
                    ? p.version()
                    : found.get().name();
            return base.ok(version + " — " + found.get().executable());
        });
    }

    /**
     * The AI provider the actions and chat use: a key is set where the provider needs one, or Codex's command
     * is installed. No network call — whether the endpoint answers is the AI page's Test button's job.
     */
    private DoctorService.CheckSpec aiProviderRow(Settings s) {
        com.editora.ai.AiProvider provider = com.editora.ai.AiProvider.from(s.getAiProvider());
        String name = tr("settings.ai.provider." + provider.id());
        DoctorCheck row = DoctorCheck.checking("ai.provider", "ai", tr("doctor.label.aiProvider"), name)
                .withSettings("ai");
        if (provider == com.editora.ai.AiProvider.CODEX) {
            List<String> codex = AcpAgentRegistry.commandFor("codex", agentOverrides(s));
            return probe(row, base -> {
                String path = DoctorProbes.resolvedPath(codex);
                return path.isEmpty()
                        ? base.missing("doctor.tip.missing", codex.isEmpty() ? "codex" : codex.get(0))
                        : base.ok(path);
            });
        }
        boolean keyMissing =
                provider.requiresApiKey() && s.getApiKeyFor(provider).isBlank();
        String endpoint = s.getAiEndpointFor(provider);
        return terminal(
                keyMissing
                        ? row.missing("doctor.tip.aiKey", name)
                        : row.ok(endpoint == null || endpoint.isBlank() ? provider.defaultEndpoint() : endpoint));
    }

    /**
     * A build tool: the command its tool window runs — the setting, the project's wrapper, else the bare name
     * (review D1). Missing, it is amber when the project has its marker file, else "not installed".
     */
    private DoctorService.CheckSpec buildTool(com.editora.build.BuildTool tool, Settings s, Needs needs) {
        String override = host.projectScope().buildCommand(tool, s); // a trusted project's command, else the user's
        List<String> configured = com.editora.process.ConfiguredCommand.tokens(override, tool.commandExample());
        String label = "go".equals(tool.id()) ? tr("doctor.label.goToolchain") : tool.displayName();
        DoctorCheck row = DoctorCheck.checking("build." + tool.id(), "build", label, String.join(" ", configured))
                .withSettings("buildTools");
        Path root = ops.projectRoot();
        boolean windows = isWindows();
        boolean wrapperKind = hasWrapper(tool);
        return probe(row, base -> {
            boolean project = root != null && needs.rootHasAny(tool.markers());
            List<String> argv = configured;
            if (project) {
                try {
                    argv = tool.executable(root, windows, override);
                } catch (RuntimeException e) {
                    argv = configured;
                }
            }
            String path = DoctorProbes.resolvedPath(argv);
            if (!path.isEmpty()) {
                return base.withCommand(String.join(" ", argv)).ok(path);
            }
            if (project) {
                return base.warn(
                        "",
                        "doctor.tip.buildNeeded",
                        argv.isEmpty() ? label : argv.get(0),
                        tool.markers().get(0));
            }
            return wrapperKind
                    ? base.optional("doctor.tip.buildOptional", tool.displayName())
                    : base.optional("doctor.tip.buildOptionalNoWrapper", tool.displayName());
        });
    }

    /** Whether a build tool has a project wrapper (Maven's mvnw, Gradle's gradlew) that makes it optional. */
    private static boolean hasWrapper(com.editora.build.BuildTool tool) {
        return tool == com.editora.build.BuildTool.MAVEN || tool == com.editora.build.BuildTool.GRADLE;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .startsWith("windows");
    }

    // --- helpers -----------------------------------------------------------------------------------

    /** A standard "present + version, else missing" tool row, asking for the version with {@code versionArg}. */
    private static DoctorService.CheckSpec versionedTool(
            DoctorCheck placeholder, List<String> cmd, String toolName, String versionArg) {
        return probe(placeholder, base -> {
            DoctorProbes.Presence p = DoctorProbes.version(cmd, versionArg);
            if (!p.present()) {
                return base.missing(
                        base.install() == DoctorCheck.Install.NONE
                                ? "doctor.tip.missing"
                                : "doctor.tip.missingInstallable",
                        toolName);
            }
            // A found tool that printed no version still says where it is, so no green row has an empty column.
            return base.ok(p.version().isBlank() ? DoctorProbes.resolvedPath(cmd) : p.version());
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
        String setting = host.projectScope().debugTool("python", s.getPythonDebugCommand());
        // The window's resolver when it has one: the active Python file's interpreter (its venv, the picked one,
        // a remote host's) — what the status bar shows and Pyright is told, so Doctor has one Python row.
        var resolver = ops.pythonInterpreter();
        boolean elsewhere = resolver != null && ops.pythonInterpreterElsewhere();
        DoctorCheck row = DoctorCheck.checking("run.python", "run", tr("doctor.label.pythonInterpreter"), "python")
                .withSettings("debug");
        return probe(row, base -> {
            com.editora.run.PythonInterpreters.PythonInterpreter py = resolver != null
                    ? resolver.get()
                    : com.editora.run.PythonInterpreters.resolveLocal(null, root, "", choice, setting);
            String what = String.join(" ", py.command()) + " (" + PythonInterpreterView.reason(py) + ")";
            if (elsewhere) {
                return base.ok(what); // it runs on that machine, not here: named, not probed
            }
            List<String> argv = new ArrayList<>(com.editora.run.PythonLaunch.withoutEnvPrefix(py.command()));
            argv.add("--version");
            String version = DoctorRules.firstLine(DoctorProbes.output(argv).strip(), "");
            if (!version.startsWith("Python")) {
                return base.warn(what, "doctor.tip.pythonInterpreter", String.join(" ", py.command()));
            }
            return base.ok(version + " — " + what);
        });
    }

    /** A Run-section interpreter row: absent, it is only "not installed" (needed just for that file type). */
    private static DoctorService.CheckSpec optionalRunTool(
            String id, String label, List<String> cmd, List<String> fallback, String fileKind) {
        DoctorCheck row = DoctorCheck.checking(id, "run", label, cmd.get(0));
        return probe(row, base -> {
            String path = DoctorProbes.resolvedPath(cmd);
            if (path.isEmpty() && fallback != null) {
                path = DoctorProbes.resolvedPath(fallback);
            }
            return path.isEmpty() ? base.optional("doctor.tip.runOptional", fileKind) : base.ok(path);
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
