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
        java.nio.file.@org.jspecify.annotations.Nullable Path configDir;

        @Override
        public java.nio.file.@org.jspecify.annotations.Nullable Path configDir() {
            return configDir;
        }

        @Override
        public Settings settings() {
            return settings;
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

        @org.jspecify.annotations.Nullable
        List<String> javaArgv;

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
            return List.of("java", "json");
        }

        @Override
        public boolean lspServerEnabled(String serverId) {
            return true;
        }

        @Override
        public List<String> lspServerArgv(String serverId) {
            return javaArgv != null && serverId.equals("java") ? javaArgv : List.of(serverId + "-server");
        }

        @Override
        public void installServer(String serverId, Consumer<Boolean> onDone) {
            installedServer = serverId;
            onDone.accept(true);
        }

        @Override
        public void installLang(InstallCatalog.Lang lang, Consumer<Boolean> onDone) {
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
    void anOutdatedJdtlsOffersInstallOnlyWhenItIsEditorasOwn(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        FakeHost host = new FakeHost();
        host.configDir = dir.resolve("config");
        FakeOps ops = new FakeOps();
        ops.javaArgv = List.of(jdtls(host.configDir.resolve("plugins/lsp/java")).toString());
        DoctorCheck own = javaRow(host, ops);
        assertEquals(DoctorStatus.WARN, own.status());
        assertEquals("doctor.tip.jdtlsOutdated", own.tipKey());
        assertTrue(own.offersInstall(), "the tip says Click Install…, so the row has it (CN4)");

        ops.javaArgv = List.of(jdtls(dir.resolve("opt/jdtls")).toString());
        DoctorCheck elsewhere = javaRow(host, ops);
        assertEquals(DoctorStatus.WARN, elsewhere.status());
        assertEquals("doctor.tip.jdtlsOutdatedElsewhere", elsewhere.tipKey());
        assertFalse(elsewhere.offersInstall(), "Install… replaces only the copy Editora installed");
    }

    private static java.nio.file.Path jdtls(java.nio.file.Path home) throws Exception {
        java.nio.file.Files.createFile(java.nio.file.Files.createDirectories(home.resolve("plugins"))
                .resolve("org.eclipse.jdt.ls.core_1.61.0.202607301809.jar"));
        java.nio.file.Path launcher = java.nio.file.Files.createFile(
                java.nio.file.Files.createDirectories(home.resolve("bin")).resolve("jdtls"));
        assertTrue(launcher.toFile().setExecutable(true));
        return launcher;
    }

    private static DoctorCheck javaRow(FakeHost host, FakeOps ops) throws Exception {
        DoctorCoordinator doctor = FxTestSupport.callOnFx(() -> new DoctorCoordinator(host, ops));
        FxTestSupport.runOnFx(doctor::runChecks);
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            DoctorCheck java = FxTestSupport.callOnFx(() -> doctor.pane().currentChecks()).stream()
                    .filter(r -> r.id().equals("lsp.java"))
                    .findFirst()
                    .orElseThrow();
            if (java.status() != DoctorStatus.CHECKING || System.currentTimeMillis() > deadline) {
                return java;
            }
            Thread.sleep(20);
        }
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
