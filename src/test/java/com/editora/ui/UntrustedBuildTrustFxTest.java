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
import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
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
 * The window side of the servers that run a folder's build to load it ({@code lsp.UntrustedBuilds}): a Java file in an
 * untrusted Maven project says jdtls runs without the build, a Rust file in an untrusted crate says rust-analyzer was
 * not started, both offer Trust Folder, and a trust decision restarts the servers it changes.
 */
@Tag("fx")
class UntrustedBuildTrustFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private Path project;
    private LspManager manager;
    private List<FakeLanguageServer> servers;
    private LspCoordinator coordinator;
    private XmlFolderTrust trust;
    private FakeHost host;
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
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(project.resolve("Cargo.toml"), "[package]\n");
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        servers = LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls", "rust", "rust-analyzer"));
        host = new FakeHost();
        FakeOps ops = new FakeOps();
        config = new ConfigManager(dir.resolve("config"));
        ops.config = config;
        ops.projectRoot = project;
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
            coordinator.setServerAvailableForTest("rust", true);
            trust = FxTestSupport.field(coordinator, "xmlTrust");
        });
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
    void aJavaFileWithoutItsBuildOffersTrustFolderAndTrustingRestartsTheServer() throws Exception {
        EditorBuffer java = open(project.resolve("src/main/java/App.java"), "class App {}\n");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(java));
        assertTrue(manager.isManaged(java.getPath()));
        LspTestHooks.startedUntrusted(manager, java.getPath(), true);
        int started = servers.size();

        FxTestSupport.runOnFx(() -> trust.afterSync(java));
        assertEquals(tr("lsp.trust.javaNotice"), FxTestSupport.callOnFx(java::fileNoticeText));
        assertEquals(project, FxTestSupport.callOnFx(() -> trust.target(java)), "the project is what gets trusted");

        List<Path> asked = new ArrayList<>();
        trust.confirmer = (owner, root) -> asked.add(root);
        FxTestSupport.runOnFx(() -> trust.trustFolder());

        assertEquals(List.of(project), asked);
        assertTrue(config.getTrustStore().isTrusted(project));
        assertEquals(started + 1, servers.size(), "jdtls started again, now with the build");
        assertTrue(manager.isManaged(java.getPath()));
        assertFalse(manager.buildNotLoaded(java.getPath()));
        assertNull(FxTestSupport.callOnFx(java::fileNoticeText), "the offer goes once it is done");
        assertEquals(tr("lsp.trust.trustedBuild", "app", coordinator.serverLabel("java")), host.lastStatus);
    }

    @Test
    void aJavaServerWithNoBuildToLoadOffersNothing() throws Exception {
        EditorBuffer java = open(project.resolve("Scratch.java"), "class Scratch {}\n");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(java));
        LspTestHooks.startedUntrusted(manager, java.getPath(), false);

        FxTestSupport.runOnFx(() -> trust.afterSync(java));
        assertNull(FxTestSupport.callOnFx(java::fileNoticeText));
    }

    @Test
    void aWithheldRustServerOffersTrustFolderUntilTheFolderIsTrusted() throws Exception {
        EditorBuffer rust = open(project.resolve("src/main.rs"), "fn main() {}\n");
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                coordinator, "onStartWithheld", new Class<?>[] {String.class, Path.class}, "rust", project));

        String notice = tr("lsp.trust.withheldNotice", coordinator.serverLabel("rust"));
        assertEquals(notice, FxTestSupport.callOnFx(rust::fileNoticeText));
        assertEquals(notice, host.lastStatus, "the status line says it too");
        assertFalse(manager.isManaged(rust.getPath()));

        config.getTrustStore().trust(project);
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings); // what the trust broadcast runs
        assertNull(FxTestSupport.callOnFx(rust::fileNoticeText));
    }

    @Test
    void revokingTrustRestartsAServerThatStartedTrusted() throws Exception {
        config.getTrustStore().trust(project);
        EditorBuffer java = open(project.resolve("src/main/java/App.java"), "class App {}\n");
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(java));
        int started = servers.size();

        FxTestSupport.runOnFx(coordinator::reloadProjectSettings);
        assertEquals(started, servers.size(), "trust unchanged: nothing restarts");

        config.getTrustStore().revoke(project);
        FxTestSupport.runOnFx(coordinator::reloadProjectSettings);
        assertEquals(started + 1, servers.size(), "started again, this time as an untrusted folder's");
        assertTrue(manager.isManaged(java.getPath()));
    }
}
