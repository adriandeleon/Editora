package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Session <b>lifetime</b>: idle eviction (#669), the crash-vs-deliberate-teardown distinction (#666), and the
 * per-root jdtls workspace claim (#668). Each of these is a past fix whose behaviour lived only in the code —
 * the pure helpers around them are unit-tested, but nothing exercised the actual transitions.
 *
 * <p>Driven through {@code setSessionStarterForTest} (no fork) with {@code setIdleEvictionGraceForTest}
 * shortening the 3-minute production grace, and {@code simulateServerDeathForTest} standing in for the
 * {@code process.onExit()} that cannot happen without a process.
 */
@Tag("fx")
class LspSessionLifecycleFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private final List<LanguageServerSession> sessions = new CopyOnWriteArrayList<>();
    private final List<String> crashedServers = new CopyOnWriteArrayList<>();
    private final List<Path> crashedRoots = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        sessions.clear();
        crashedServers.clear();
        crashedRoots.clear();
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            sessions.add(session);
            session.attachForTest(new FakeLanguageServer(), new ServerCapabilities());
        });
        manager.setOnSessionCrashed((serverId, r) -> {
            crashedServers.add(serverId);
            crashedRoots.add(r);
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

    private static void drainFx() throws Exception {
        var latch = new CountDownLatch(1);
        Platform.runLater(latch::countDown);
        assertTrue(latch.await(10, TimeUnit.SECONDS), "FX queue did not drain");
    }

    /** Polls until {@code condition} holds or the timeout expires, draining FX each round. */
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

    // --- #669: idle eviction ------------------------------------------------------------------------

    /**
     * Closing the last document of a root evicts its session after the grace period. Before #669 the server
     * stayed alive until the window closed, so browsing Java files across N roots accumulated N jdtls JVMs.
     */
    @Test
    void closingTheLastDocumentEvictsTheSessionAfterTheGrace() throws Exception {
        manager.setIdleEvictionGraceForTest(Duration.ofMillis(50));
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        assertTrue(manager.hasSessionForTest("java", root));

        manager.closeDocument(f);

        awaitFx(() -> !manager.hasSessionForTest("java", root), "the idle session to be evicted");
        assertTrue(sessions.get(0).isDisposed(), "the evicted session must be disposed, not merely dropped");
    }

    /** An eviction must NOT fire while the session still serves another open document. */
    @Test
    void aSessionStillServingAnotherDocumentIsNotEvicted() throws Exception {
        manager.setIdleEvictionGraceForTest(Duration.ofMillis(50));
        Path a = javaFile("A.java");
        Path b = javaFile("B.java");
        manager.openDocument(a, root, "java", "class A {}");
        manager.openDocument(b, root, "java", "class B {}");

        manager.closeDocument(a);
        Thread.sleep(200);
        drainFx();

        assertTrue(manager.hasSessionForTest("java", root), "B.java is still open — the session must survive");
        assertTrue(manager.isManaged(b));
    }

    /**
     * Reopening within the grace cancels the pending eviction — this is what stops tab churn inside one
     * project from cold-restarting the server.
     */
    @Test
    void reopeningWithinTheGraceCancelsTheEviction() throws Exception {
        manager.setIdleEvictionGraceForTest(Duration.ofMillis(300));
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        manager.closeDocument(f);

        manager.openDocument(f, root, "java", "class A {}"); // reopened before the grace elapses

        Thread.sleep(500);
        drainFx();
        assertTrue(manager.hasSessionForTest("java", root), "the pending eviction should have been cancelled");
        assertTrue(manager.isManaged(f));
        assertEquals(1, sessions.size(), "no new session should have been created");
    }

    // --- #666: a crash is not a teardown -------------------------------------------------------------

    /**
     * A server that dies on its own is dropped AND reported, so the coordinator can clear stale diagnostics
     * and restart it. Before #666 it stayed cached looking alive: {@code isManaged} kept returning true, so
     * the re-open guard never fired and LSP was silently dead for the rest of the session.
     */
    @Test
    void aServerThatDiesOnItsOwnIsDroppedAndReported() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        sessions.get(0).simulateServerDeathForTest();
        awaitFx(() -> !crashedServers.isEmpty(), "the crash callback");

        assertEquals("java", crashedServers.get(0));
        assertEquals(root, crashedRoots.get(0));
        assertFalse(manager.isManaged(f), "a dead session must not keep claiming the document");
        assertFalse(manager.hasSessionForTest("java", root), "the dead session must be uncached");
    }

    /**
     * A deliberate teardown must NOT be reported as a crash — otherwise disabling a server, or closing a
     * window, would re-fork it in a loop.
     */
    @Test
    void aDeliberateShutdownIsNotReportedAsACrash() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        manager.shutdownServer("java");
        Thread.sleep(100);
        drainFx();

        assertTrue(crashedServers.isEmpty(), "shutdownServer must not look like a crash (#666)");
    }

    @Test
    void shutdownAllIsNotReportedAsACrashEither() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");

        manager.shutdownAll();
        Thread.sleep(100);
        drainFx();

        assertTrue(crashedServers.isEmpty(), "shutdownAll must not look like a crash (#666)");
    }

    /** After a crash the next open starts a fresh session rather than handing back the dead one. */
    @Test
    void reopeningAfterACrashStartsAFreshSession() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        sessions.get(0).simulateServerDeathForTest();
        awaitFx(() -> !crashedServers.isEmpty(), "the crash callback");

        manager.openDocument(f, root, "java", "class A {}");

        assertTrue(manager.isManaged(f));
        assertEquals(2, sessions.size(), "a fresh session must be created after a crash");
    }

    /** Death is reported once, however many times it is signalled. */
    @Test
    void aRepeatedDeathSignalReportsOnlyOnce() throws Exception {
        Path f = javaFile("A.java");
        manager.openDocument(f, root, "java", "class A {}");
        LanguageServerSession s = sessions.get(0);

        s.simulateServerDeathForTest();
        s.simulateServerDeathForTest();
        s.simulateServerDeathForTest();
        awaitFx(() -> !crashedServers.isEmpty(), "the crash callback");
        Thread.sleep(100);
        drainFx();

        assertEquals(1, crashedServers.size(), "the dead-reported latch must make this idempotent");
    }

    // --- #668: the per-root jdtls workspace claim ----------------------------------------------------

    /**
     * jdtls gets a dedicated {@code -data} workspace per root. Two managers (i.e. two windows) opening the
     * SAME root must not be handed the same directory — one Eclipse workspace admits a single process, and
     * the second would wedge at {@code initialize} until its 60 s timeout.
     */
    @Test
    void twoManagersOnOneRootClaimDifferentJdtlsWorkspaces() throws Exception {
        Path base = root.resolve("jdtls-workspaces");
        Path project = root.resolve("proj");
        Files.createDirectories(project);
        Path f = project.resolve("A.java");
        Files.writeString(f, "class A {}");

        List<List<String>> commandsSeen = new CopyOnWriteArrayList<>();
        LspManager second = new LspManager((a, b) -> {}, (a, b) -> {});
        try {
            manager.setJdtlsWorkspaceBase(base);
            second.setJdtlsWorkspaceBase(base);
            second.setSessionStarterForTest(
                    session -> session.attachForTest(new FakeLanguageServer(), new ServerCapabilities()));
            second.configure(true, Map.of("java", "jdtls"));

            manager.openDocument(f, project, "java", "class A {}");
            second.openDocument(f, project, "java", "class A {}");

            // Both claimed a workspace dir under the base; they must be distinct directories.
            try (var entries = Files.list(base)) {
                List<String> dirs =
                        entries.map(p -> p.getFileName().toString()).sorted().toList();
                assertEquals(2, dirs.size(), "each live session needs its own -data dir, got " + dirs);
                assertTrue(
                        dirs.get(1).startsWith(dirs.get(0)),
                        "the second claim should be the canonical name plus a suffix, got " + dirs);
            }
        } finally {
            second.shutdownAll();
        }
    }

    /** Dropping a session releases its claim, so the canonical (index-reusing) dir is available again. */
    @Test
    void disposingASessionReleasesItsWorkspaceClaim() throws Exception {
        Path base = root.resolve("jdtls-workspaces");
        Path project = root.resolve("proj");
        Files.createDirectories(project);
        Path f = project.resolve("A.java");
        Files.writeString(f, "class A {}");

        manager.setJdtlsWorkspaceBase(base);
        manager.openDocument(f, project, "java", "class A {}");
        String first;
        try (var entries = Files.list(base)) {
            first = entries.findFirst().orElseThrow().getFileName().toString();
        }

        manager.shutdownServer("java");
        manager.openDocument(f, project, "java", "class A {}");

        // The freed canonical name must be reused rather than a new suffixed dir accumulating.
        try (var entries = Files.list(base)) {
            List<String> dirs = entries.map(p -> p.getFileName().toString()).toList();
            assertEquals(1, dirs.size(), "the released claim should be recycled, got " + dirs);
            assertEquals(first, dirs.get(0), "the canonical dir preserves jdtls's persisted index");
        }
    }

    /** Only jdtls gets a {@code -data} dir; another server must not have one invented for it. */
    @Test
    void aNonJavaServerGetsNoJdtlsWorkspace() throws Exception {
        Path base = root.resolve("jdtls-workspaces");
        manager.configure(true, Map.of("python", "pyright-langserver --stdio"));
        manager.setJdtlsWorkspaceBase(base);

        Path py = root.resolve("a.py");
        Files.writeString(py, "x = 1\n");
        manager.openDocument(py, root, "python", "x = 1\n");

        assertTrue(manager.isManaged(py), "the python session should still start");
        assertFalse(Files.exists(base), "no jdtls workspace should be created for a non-java server");
    }
}
