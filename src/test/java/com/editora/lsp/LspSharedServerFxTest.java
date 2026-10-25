package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two windows on one Java project share one jdtls (Java review C12): the second joins the first's server,
 * a file open in both is opened once on it, both hear its diagnostics, a window that leaves leaves the server
 * to the other, a restart in one reopens the other's documents, and a question goes to the focused window.
 */
@Tag("fx")
class LspSharedServerFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private final List<LanguageServerSession> sessions = new CopyOnWriteArrayList<>();
    private final List<FakeLanguageServer> servers = new CopyOnWriteArrayList<>();
    private final List<String> diagnosticsA = new CopyOnWriteArrayList<>();
    private final List<String> diagnosticsB = new CopyOnWriteArrayList<>();
    private final List<String> crashedA = new CopyOnWriteArrayList<>();
    private final List<String> crashedB = new CopyOnWriteArrayList<>();
    private final List<String> endedA = new CopyOnWriteArrayList<>();
    private final List<String> endedB = new CopyOnWriteArrayList<>();
    private LspManager a;
    private LspManager b;

    private LspManager window(List<String> diagnostics, List<String> crashed, List<String> ended) {
        LspManager m =
                new LspManager((file, list) -> diagnostics.add(file.getFileName() + ":" + list.size()), (t, msg) -> {});
        m.setSessionStarterForTest(session -> {
            FakeLanguageServer server = new FakeLanguageServer();
            sessions.add(session);
            servers.add(server);
            session.attachForTest(server, new ServerCapabilities());
        });
        m.setOnSessionCrashed((serverId, r) -> crashed.add(serverId));
        m.setOnSharedServerEnded((serverId, r) -> ended.add(serverId));
        m.configure(true, Map.of("java", "jdtls"));
        return m;
    }

    @BeforeEach
    void setUp() {
        a = window(diagnosticsA, crashedA, endedA);
        b = window(diagnosticsB, crashedB, endedB);
        a.shareServersForTest(b); // two windows of one process
    }

    @AfterEach
    void tearDown() {
        a.shutdownAll();
        b.shutdownAll();
    }

    private Path javaFile(String name) throws Exception {
        Path f = root.resolve(name);
        Files.writeString(f, "class X {}\n");
        return f;
    }

    private static void drainFx() throws Exception {
        var latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FX queue did not drain");
    }

    private static void awaitFx(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            drainFx();
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for: " + what);
    }

    @Test
    void theSecondWindowJoinsTheFirstWindowsServer() throws Exception {
        Path one = javaFile("A.java");
        Path two = javaFile("B.java");
        a.openDocument(one, root, "java", "class A {}");
        assertTrue(b.isRunning("java", root), "ready for the second window before it opens anything: no 'starting'");
        b.openDocument(two, root, "java", "class B {}");

        assertEquals(1, sessions.size(), "one jdtls for the project, not one per window");
        assertTrue(a.isManaged(one));
        assertTrue(b.isManaged(two));
        assertTrue(b.isRunning("java", root));
        assertEquals(2, servers.get(0).opened.size(), "both windows' documents are open on it");
    }

    @Test
    void aFileOpenInBothWindowsIsOpenedOnceAndClosedWithTheLastWindow() throws Exception {
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A { int x; }");
        FakeLanguageServer server = servers.get(0);

        assertEquals(1, server.opened.size());
        assertEquals(1, server.changed.size(), "the second window's text arrives as a change");
        assertEquals(
                List.of(2),
                server.changed.stream()
                        .map(p -> p.getTextDocument().getVersion())
                        .toList(),
                "one version line for the document, whichever window sends");

        a.closeDocument(f);
        assertTrue(server.closed.isEmpty(), "still open in the other window");
        b.closeDocument(f);
        assertEquals(1, server.closed.size());
    }

    @Test
    void bothWindowsHearTheServersDiagnostics() throws Exception {
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A {}");
        var diagnostic = new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), "boom");
        LspTestHooks.publishDiagnostics(
                a, f, new PublishDiagnosticsParams(f.toUri().toString(), List.of(diagnostic)));

        awaitFx(() -> !diagnosticsA.isEmpty() && !diagnosticsB.isEmpty(), "diagnostics in both windows");
        assertEquals(List.of("A.java:1"), diagnosticsA);
        assertEquals(List.of("A.java:1"), diagnosticsB);
    }

    @Test
    void aWindowThatClosesLeavesTheServerToTheOtherAndTheLastOneStopsIt() throws Exception {
        Path f = javaFile("A.java");
        Path g = javaFile("B.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(g, root, "java", "class B {}");
        LanguageServerSession shared = sessions.get(0);

        a.close();
        assertFalse(shared.isDisposed(), "the other window still uses it");
        assertTrue(b.isManaged(g));
        assertEquals(1, servers.get(0).closed.size(), "the leaving window's document is closed on it");
        drainFx();
        assertTrue(endedB.isEmpty() && crashedB.isEmpty(), "nothing to tell the window that stays");

        b.shutdownAll();
        assertTrue(shared.isDisposed(), "its last window stops it");
    }

    @Test
    void theFirstWindowLeavingFirstKeepsItRunningForAWindowThatOpensLater() throws Exception {
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A {}");
        a.shutdownAll();

        LspManager c = window(new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>(), new CopyOnWriteArrayList<>());
        try {
            a.shareServersForTest(c);
            c.openDocument(javaFile("C.java"), root, "java", "class C {}");
            assertEquals(1, sessions.size(), "a third window still finds the server the second one kept");
        } finally {
            c.shutdownAll();
        }
    }

    @Test
    void aRestartInOneWindowReopensTheOtherWindowsDocumentsQuietly() throws Exception {
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A {}");
        LanguageServerSession shared = sessions.get(0);

        a.restartAll();

        assertTrue(shared.isDisposed(), "a restart stops it for both windows");
        awaitFx(() -> !endedB.isEmpty(), "the other window to hear it ended");
        assertEquals(List.of("java"), endedB);
        assertTrue(crashedB.isEmpty(), "not a crash");
        assertTrue(endedA.isEmpty() && crashedA.isEmpty(), "the window that restarted it knows");
        assertFalse(b.isManaged(f), "its documents wait for the new server");

        b.openDocument(f, root, "java", "class A {}");
        a.openDocument(f, root, "java", "class A {}");
        assertEquals(2, sessions.size(), "one new server");
        assertSame(sessions.get(1), b.sessionForTest(f));
        assertSame(sessions.get(1), a.sessionForTest(f));
    }

    @Test
    void aCrashIsReportedOnceAndTheOtherWindowReopensQuietly() throws Exception {
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A {}");

        LspTestHooks.simulateServerDeath(a, f);

        awaitFx(() -> crashedA.size() + crashedB.size() + endedA.size() + endedB.size() == 2, "both windows told");
        assertEquals(1, crashedA.size() + crashedB.size(), "one crash notice (and one count towards the crash loop)");
        assertEquals(1, endedA.size() + endedB.size(), "the other window just reopens");
    }

    @Test
    void aQuestionFromTheServerGoesToTheFocusedWindow() throws Exception {
        List<String> askedA = new CopyOnWriteArrayList<>();
        List<String> askedB = new CopyOnWriteArrayList<>();
        a.setClientUi(ui(askedA));
        b.setClientUi(ui(askedB));
        a.setWindowFocused(() -> false);
        b.setWindowFocused(() -> true);
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A {}");

        var params = new ShowMessageRequestParams(List.of(new MessageActionItem("Yes")));
        params.setType(MessageType.Info);
        params.setMessage("Import the project?");
        CompletableFuture<MessageActionItem> answer = a.sessionForTest(f).showMessageRequest(params);

        assertEquals("Yes", answer.get(10, TimeUnit.SECONDS).getTitle());
        assertEquals(List.of("Import the project?"), askedB);
        assertTrue(askedA.isEmpty());
    }

    @Test
    void oneWindowsIdleEvictionLeavesTheOtherWindowsServerAlone() throws Exception {
        a.setIdleEvictionGraceForTest(Duration.ofMillis(30));
        Path f = javaFile("A.java");
        Path g = javaFile("B.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(g, root, "java", "class B {}");
        LanguageServerSession shared = sessions.get(0);

        a.closeDocument(f);
        awaitFx(() -> !a.hasSessionForTest("java", root), "the first window to let it go");
        assertFalse(shared.isDisposed(), "the other window still has a document on it");
        assertTrue(b.isManaged(g));
    }

    @Test
    void aReadyServerRefreshesBothWindowsDocuments() throws Exception {
        List<String> refreshedA = new CopyOnWriteArrayList<>();
        List<String> refreshedB = new CopyOnWriteArrayList<>();
        a.setOnRefreshRequested((kind, origin) -> refreshedA.add(kind));
        b.setOnRefreshRequested((kind, origin) -> refreshedB.add(kind));
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        b.openDocument(f, root, "java", "class A {}");
        awaitFx(
                () -> refreshedB.contains("capabilities"),
                "the joining window's refresh: the server was ready already");
        refreshedA.clear();
        refreshedB.clear();

        LspTestHooks.languageStatus(a, f, "ServiceReady"); // jdtls again, once the import has finished

        awaitFx(() -> refreshedA.contains("capabilities") && refreshedB.contains("capabilities"), "both refreshed");
    }

    @Test
    void theSharedServerHoldsTheProjectsOneWorkspaceUntilItsLastWindowLeaves() throws Exception {
        Path base = root.resolve("jdtls-workspaces");
        a.setJdtlsWorkspaceBase(base);
        b.setJdtlsWorkspaceBase(base);
        Path project = Files.createDirectories(root.resolve("proj"));
        Path f = project.resolve("A.java");
        Files.writeString(f, "class A {}");
        a.openDocument(f, project, "java", "class A {}");
        b.openDocument(f, project, "java", "class A {}");
        String canonical = LspServerRegistry.workspaceDirName(project);
        try (var dirs = Files.list(base)) {
            assertEquals(
                    List.of(canonical),
                    dirs.map(p -> p.getFileName().toString()).toList(),
                    "no -2");
        }

        a.close(); // the window that started it, and holds the claim, leaves first
        assertEquals(
                canonical + "-2", LspManager.claimJdtlsWorkspaceName(canonical), "still held for the other window");
        LspManager.releaseJdtlsWorkspaceName(canonical + "-2");

        b.shutdownAll();
        awaitFx(() -> canonical.equals(claimAndRelease(canonical)), "the claim released once the server is gone");
    }

    private static String claimAndRelease(String baseName) {
        String name = LspManager.claimJdtlsWorkspaceName(baseName);
        LspManager.releaseJdtlsWorkspaceName(name);
        return name;
    }

    @Test
    void anotherWindowsRestartWaitsForCleanJavaWorkspaceToDelete() throws Exception {
        Path base = root.resolve("jdtls-workspaces");
        a.setJdtlsWorkspaceBase(base);
        b.setJdtlsWorkspaceBase(base);
        Path project = Files.createDirectories(root.resolve("proj"));
        Path f = project.resolve("A.java");
        Files.writeString(f, "class A {}");
        a.openDocument(f, project, "java", "class A {}");
        b.openDocument(f, project, "java", "class A {}");

        var cleaned = new CompletableFuture<Boolean>();
        var waited = new CompletableFuture<Boolean>();
        Platform.runLater(() -> {
            a.cleanJavaWorkspace(f, cleaned::complete);
            waited.complete(LspManager.pendingExitForTest("java", project) != null);
        });
        assertTrue(waited.get(10, TimeUnit.SECONDS), "every start on the project waits for the clean (re-review CN1)");
        assertTrue(cleaned.get(10, TimeUnit.SECONDS), "deleted");
        awaitFx(() -> LspManager.pendingExitForTest("java", project) == null, "the wait to end with the clean");
    }

    @Test
    void aWindowJoiningAReadyServerIsToldItIsReady() throws Exception {
        Path f = javaFile("A.java");
        a.openDocument(f, root, "java", "class A {}");
        List<String> statuses = new CopyOnWriteArrayList<>();
        LspManager c = new LspManager((file, list) -> {}, (type, msg) -> statuses.add(type));
        try {
            c.setSessionStarterForTest(
                    session -> session.attachForTest(new FakeLanguageServer(), new ServerCapabilities()));
            c.configure(true, Map.of("java", "jdtls"));
            a.shareServersForTest(c);
            c.openDocument(f, root, "java", "class A {}");
            awaitFx(() -> statuses.contains("ServiceReady"), "the joining window to hear the server is ready (CN5)");
        } finally {
            c.shutdownAll();
        }
    }

    private static LspClientUi ui(List<String> asked) {
        return new LspClientUi() {
            @Override
            public CompletableFuture<String> chooseAction(
                    String serverId, MessageType type, String message, List<String> actions) {
                asked.add(message);
                return CompletableFuture.completedFuture(actions.get(0));
            }

            @Override
            public CompletableFuture<String> inputBox(
                    String serverId, String prompt, String value, String placeHolder) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<String> quickPick(String serverId, String placeHolder, List<PickItem> items) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void openLocation(String serverId, Path file, int line, int column) {}
        };
    }
}
