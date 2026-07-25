package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.editor.LspDiagnostic;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LspManager}'s routing and lifecycle layer, driven through the {@code setSessionStarterForTest} seam
 * so no language server is ever forked. Tagged {@code fx} because the manager marshals its diagnostics
 * callback with {@code Platform.runLater}, which needs a booted toolkit.
 *
 * <p>This layer was 17%-covered: the 32 existing {@code LspManagerTest} cases all target pure statics, so the
 * map bookkeeping several fixes actually turn on had no test at all — per-server shutdown (whose prefix scan
 * once silently matched nothing), the command-change teardown (without which a stale session is handed back
 * while Settings reports the new command applied), open→managed→close routing, and diagnostics dispatch.
 */
@Tag("fx")
class LspManagerLifecycleFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage(); // built-in Headless Glass platform (-Dglass.platform=Headless)
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();
    private final List<LanguageServerSession> sessions = new CopyOnWriteArrayList<>();
    private final List<Path> diagnosticFiles = new CopyOnWriteArrayList<>();
    private final List<List<LspDiagnostic>> diagnosticLists = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        fakes.clear();
        sessions.clear();
        diagnosticFiles.clear();
        diagnosticLists.clear();
        manager = new LspManager(
                (file, diags) -> {
                    diagnosticFiles.add(file);
                    diagnosticLists.add(diags);
                },
                (type, msg) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            sessions.add(session);
            session.attachForTest(fake, new ServerCapabilities());
        });
        manager.configure(true, Map.of("java", "jdtls"));
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private Path javaFile(String name) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, "class X {}\n");
        return f;
    }

    /** Blocks until everything already queued on the FX thread has run (runLater is FIFO). */
    private static void drainFx() throws Exception {
        var latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FX queue did not drain");
    }

    // --- open → managed → close ---------------------------------------------------------------------

    @Test
    void openingADocumentMakesItManagedByItsServer() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        assertTrue(manager.isManaged(f), "the document should be routed to a session");
        assertEquals("java", manager.managedServerId(f));
        assertEquals(1, fakes.size(), "exactly one session should have been created");
        assertEquals(1, fakes.get(0).opened.size(), "didOpen should have reached the server");
    }

    @Test
    void closingADocumentUnmanagesIt() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        manager.closeDocument(f);

        assertFalse(manager.isManaged(f));
        assertNull(manager.managedServerId(f), "a closed document has no managing server");
        assertEquals(1, fakes.get(0).closed.size(), "didClose should have reached the server");
    }

    /** Two files under one root share a single session — that is the point of keying by (server, root). */
    @Test
    void twoFilesUnderOneRootShareOneSession() throws Exception {
        Path a = javaFile("A.java");
        Path b = javaFile("B.java");
        manager.openDocument(a, root, "java", "class A {}");
        manager.openDocument(b, root, "java", "class B {}");

        assertEquals(1, fakes.size(), "a second file under the same root must reuse the session");
        assertEquals(2, fakes.get(0).opened.size(), "both documents should be open on it");
    }

    @Test
    void changeAndSaveReachTheServerForAManagedDocument() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        manager.changeDocument(f, "class A { int x; }");
        manager.saveDocument(f);

        assertEquals(1, fakes.get(0).changed.size());
        assertEquals(1, fakes.get(0).saved.size());
    }

    /** An unmanaged file must not reach any server — and must not blow up either. */
    @Test
    void changingAnUnmanagedDocumentIsANoOp() throws Exception {
        Path f = javaFile("A.java");
        manager.changeDocument(f, "whatever");
        manager.saveDocument(f);
        assertTrue(fakes.isEmpty(), "no session should be created by a change to an unopened document");
    }

    /** The feature flag gates the open path outright. */
    @Test
    void openingIsRefusedWhileTheFeatureIsDisabled() throws Exception {
        manager.configure(false, Map.of("java", "jdtls"));
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        assertFalse(manager.isManaged(f));
        assertTrue(fakes.isEmpty());
    }

    /** An unknown language has no server, so nothing is routed. */
    @Test
    void anUnsupportedLanguageIsNotRouted() throws Exception {
        Path f = root.resolve("notes.unknownext");
        Files.writeString(f, "hi");
        manager.openDocument(f, root, "no-such-language", "hi");

        assertFalse(manager.isManaged(f));
        assertTrue(fakes.isEmpty());
    }

    // --- per-server shutdown (the prefix-scan bug class) ---------------------------------------------

    /**
     * {@code shutdownServer} finds a server's sessions by scanning session keys for its prefix. That scan
     * once used a different separator than {@code sessionKey}, so it matched nothing at all — silently doing
     * nothing while looking correct.
     */
    @Test
    void shuttingDownAServerUnmanagesItsDocuments() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        assertTrue(manager.isManaged(f));

        manager.shutdownServer("java");

        assertFalse(manager.isManaged(f), "shutdownServer must actually drop the session's documents");
        assertNull(manager.managedServerId(f));
    }

    @Test
    void shuttingDownAnUnrelatedServerLeavesTheSessionAlone() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        manager.shutdownServer("python");

        assertTrue(manager.isManaged(f), "another server's shutdown must not touch this session");
    }

    @Test
    void shutdownAllClearsEverything() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        manager.shutdownAll();

        assertFalse(manager.isManaged(f));
    }

    /** Reopening after a shutdown starts a fresh session rather than handing back the dead one. */
    @Test
    void reopeningAfterShutdownStartsAFreshSession() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        manager.shutdownServer("java");
        manager.openDocument(f, root, "java", "class A {}");

        assertTrue(manager.isManaged(f));
        assertEquals(2, fakes.size(), "a second session should have been created");
    }

    // --- changing a server's command must not hand back the old session ------------------------------

    /**
     * The session key is (serverId, root) — the command is deliberately not part of it — so a changed command
     * must dispose the running session, or the stale one is handed straight back: the new command never runs
     * and the old process leaks, while the Settings row re-probes and turns green as if it applied.
     */
    @Test
    void changingAServersCommandDropsItsRunningSession() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        assertTrue(manager.isManaged(f));

        manager.configure(true, Map.of("java", "/opt/other/jdtls"));

        assertFalse(manager.isManaged(f), "the session running the OLD command must be dropped");
    }

    /** Re-applying the same command must NOT churn the session (settings are applied constantly). */
    @Test
    void reapplyingTheSameCommandKeepsTheSession() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        manager.configure(true, Map.of("java", "jdtls"));
        manager.configure(true, Map.of("java", "jdtls"));

        assertTrue(manager.isManaged(f), "an unchanged command must not restart the server");
        assertEquals(1, fakes.size(), "no extra session should have been created");
    }

    // --- diagnostics routing -------------------------------------------------------------------------

    @Test
    void publishedDiagnosticsReachTheCallbackForAnOpenDocument() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        sessions.get(0)
                .publishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsParams(
                        f.toUri().toString(), List.of(diagnostic("boom"))));
        drainFx();

        assertFalse(diagnosticFiles.isEmpty(), "the diagnostics callback should have fired");
        assertEquals(f, diagnosticFiles.get(diagnosticFiles.size() - 1));
        assertEquals(
                "boom", diagnosticLists.get(diagnosticLists.size() - 1).get(0).message());
    }

    /** A jdt:// (non-file) URI has no path and must be dropped rather than crash the publish path. */
    @Test
    void aNonFileUriDiagnosticIsDropped() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        int before = diagnosticFiles.size();

        sessions.get(0)
                .publishDiagnostics(new org.eclipse.lsp4j.PublishDiagnosticsParams(
                        "jdt://contents/rt.jar/java.lang/String.class", List.of(diagnostic("lib"))));
        drainFx();

        assertEquals(before, diagnosticFiles.size(), "a non-file URI must not reach the callback");
    }

    /** A disposed session must not keep publishing into the UI. */
    @Test
    void aDisposedSessionStopsPublishingDiagnostics() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        LanguageServerSession s = sessions.get(0);
        manager.shutdownAll();
        int before = diagnosticFiles.size();

        s.publishDiagnostics(
                new org.eclipse.lsp4j.PublishDiagnosticsParams(f.toUri().toString(), List.of(diagnostic("late"))));
        drainFx();

        assertEquals(before, diagnosticFiles.size(), "a disposed session must go quiet");
    }

    // --- capability accessors are null-safe ----------------------------------------------------------

    /** Every {@code supportsX} is asked about arbitrary buffers, including unmanaged ones. */
    @Test
    void capabilityQueriesOnAnUnmanagedFileAreFalseNotExceptions() throws Exception {
        Path f = javaFile("Unopened.java");
        assertFalse(manager.supportsFormatting(f));
        assertFalse(manager.supportsRangeFormatting(f));
        assertFalse(manager.supportsCodeActions(f));
        assertFalse(manager.supportsRename(f));
        assertFalse(manager.supportsSignatureHelp(f));
        assertFalse(manager.supportsInlayHints(f));
        assertFalse(manager.supportsDocumentHighlight(f));
        assertFalse(manager.supportsSemanticTokens(f));
        assertFalse(manager.supportsDocumentSymbols(f));
        assertFalse(manager.supportsWorkspaceSymbols(f));
        assertFalse(manager.supportsCallHierarchy(f));
        assertFalse(manager.supportsTypeHierarchy(f));
        assertTrue(manager.triggerCharacters(f).isEmpty());
        assertTrue(manager.signatureTriggerCharacters(f).isEmpty());
    }

    /** A null path must never reach {@code toUri()} — remote/untitled buffers ask these constantly. */
    @Test
    void nullPathsAreHandledEverywhere() {
        assertFalse(manager.isManaged(null));
        assertNull(manager.managedServerId(null));
        assertFalse(manager.supportsFormatting(null));
    }

    // --- watched files (#677) ------------------------------------------------------------------------

    @Test
    void watchedFileChangesReachOnlySessionsWhoseRootContainsThem() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        manager.notifyWatchedFiles(
                List.of(new LspManager.WatchedFile(root.resolve("Other.java"), LspManager.WatchedKind.CHANGED)));
        assertEquals(1, fakes.get(0).watchedFiles.size(), "a change under the root must be forwarded");

        manager.notifyWatchedFiles(List.of(new LspManager.WatchedFile(
                root.getParent().resolve("outside-the-root.java"), LspManager.WatchedKind.CHANGED)));
        assertEquals(1, fakes.get(0).watchedFiles.size(), "a change outside the root must not be forwarded");
    }

    private static org.eclipse.lsp4j.Diagnostic diagnostic(String message) {
        var d = new org.eclipse.lsp4j.Diagnostic();
        d.setRange(new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(0, 0), new org.eclipse.lsp4j.Position(0, 1)));
        d.setMessage(message);
        d.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Error);
        return d;
    }
}
