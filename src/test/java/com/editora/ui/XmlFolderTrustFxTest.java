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
            trust = FxTestSupport.field(coordinator, "xmlTrust"); // the window's own: its notices' button
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
        assertTrue(FxTestSupport.callOnFx(() -> coordinator.pomWithheldUntrusted(pom)));
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

        FxTestSupport.runOnFx(() -> trust.trustFolder());

        assertEquals(List.of(project), asked, "the project the pom is in, after the prompt");
        assertTrue(config.getTrustStore().isTrusted(project));
        assertEquals("maven-pom", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(pom)));
        assertEquals("maven-pom", manager.managedServerId(pom.getPath()));
        assertFalse(FxTestSupport.callOnFx(() -> coordinator.pomWithheldUntrusted(pom)));
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

        FxTestSupport.runOnFx(() -> trust.trustFolder());

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
        assertFalse(FxTestSupport.callOnFx(() -> coordinator.pomWithheldUntrusted(pom)));
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
        FxTestSupport.runOnFx(() -> trust.trustFolder());
        assertEquals(tr("xml.trust.noFolder"), host.lastStatus);

        ops.projectRoot = project;
        config.getTrustStore().trust(project);
        FxTestSupport.runOnFx(() -> trust.trustFolder());
        assertEquals(tr("xml.trust.already", "app"), host.lastStatus);
    }

    @Test
    void theNoticeButtonTrustsAndTellsEveryWindow() throws Exception {
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        List<Path> asked = new ArrayList<>();
        trust.confirmer = (owner, root) -> asked.add(root);
        List<String> told = new ArrayList<>();
        trust.broadcast = () -> told.add("broadcast");
        javafx.scene.control.Button action = FxTestSupport.callOnFx(() -> {
            Object bar = FxTestSupport.field(pom, "fileNotice");
            return (javafx.scene.control.Button) FxTestSupport.field(bar, "action");
        });
        FxTestSupport.runOnFx(action::fire);
        assertEquals(List.of(project), asked);
        assertEquals(List.of("broadcast"), told);
        assertTrue(config.getTrustStore().isTrusted(project));
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
        // a file outside the project never stands for the project (XML/pom review 3, A24/C23)
        assertNull(XmlFolderTrust.trustTarget(p, Path.of("/x/a.xml"), loose, loose));
        assertNull(XmlFolderTrust.trustTarget(p, Path.of("/x/a.java"), null, loose));
        assertEquals(p, XmlFolderTrust.trustTarget(p, null, null, loose), "no file: the project");
        assertNull(XmlFolderTrust.trustTarget(null, Path.of("/x/a.xml"), loose, loose));
        assertNull(XmlFolderTrust.trustTarget(null, null, null, loose));
    }

    /** XML/pom review 3, A24/C23: Trust This Folder on a loose file says there is no folder, and trusts nothing. */
    @Test
    void trustFolderOnAFileOutsideTheProjectTrustsNothing() throws Exception {
        manager.setLooseRoot(dir.resolve("config/lsp/loose-files"));
        EditorBuffer feed = open(dir.resolve("downloads/feed.xml"), "<feed/>");
        List<Path> asked = new ArrayList<>();
        trust.confirmer = (owner, root) -> asked.add(root);

        FxTestSupport.runOnFx(() -> trust.trustFolder());

        assertEquals(List.of(), asked, "no prompt for the project");
        assertFalse(config.getTrustStore().isTrusted(project));
        assertEquals(tr("xml.trust.noFolder"), host.lastStatus);
        assertNull(FxTestSupport.callOnFx(() -> trust.target(feed)));
    }

    /**
     * XML/pom review 3, A27: a trust change reroutes every pom a server holds at once, the background ones too; a
     * revoked one goes back to plain lemminx (not to no server at all). A tab never shown stays deferred.
     */
    @Test
    void aTrustChangeReroutesBackgroundPomsAtOnce() throws Exception {
        EditorBuffer parent =
                open(project.resolve("pom.xml"), "<project><modules><module>core</module></modules></project>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(parent));
        EditorBuffer core = open(project.resolve("core/pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(core));
        EditorBuffer neverShown = open(project.resolve("other/pom.xml"), "<project/>");
        host.active = core; // parent and neverShown are background tabs
        assertEquals("xml", manager.managedServerId(parent.getPath()));

        config.getTrustStore().trust(project);
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings);
        assertEquals("maven-pom", manager.managedServerId(parent.getPath()), "the background pom moved too");
        assertEquals("maven-pom", manager.managedServerId(core.getPath()));
        assertNull(manager.managedServerId(neverShown.getPath()), "a tab never shown is not started");

        config.getTrustStore().revoke(project);
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings);
        assertEquals("xml", manager.managedServerId(parent.getPath()), "back on plain lemminx, with diagnostics");
        assertEquals("xml", manager.managedServerId(core.getPath()));
        assertFalse(manager.hasSession("maven-pom", project));
    }

    /**
     * XML/pom review 3, A28: without the Maven server an untrusted pom is not asked to trust its folder "for
     * Maven-aware editing" (the install banner says both); once it is installed, the trust notice is offered.
     */
    @Test
    void withoutTheMavenServerAnUntrustedPomGetsNoTrustNotice() throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("maven-pom", false));
        EditorBuffer pom = open(project.resolve("pom.xml"), "<project/>");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(pom));
        assertEquals("xml", manager.managedServerId(pom.getPath()));
        assertTrue(FxTestSupport.callOnFx(() -> coordinator.pomWithheldUntrusted(pom)), "the banner's wording");
        assertNull(FxTestSupport.callOnFx(pom::fileNoticeText), "no trust notice for a server not installed");

        FxTestSupport.runOnFx(() -> coordinator.serverDetected("maven-pom", true)); // installed
        assertEquals(tr("xml.trust.pomNotice"), FxTestSupport.callOnFx(pom::fileNoticeText));
    }

    @Test
    void trustingWithoutTheMavenServerClaimsOnlySchemaDownloads() throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("maven-pom", false));
        EditorBuffer beans = open(project.resolve("beans.xml"), "<beans/>");
        trust.confirmer = (owner, root) -> true;
        FxTestSupport.runOnFx(() -> trust.trustFolder());
        assertEquals(tr("xml.trust.trustedDownloads", "app"), host.lastStatus);
        assertTrue(config.getTrustStore().isTrusted(project));
        assertEquals(project, FxTestSupport.callOnFx(() -> trust.target(beans)));
    }

    /** XML/pom review 3, C25: the blocked-download status names only what would let the download through. */
    @Test
    void theBlockedDownloadStatusNamesWhatWouldHelpThere() throws Exception {
        manager.setLooseRoot(dir.resolve("config/lsp/loose-files"));
        EditorBuffer beans = open(project.resolve("beans.xml"), "<beans/>");
        EditorBuffer feed = open(dir.resolve("downloads/feed.xml"), "<feed/>");

        assertEquals(
                tr("status.lsp.xmlDownloadsOff", tr("command.workspace.trustFolder")),
                FxTestSupport.callOnFx(() -> trust.downloadsOffStatus(beans)));
        assertEquals(tr("status.lsp.xmlDownloadsLoose"), FxTestSupport.callOnFx(() -> trust.downloadsOffStatus(feed)));
        host.settings.setXmlSchemaDownloads("never");
        assertEquals(tr("status.lsp.xmlDownloadsNever"), FxTestSupport.callOnFx(() -> trust.downloadsOffStatus(beans)));
        assertEquals(tr("status.lsp.xmlDownloadsNever"), FxTestSupport.callOnFx(() -> trust.downloadsOffStatus(feed)));
    }

    private static LspDiagnostic diagnostic(String code) {
        return new LspDiagnostic(1, 0, 1, 5, LspDiagnostic.Severity.ERROR, "msg", code, "xml", null, false, false);
    }
}
