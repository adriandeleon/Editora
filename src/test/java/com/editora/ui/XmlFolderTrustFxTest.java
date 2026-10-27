package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Folder trust for the lemminx servers (XML/pom review 2): an untrusted pom never reaches lemminx-maven, which would
 * resolve and load what it names (E1, D11); its tab offers Trust Folder, and trusting moves it there. A blocked schema
 * download in an XML-only folder offers the same (C6).
 */
@Tag("fx")
class XmlFolderTrustFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private Path project;
    private LspManager manager;
    private LspCoordinator coordinator;
    private XmlFolderTrust trust;
    private FakeHost host;
    private FakeOps ops;
    private ConfigManager config;

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        EditorBuffer active;
        String lastStatus;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
        }
    }

    private static final class FakeOps extends LspOpsStub {
        ConfigManager config;
        Path projectRoot;
        final List<String> commands = new ArrayList<>();

        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public Path lspProjectRoot() {
            return projectRoot;
        }

        @Override
        public boolean lspFeatureEnabled() {
            return true;
        }

        @Override
        public void executeCommand(String id) {
            commands.add(id);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        project = Files.createDirectories(dir.resolve("app"));
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("xml", "lemminx", "maven-pom", "java -cp x Main"));
        host = new FakeHost();
        ops = new FakeOps();
        config = new ConfigManager(dir.resolve("config"));
        ops.config = config;
        ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("xml", true);
            coordinator.setServerAvailableForTest("maven-pom", true);
            trust = new XmlFolderTrust(coordinator, host, manager, ops);
        });
        // The project is already applied: the first sync would otherwise re-configure and re-probe every server.
        java.lang.reflect.Field applied = LspCoordinator.class.getDeclaredField("appliedProjectRoot");
        applied.setAccessible(true);
        applied.set(coordinator, project);
        java.lang.reflect.Field overrides = LspCoordinator.class.getDeclaredField("appliedProjectOverrides");
        overrides.setAccessible(true);
        overrides.set(
                coordinator,
                FxTestSupport.callOnFx(
                        () -> FxTestSupport.call(coordinator, "projectOverrideSignature", new Class<?>[0])));
    }

    @AfterEach
    void closeManager() throws Exception {
        FxTestSupport.runOnFx(manager::close);
    }

    private EditorBuffer open(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(file);
            b.setContent(text);
            host.buffers.add(b);
            host.active = b;
            return b;
        });
    }

    @Test
    void anUntrustedPomStaysOnPlainLemminxAndOffersTrustFolder() throws Exception {
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));

        assertEquals("xml", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(pom)));
        assertTrue(FxTestSupport.callOnFx(() -> coordinator.pomAwaitsTrust(pom)));
        assertFalse(manager.hasSession("maven-pom", project), "lemminx-maven is never started for it");
        assertEquals("xml", manager.managedServerId(pom.getPath()));
        assertEquals(tr("xml.trust.pomNotice"), FxTestSupport.callOnFx(pom::fileNoticeText));
    }

    @Test
    void trustingTheFolderMovesThePomToTheMavenServerAndRevokingMovesItBack() throws Exception {
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        List<Path> asked = new ArrayList<>();
        trust.confirmer = (owner, root) -> asked.add(root);

        FxTestSupport.runOnFx(() -> trust.trustFolder(null));

        assertEquals(List.of(project), asked, "the project the pom is in, after the prompt");
        assertTrue(config.getTrustStore().isTrusted(project));
        assertEquals("maven-pom", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(pom)));
        assertEquals("maven-pom", manager.managedServerId(pom.getPath()));
        assertFalse(FxTestSupport.callOnFx(() -> coordinator.pomAwaitsTrust(pom)));
        assertNull(FxTestSupport.callOnFx(pom::fileNoticeText), "the offer goes once it is done");

        config.getTrustStore().revoke(project);
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings); // what another window's revoke broadcasts
        assertEquals("xml", manager.managedServerId(pom.getPath()));
        assertFalse(manager.hasSession("maven-pom", project), "the Maven server on the folder is stopped");
    }

    @Test
    void decliningThePromptTrustsNothing() throws Exception {
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        trust.confirmer = (owner, root) -> false;

        FxTestSupport.runOnFx(() -> trust.trustFolder(null));

        assertFalse(config.getTrustStore().isTrusted(project));
        assertEquals("xml", manager.managedServerId(pom.getPath()));
    }

    @Test
    void aLoosePomIsTrustedByItsOwnFolderNotTheProject() throws Exception {
        Path elsewhere = dir.resolve("downloads/lib");
        EditorBuffer pom = open(elsewhere.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        assertEquals("xml", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(pom)));

        config.getTrustStore().trust(project);
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings);
        assertEquals("xml", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(pom)), "the project's trust");

        assertEquals(elsewhere, FxTestSupport.callOnFx(() -> trust.target(pom)));
    }

    @Test
    void theMavenServerIsDisabledSoNothingIsOffered() throws Exception {
        host.settings.setMavenPomLspEnabled(false);
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        assertFalse(FxTestSupport.callOnFx(() -> coordinator.pomAwaitsTrust(pom)));
        assertNull(FxTestSupport.callOnFx(pom::fileNoticeText));
    }

    @Test
    void aBlockedDownloadInAnUntrustedFolderOffersTrustFolderOnTheFile() throws Exception {
        EditorBuffer beans = open(project.resolve("beans.xml"), "<beans/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(beans));
        List<LspDiagnostic> blocked = List.of(diagnostic(XmlServerNotices.DOWNLOAD_DISABLED));

        FxTestSupport.runOnFx(() -> trust.onDiagnostics(beans, List.of(diagnostic("cvc-elt.1.a"))));
        assertNull(FxTestSupport.callOnFx(beans::fileNoticeText), "only a blocked download");

        FxTestSupport.runOnFx(() -> trust.onDiagnostics(beans, blocked));
        assertEquals(tr("xml.trust.downloadNotice"), FxTestSupport.callOnFx(beans::fileNoticeText));
    }

    @Test
    void aBlockedDownloadOffersNothingWhereTrustWouldNotHelp() throws Exception {
        List<LspDiagnostic> blocked = List.of(diagnostic(XmlServerNotices.DOWNLOAD_DISABLED));
        EditorBuffer beans = open(project.resolve("beans.xml"), "<beans/>");

        host.settings.setXmlSchemaDownloads("never");
        FxTestSupport.runOnFx(() -> trust.onDiagnostics(beans, blocked));
        assertNull(FxTestSupport.callOnFx(beans::fileNoticeText), "downloads are off everywhere");

        host.settings.setXmlSchemaDownloads("trusted");
        config.getTrustStore().trust(project);
        FxTestSupport.runOnFx(() -> trust.onDiagnostics(beans, blocked));
        assertNull(FxTestSupport.callOnFx(beans::fileNoticeText), "already trusted");
    }

    @Test
    void trustFolderSaysSoWhenThereIsNothingOrItIsAlreadyTrusted() throws Exception {
        ops.projectRoot = null;
        FxTestSupport.runOnFx(() -> trust.trustFolder(null));
        assertEquals(tr("xml.trust.noFolder"), host.lastStatus);

        ops.projectRoot = project;
        config.getTrustStore().trust(project);
        FxTestSupport.runOnFx(() -> trust.trustFolder(null));
        assertEquals(tr("xml.trust.already", "app"), host.lastStatus);
    }

    @Test
    void theNoticeButtonRunsTheCommandThatTellsEveryWindow() throws Exception {
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        javafx.scene.control.Button action = FxTestSupport.callOnFx(() -> {
            Object bar = FxTestSupport.field(pom, "fileNotice");
            return (javafx.scene.control.Button) FxTestSupport.field(bar, "action");
        });
        FxTestSupport.runOnFx(action::fire);
        assertEquals(List.of(XmlFolderTrust.COMMAND), ops.commands);
    }

    @Test
    void thePromptSaysWhatTrustAllowsAndCancelIsItsDefault() throws Exception {
        TrustPrompts.Prompt prompt = FxTestSupport.callOnFx(() -> TrustPrompts.folder(null, project));
        javafx.scene.control.Button cancel = FxTestSupport.callOnFx(() -> (javafx.scene.control.Button)
                prompt.alert().getDialogPane().lookupButton(javafx.scene.control.ButtonType.CANCEL));
        javafx.scene.control.Button accept = FxTestSupport.callOnFx(() ->
                (javafx.scene.control.Button) prompt.alert().getDialogPane().lookupButton(prompt.trust()));
        assertTrue(cancel.isDefaultButton());
        assertFalse(accept.isDefaultButton(), "a stray Enter never trusts a folder");
        String body = prompt.alert().getContentText();
        assertTrue(body.contains(project.toString()), body);
        assertTrue(body.contains("lemminx-maven"), body);
    }

    @Test
    void theTargetIsTheProjectElseTheServersOwnFolderButNeverTheLooseRoot() {
        Path p = Path.of("/w/app");
        Path loose = Path.of("/cfg/lsp/loose-files");
        assertEquals(p, XmlFolderTrust.trustTarget(p, p.resolve("a/pom.xml"), p.resolve("a"), loose));
        assertEquals(
                Path.of("/x/lib"), XmlFolderTrust.trustTarget(p, Path.of("/x/lib/pom.xml"), Path.of("/x/lib"), loose));
        assertEquals(p, XmlFolderTrust.trustTarget(p, Path.of("/x/a.xml"), loose, loose), "falls back to the project");
        assertNull(XmlFolderTrust.trustTarget(null, Path.of("/x/a.xml"), loose, loose));
        assertNull(XmlFolderTrust.trustTarget(null, null, null, loose));
    }

    private static LspDiagnostic diagnostic(String code) {
        return new LspDiagnostic(1, 0, 1, 5, LspDiagnostic.Severity.ERROR, "msg", code, "xml", null, false, false);
    }
}
