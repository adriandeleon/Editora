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
        InstallCatalog.Lang installedLang;
        boolean installedTypstCli;
        String openedSettingsKey;

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
            return true;
        }

        @Override
        public boolean debugFeatureEnabled() {
            return false;
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
            return List.of(serverId + "-server");
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
        for (String disabledId : List.of("mmdc", "dot", "typst")) {
            DoctorService.CheckSpec spec = specs.stream()
                    .filter(s -> s.placeholder().id().equals(disabledId))
                    .findFirst()
                    .orElseThrow();
            assertEquals(DoctorStatus.DISABLED, spec.placeholder().status());
            assertNull(spec.probe(), disabledId + " must not spawn a probe while its feature is off");
        }
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
