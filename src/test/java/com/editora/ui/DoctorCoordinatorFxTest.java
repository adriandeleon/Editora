package com.editora.ui;

import java.util.List;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.doctor.DoctorCheck;
import com.editora.doctor.DoctorService;
import com.editora.doctor.DoctorStatus;
import com.editora.install.InstallCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DoctorCoordinator} against a fake host + Ops: the check catalog honors feature toggles (a
 * disabled feature yields a terminal gray row with no probe), the probe-override seam resolves every
 * probing row without spawning a subprocess, and the fix actions route to the right Ops calls.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        java.nio.file.Path config;
        final List<String> statuses = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public java.nio.file.Path configDir() {
            return config;
        }
    }

    private static final class FakeOps implements DoctorCoordinator.Ops {
        final com.editora.mermaid.MermaidService mermaid = new com.editora.mermaid.MermaidService();
        final com.editora.diagram.DiagramService diagram = new com.editora.diagram.DiagramService();
        final com.editora.typst.TypstService typst = new com.editora.typst.TypstService();
        String installedServer;
        boolean lspEnabled = true;
        boolean debugEnabled = false;
        InstallCatalog.Lang installedLang;
        boolean installedTypstCli;
        String openedSettingsKey;
        boolean updateAsked;
        List<String> servers = List.of("java", "json");
        java.util.Map<String, List<String>> argv = new java.util.HashMap<>();
        java.nio.file.Path config; // handed to the FakeHost: the config dir comes from the window's host

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
            return true;
        }

        @Override
        public boolean lspFeatureEnabled() {
            return lspEnabled;
        }

        @Override
        public boolean debugFeatureEnabled() {
            return debugEnabled;
        }

        @Override
        public List<String> lspServerIds() {
            return servers;
        }

        @Override
        public boolean lspServerEnabled(String serverId) {
            return true;
        }

        @Override
        public List<String> lspServerArgv(String serverId) {
            return argv.getOrDefault(serverId, List.of(serverId + "-server"));
        }

        @Override
        public void installServer(String serverId, boolean update, Consumer<Boolean> onDone) {
            installedServer = serverId;
            updateAsked = update;
            onDone.accept(true);
        }

        @Override
        public void installLang(InstallCatalog.Lang lang, String updateStep, Consumer<Boolean> onDone) {
            installedLang = lang;
            onDone.accept(true);
        }

        @Override
        public void installTypstCli(Consumer<Boolean> onDone) {
            installedTypstCli = true;
            onDone.accept(true);
        }

        @Override
        public void openSettingsFor(String settingsKey) {
            openedSettingsKey = settingsKey;
        }
    }

    @Test
    void disabledFeaturesYieldTerminalGrayRowsWithNoProbe() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMermaidSupport(false);
        host.settings.setDiagramSupport(false);
        host.settings.setTypstSupport(false);
        FakeOps ops = new FakeOps();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));

        List<DoctorService.CheckSpec> specs = FxTestSupport.callOnFx(doctor::buildSpecs);
        // A feature that is off is one row named for the feature, with a Turn on action (review A6/C5).
        for (String disabledId : List.of("mermaid", "diagrams", "typst")) {
            DoctorService.CheckSpec spec = specs.stream()
                    .filter(s -> s.placeholder().id().equals(disabledId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(DoctorStatus.DISABLED, spec.placeholder().status());
            assertNull(spec.probe(), disabledId + " must not spawn a probe while its feature is off");
            assertFalse(spec.placeholder().enableKey().isEmpty(), disabledId + " offers Turn on");
        }
        assertTrue(specs.stream().noneMatch(s -> s.placeholder().id().equals("mmdc")), "no tool rows while off");
        // Every DISABLED placeholder is terminal — no probe anywhere.
        assertTrue(specs.stream()
                .filter(s -> s.placeholder().status() == DoctorStatus.DISABLED)
                .allMatch(s -> s.probe() == null));
        // The debug feature is off (FakeOps) → one gray summary row instead of adapter rows.
        assertTrue(specs.stream()
                .anyMatch(s ->
                        s.placeholder().id().equals("debug") && s.placeholder().status() == DoctorStatus.DISABLED));
    }

    @Test
    void standaloneRunChecksRemainWhenLspIsOff() throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.lspEnabled = false;
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));

        List<DoctorService.CheckSpec> specs = FxTestSupport.callOnFx(doctor::buildSpecs);
        // Shell Run no longer depends on LSP, so its Bash row stays too.
        for (String id : List.of("run.java", "run.python", "run.make", "run.shell")) {
            assertTrue(specs.stream().anyMatch(s -> s.placeholder().id().equals(id)), id);
        }
    }

    @Test
    void gitRowShowsTheConfiguredCommandAndJavaRowLinksToItsJdkSetting() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setGitPath("/opt/git/bin/git");
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, new FakeOps()));

        List<DoctorService.CheckSpec> specs = FxTestSupport.callOnFx(doctor::buildSpecs);
        // Resolved at spec-build time (FX thread), so the probe never reads Settings off-thread.
        assertEquals("/opt/git/bin/git", placeholder(specs, "git").command());
        // Its JDK is the Build Tools page's Maven JDK, so an old/missing Java row can offer Settings….
        assertEquals("buildTools", placeholder(specs, "run.java").settingsKey());
    }

    @Test
    void absentMermaidLinterIsOnlyNotInstalled() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMermaidSupport(true);
        FakeOps ops = new FakeOps();
        ops.mermaid.setPaths("", "/nonexistent/editora-doctor-test/maid");
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));

        List<DoctorService.CheckSpec> specs = FxTestSupport.callOnFx(doctor::buildSpecs);
        DoctorCheck maid = spec(specs, "maid").probe().get(); // launches nothing: the path doesn't exist
        // Diagrams render without it: a neutral fact, not a warning (review A2).
        assertEquals(DoctorStatus.OPTIONAL, maid.status());
        assertEquals("doctor.tip.maidOptional", maid.tipKey());
    }

    @Test
    void debugpyIsMissingWhenItsInterpreterWontRunEvenIfABundleIsFound() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setPythonDebugEnabled(true);
        String python = "/nonexistent/editora-doctor-test/python3";
        host.settings.setPythonDebugCommand(python);
        FakeOps ops = new FakeOps();
        ops.debugEnabled = true;
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));

        List<DoctorService.CheckSpec> specs = FxTestSupport.callOnFx(doctor::buildSpecs);
        DoctorCheck debugpy = spec(specs, "debug.python").probe().get();
        assertEquals(DoctorStatus.MISSING, debugpy.status());
        assertEquals(List.of(python), debugpy.tipArgs());
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path sandbox;

    /**
     * Editora's own Groovy server in a fresh config dir, with a receipt saying {@code version} (none when null),
     * and the server's command pointing into it. The launcher is a plain file marked executable: it is never run.
     */
    private FakeOps withEditorasGroovyServer(String name, String version) throws Exception {
        FakeOps ops = new FakeOps();
        ops.config = java.nio.file.Files.createDirectories(sandbox.resolve(name));
        java.nio.file.Path dir = java.nio.file.Files.createDirectories(ops.config.resolve("plugins/lsp/groovy"));
        java.nio.file.Path launcher = java.nio.file.Files.writeString(dir.resolve("gls-launcher"), "never run");
        launcher.toFile().setExecutable(true);
        if (version != null) {
            new com.editora.install.InstallReceipt("gls", version, null).write(dir);
        }
        ops.servers = List.of("groovy");
        ops.argv.put("groovy", List.of(launcher.toString()));
        return ops;
    }

    private static DoctorCheck groovyRow(FakeOps ops) throws Exception {
        FakeHost host = new FakeHost();
        host.config = ops.config;
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        List<DoctorService.CheckSpec> specs = FxTestSupport.callOnFx(doctor::buildSpecs);
        return spec(specs, "lsp.groovy").probe().get();
    }

    /** Editora's copy older than the pin warns, names both versions and offers Update. */
    @Test
    void anOutdatedCopyOfEditorasOffersUpdate() throws Exception {
        DoctorCheck row = groovyRow(withEditorasGroovyServer("old", "0.4.1"));

        assertEquals(DoctorStatus.WARN, row.status());
        assertTrue(row.update());
        assertEquals("doctor.tip.pinOutdated", row.tipKey());
        assertEquals(List.of("0.4.1", InstallCatalog.GLS_VERSION), row.tipArgs());
        assertEquals(DoctorCheck.Install.SERVER, row.install());
        assertTrue(row.detail().startsWith("0.4.1 — "), row.detail());
    }

    /** An install from before receipts whose files do not say which release it is also offers Update. */
    @Test
    void aCopyOfEditorasWithNoKnownVersionOffersUpdate() throws Exception {
        DoctorCheck row = groovyRow(withEditorasGroovyServer("unknown", null));

        assertEquals(DoctorStatus.WARN, row.status());
        assertTrue(row.update());
        assertEquals("doctor.tip.pinUnknown", row.tipKey());
    }

    /** The pinned version is simply OK, with the version shown. */
    @Test
    void theCopyOfThePinnedVersionIsOk() throws Exception {
        DoctorCheck row = groovyRow(withEditorasGroovyServer("current", InstallCatalog.GLS_VERSION));

        assertEquals(DoctorStatus.OK, row.status());
        assertFalse(row.update());
        assertTrue(row.detail().startsWith(InstallCatalog.GLS_VERSION + " — "), row.detail());
    }

    /** A server that is not Editora's own and says nothing of its version is left as it is. */
    @Test
    void aServerOutsideEditorasFolderIsNotJudged() throws Exception {
        FakeOps ops = withEditorasGroovyServer("elsewhere", "0.4.1");
        java.nio.file.Path own = java.nio.file.Files.writeString(sandbox.resolve("own-gls"), "never run");
        own.toFile().setExecutable(true);
        ops.argv.put("groovy", List.of(own.toString()));

        DoctorCheck row = groovyRow(ops);

        assertEquals(DoctorStatus.OK, row.status());
        assertFalse(row.update());
    }

    /**
     * Check for Newer Versions asks for the latest release of each working server installed at its latest (here a
     * global npm Pyright), checks again, and the row whose installed version is older offers Update; the status
     * line says how many. Only that row's probe runs for real; the lookup is answered by the test.
     */
    @Test
    void checkForNewerVersionsFindsAnOlderNpmServer() throws Exception {
        java.nio.file.Path pkg = java.nio.file.Files.createDirectories(sandbox.resolve("npm/lib/node_modules/pyright"));
        java.nio.file.Files.writeString(pkg.resolve("package.json"), "{\"name\":\"pyright\",\"version\":\"1.1.400\"}");
        java.nio.file.Path launcher = java.nio.file.Files.writeString(pkg.resolve("langserver.index.js"), "never run");
        launcher.toFile().setExecutable(true);
        FakeOps ops = new FakeOps();
        ops.servers = List.of("python");
        ops.argv.put("python", List.of(launcher.toString(), "--stdio"));
        FakeHost host = new FakeHost();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        doctor.probeOverrideForTest = spec -> spec.placeholder().id().equals("lsp.python")
                ? spec.probe().get()
                : spec.placeholder().optional(""); // nothing else is installed
        List<List<com.editora.install.LatestVersions.Source>> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        doctor.latestLookupForTest = sources -> {
            asked.add(sources);
            return new com.editora.install.LatestVersions.Found(java.util.Map.of("npm:pyright", "1.1.414"), 0);
        };
        FxTestSupport.runOnFx(doctor::runChecks);
        assertFalse(rowOf(doctor, "lsp.python").update(), "nothing is said to be newer before anyone asks");

        FxTestSupport.runOnFx(doctor::checkNewer);
        long deadline = System.currentTimeMillis() + 10_000;
        while (!rowOf(doctor, "lsp.python").update() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            FxTestSupport.drainFx();
        }

        DoctorCheck row = rowOf(doctor, "lsp.python");
        assertEquals(DoctorStatus.OK, row.status());
        assertTrue(row.update(), String.valueOf(row));
        assertEquals(List.of("1.1.400", "1.1.414"), row.tipArgs());
        assertEquals(
                List.of(List.of(new com.editora.install.LatestVersions.Source(
                        com.editora.install.LatestVersions.Registry.NPM, "pyright"))),
                asked,
                "only the working server installed at its latest is looked up");
        assertTrue(host.statuses.contains(tr("status.doctor.newerFound", 1L)), String.valueOf(host.statuses));

        FxTestSupport.runOnFx(doctor::runChecks);
        assertTrue(rowOf(doctor, "lsp.python").update(), "a plain Refresh keeps what the check found");
        assertEquals(1, asked.size(), "without asking the network again");
    }

    private static DoctorCheck rowOf(DoctorCoordinator doctor, String id) throws Exception {
        return FxTestSupport.callOnFx(() -> doctor.pane().currentChecks().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElseThrow());
    }

    /** Update on a language bundle's row reinstalls that row's tool only. */
    @Test
    void updateOnABundlesRowNamesItsOwnStep() {
        assertEquals("typescript-language-server", DoctorCoordinator.updateStepFor("lsp.typescript"));
        assertEquals("jdtls", DoctorCoordinator.updateStepFor("lsp.java"));
        assertEquals("js-debug", DoctorCoordinator.updateStepFor("debug.javascript"));
        assertEquals("mmdc", DoctorCoordinator.updateStepFor("mmdc"));
        assertNull(DoctorCoordinator.updateStepFor("lsp.json"));
    }

    /** Update routes to the installer as an update, which reinstalls although the server is found. */
    @Test
    void updateAsksTheInstallerToReplaceTheCopy() throws Exception {
        FakeOps ops = new FakeOps();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(new FakeHost(), ops));
        doctor.probeOverrideForTest = spec -> spec.placeholder().ok("");
        DoctorCheck outdated = DoctorCheck.checking("lsp.groovy", "lsp", "Groovy", "gls")
                .withInstall(DoctorCheck.Install.SERVER, "groovy")
                .outdated("0.4.1", "doctor.tip.pinOutdated", "0.4.1", "0.4.8");

        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(doctor, "install", new Class<?>[] {DoctorCheck.class}, outdated));

        assertEquals("groovy", ops.installedServer);
        assertTrue(ops.updateAsked);
    }

    private static DoctorService.CheckSpec spec(List<DoctorService.CheckSpec> specs, String id) {
        return specs.stream()
                .filter(s -> s.placeholder().id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static DoctorCheck placeholder(List<DoctorService.CheckSpec> specs, String id) {
        return spec(specs, id).placeholder();
    }

    @Test
    void probeOverrideResolvesEveryProbingRowSynchronously() throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        doctor.probeOverrideForTest = spec -> spec.placeholder().ok("v1.2.3");

        FxTestSupport.runOnFx(doctor::runChecks);
        List<DoctorCheck> rows = FxTestSupport.callOnFx(() -> doctor.pane().currentChecks());
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().noneMatch(r -> r.status() == DoctorStatus.CHECKING));
        DoctorCheck git =
                rows.stream().filter(r -> r.id().equals("git")).findFirst().orElseThrow();
        assertEquals(DoctorStatus.OK, git.status());
        assertEquals("v1.2.3", git.detail());
        // The per-server LSP rows came from the Ops-provided id list.
        assertTrue(rows.stream().anyMatch(r -> r.id().equals("lsp.json")));
        assertTrue(rows.stream().anyMatch(r -> r.id().equals("lsp.java")));
    }

    @Test
    void fixActionsRouteToOpsAndRerunTheChecks() throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        doctor.probeOverrideForTest = spec -> spec.placeholder().ok("");

        DoctorCheck missingServer = DoctorCheck.checking("lsp.json", "lsp", "JSON", "vscode-json-language-server")
                .withInstall(DoctorCheck.Install.SERVER, "json")
                .missing("doctor.tip.missing", "vscode-json-language-server");
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(doctor, "install", new Class<?>[] {DoctorCheck.class}, missingServer));
        assertEquals("json", ops.installedServer);
        // The install completion re-ran the checks (rows repopulated, all resolved via the override).
        List<DoctorCheck> rows = FxTestSupport.callOnFx(() -> doctor.pane().currentChecks());
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().noneMatch(r -> r.status() == DoctorStatus.CHECKING));

        DoctorCheck missingLang = DoctorCheck.checking("debug.java", "debug", "java-debug", "")
                .withInstall(DoctorCheck.Install.LANG, InstallCatalog.Lang.JAVA.name())
                .missing("doctor.tip.missing", "java-debug");
        FxTestSupport.runOnFx(
                () -> FxTestSupport.call(doctor, "install", new Class<?>[] {DoctorCheck.class}, missingLang));
        assertEquals(InstallCatalog.Lang.JAVA, ops.installedLang);

        DoctorPane.Actions actions = FxTestSupport.field(doctor.pane(), "actions");
        FxTestSupport.runOnFx(() -> actions.openSettings("git"));
        assertEquals("git", ops.openedSettingsKey);
    }
}
