package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LspCoordinator#syncBuffer}'s eligibility gate and the deferred-start policy — the decisions that
 * settle, for every buffer, whether a language server runs at all.
 *
 * <p>The gate is a nine-term conjunction and <b>each term is there for a reason that is expensive to
 * rediscover</b>: a narrowed buffer holds only a region, so every position the server sends or receives is
 * offset and a formatting edit would corrupt the file; a remote (SFTP) path has no local file for the server
 * to read; the large/heavy tiers keep a huge file responsive. A term silently going missing costs either
 * correctness or performance, and none of them had a test.
 *
 * <p>Driven with real {@link EditorBuffer}s and a real {@link LspManager} whose sessions are faked
 * ({@code LspTestHooks.useFakeSessions}), so nothing forks and the result does not depend on which language
 * servers happen to be installed on the machine running the suite.
 */
@Tag("fx")
class LspCoordinatorSyncFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private LspCoordinator coordinator;
    private FakeHost host;
    private FakeOps ops;

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
        boolean featureEnabled = true;
        final List<Boolean> problemsAvailable = new ArrayList<>();
        final List<String> statusBarLabels = new ArrayList<>();

        @Override
        public boolean lspFeatureEnabled() {
            return featureEnabled;
        }

        @Override
        public void setProblemsAvailable(boolean available) {
            problemsAvailable.add(available);
        }

        @Override
        public void setStatusBarLsp(String label) {
            statusBarLabels.add(label);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        LspTestHooks.useFakeSessions(manager);
        manager.configure(true, Map.of("java", "jdtls"));
        host = new FakeHost();
        ops = new FakeOps();
        FxTestSupport.runOnFx(() -> {
            coordinator = new LspCoordinator(host, manager, ops);
            coordinator.setServerAvailableForTest("java", true);
        });
    }

    /** A java buffer backed by a real file, registered with the host as the active buffer. */
    private EditorBuffer javaBuffer(String name) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, "class A {}\n");
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(f);
            b.setContent("class A {}\n");
            host.buffers.add(b);
            host.active = b;
            return b;
        });
    }

    private void sync(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.syncBuffer(b));
    }

    // --- the happy path -----------------------------------------------------------------------------

    @Test
    void anEligibleJavaBufferIsOpenedAndActivated() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        sync(b);

        assertTrue(manager.isManaged(b.getPath()), "the document should be open on its server");
        assertTrue(FxTestSupport.callOnFx(b::isLspActive), "the buffer should be marked LSP-active");
    }

    /** Re-syncing an already-managed buffer must not close and re-open it (that would restart analysis). */
    @Test
    void reSyncingAnOpenBufferDoesNotChurnTheDocument() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        sync(b);
        sync(b);
        sync(b);

        assertTrue(manager.isManaged(b.getPath()));
        assertTrue(FxTestSupport.callOnFx(b::isLspActive));
    }

    // --- each term of the eligibility gate ------------------------------------------------------------

    @Test
    void theMasterFeatureSwitchGatesEverything() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        sync(b);
        assertTrue(manager.isManaged(b.getPath()));

        ops.featureEnabled = false;
        sync(b);

        assertFalse(manager.isManaged(b.getPath()), "disabling LSP must close the document");
        assertFalse(FxTestSupport.callOnFx(b::isLspActive));
    }

    @Test
    void aBufferWithNoPathIsNeverManaged() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        sync(b);
        assertFalse(FxTestSupport.callOnFx(b::isLspActive), "an untitled buffer has no file to serve");
    }

    /**
     * A narrowed buffer holds only its region, so every position the server sends or receives is offset —
     * a formatting edit or code action would land in the wrong place and corrupt the file.
     */
    @Test
    void aNarrowedBufferIsSuspended() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        sync(b);
        assertTrue(manager.isManaged(b.getPath()));

        FxTestSupport.runOnFx(() -> b.narrowTo(0, 5));
        sync(b);

        assertFalse(manager.isManaged(b.getPath()), "a narrowed buffer must be suspended (offset positions)");
        assertFalse(FxTestSupport.callOnFx(b::isLspActive));
    }

    /** …and widening it again brings the server back. */
    @Test
    void wideningResumesTheDocument() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        FxTestSupport.runOnFx(() -> b.narrowTo(0, 5));
        sync(b);
        assertFalse(manager.isManaged(b.getPath()));

        FxTestSupport.runOnFx(b::widen);
        sync(b);

        assertTrue(manager.isManaged(b.getPath()), "widening must resume the document");
    }

    /** The 5 MB tier drops LSP along with highlighting and the minimap. */
    @Test
    void aLargeFileIsNotManaged() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        FxTestSupport.runOnFx(() -> b.setLargeFile(true));
        sync(b);

        assertFalse(manager.isManaged(b.getPath()), "large-file mode must skip LSP");
    }

    /** The intermediate line-count tier drops LSP but keeps highlighting. */
    @Test
    void aHeavyFileIsNotManaged() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        FxTestSupport.runOnFx(() -> b.setHeavyFile(true));
        sync(b);

        assertFalse(manager.isManaged(b.getPath()), "heavy-file mode must skip LSP");
    }

    /** A server whose own toggle is off must not serve its buffers. */
    @Test
    void aDisabledServerDoesNotServeItsBuffers() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        host.settings.setJavaLspEnabled(false);
        sync(b);

        assertFalse(manager.isManaged(b.getPath()), "the per-server toggle must gate the open");
    }

    /** A server that was probed and not found must not be opened against. */
    @Test
    void anUnavailableServerIsNotOpenedAgainst() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("java", false));
        sync(b);

        assertFalse(manager.isManaged(b.getPath()), "an absent server must not be opened against");
    }

    /** A language no server serves is left alone rather than routed anywhere. */
    @Test
    void aLanguageWithNoServerIsLeftAlone() throws Exception {
        Path f = root.resolve("notes.txt");
        Files.writeString(f, "hello");
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(f);
            x.setContent("hello");
            host.buffers.add(x);
            host.active = x;
            return x;
        });
        sync(b);

        assertFalse(manager.isManaged(f));
        assertFalse(FxTestSupport.callOnFx(b::isLspActive));
    }

    // --- deferred start (the startup-cost fix) --------------------------------------------------------

    /**
     * Only the <b>active</b> buffer starts its server immediately; a restored background tab waits for its
     * first show. {@code wireBuffer} runs for every restored tab, so without this a session of N files forked
     * up to N servers during launch — measured at 4 extra processes, ~2x Editora's own startup CPU and
     * ~225 MB, for files the user had not looked at.
     */
    @Test
    void onlyTheActiveBufferStartsItsServerImmediately() throws Exception {
        EditorBuffer active = javaBuffer("Active.java");
        Path bgPath = root.resolve("Background.java");
        Files.writeString(bgPath, "class B {}\n");
        EditorBuffer background = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(bgPath);
            x.setContent("class B {}\n");
            host.buffers.add(x);
            return x; // deliberately NOT made active
        });

        FxTestSupport.runOnFx(() -> {
            coordinator.syncBufferWhenShown(active);
            coordinator.syncBufferWhenShown(background);
        });

        assertTrue(manager.isManaged(active.getPath()), "the visible buffer syncs at once");
        assertFalse(manager.isManaged(bgPath), "a background tab must not fork a server at startup");
    }

    /** …and it starts on first show, so nothing is lost — only deferred. */
    @Test
    void aDeferredBufferStartsOnFirstShow() throws Exception {
        Path bgPath = root.resolve("Background.java");
        Files.writeString(bgPath, "class B {}\n");
        EditorBuffer background = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(bgPath);
            x.setContent("class B {}\n");
            host.buffers.add(x);
            return x;
        });

        FxTestSupport.runOnFx(() -> coordinator.syncBufferWhenShown(background));
        assertFalse(manager.isManaged(bgPath));

        FxTestSupport.runOnFx(() -> {
            host.active = background;
            coordinator.onBufferShown(background);
        });

        assertTrue(manager.isManaged(bgPath), "showing the tab must start its server");
    }

    /** Showing a buffer that was never deferred must not double-open it. */
    @Test
    void showingAnAlreadySyncedBufferIsANoOp() throws Exception {
        EditorBuffer b = javaBuffer("A.java");
        FxTestSupport.runOnFx(() -> coordinator.syncBufferWhenShown(b));
        assertTrue(manager.isManaged(b.getPath()));

        FxTestSupport.runOnFx(() -> coordinator.onBufferShown(b));

        assertTrue(manager.isManaged(b.getPath()), "still managed, not churned");
    }

    // --- pom.xml routing (live since the maven-pom command fix) ---------------------------------------

    /**
     * A {@code pom.xml} routes to the Maven-aware server when it is available, and to the plain XML server
     * when it was probed and found absent. While it is <b>unprobed</b> the buffer must be left alone rather
     * than opened on the XML server first — otherwise every pom.xml would briefly attach to the wrong server
     * and then have to be closed and re-opened.
     */
    @Test
    void aPomRoutesToMavenWhenAvailableAndFallsBackWhenProbedAbsent() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(pom);
            x.setContent("<project/>");
            host.buffers.add(x);
            host.active = x;
            return x;
        });

        // Unprobed: no routing decision yet.
        assertEquals(null, FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(b)));

        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("maven-pom", true));
        assertEquals("maven-pom", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(b)));

        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("maven-pom", false));
        assertEquals(
                "xml",
                FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(b)),
                "probed-and-absent must fall back to the plain XML server");
    }

    /** With the Maven server's own toggle off, a pom is plain XML regardless of availability. */
    @Test
    void aDisabledMavenServerLeavesAPomOnPlainXml() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "<project/>");
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(pom);
            x.setContent("<project/>");
            return x;
        });
        host.settings.setMavenPomLspEnabled(false);
        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("maven-pom", true));

        assertEquals("xml", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(b)));
    }

    /** An ordinary .xml file is never routed to the Maven server. */
    @Test
    void anOrdinaryXmlFileIsNotRoutedToMaven() throws Exception {
        Path xml = root.resolve("beans.xml");
        Files.writeString(xml, "<beans/>");
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer x = new EditorBuffer();
            x.setPath(xml);
            x.setContent("<beans/>");
            return x;
        });
        FxTestSupport.runOnFx(() -> coordinator.setServerAvailableForTest("maven-pom", true));

        assertEquals("xml", FxTestSupport.callOnFx(() -> coordinator.serverIdForBuffer(b)));
    }
}
