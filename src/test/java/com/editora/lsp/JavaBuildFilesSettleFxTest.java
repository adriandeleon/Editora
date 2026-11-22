package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.lsp.LspManager.WatchedFile;
import com.editora.lsp.LspManager.WatchedKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Build files changed on disk in bursts reach jdtls as one forced {@code java/projectConfigurationsUpdate} once they
 * settle: jdtls's own update on {@code didChangeWatchedFiles} reads each pom when it gets to it and skips one it has
 * seen, so a checkout and a pull a second apart left modules on the versions in between.
 */
@Tag("fx")
class JavaBuildFilesSettleFxTest {

    private static final String UPDATE = "java/projectConfigurationsUpdate";

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path root;

    private LspManager manager;
    private List<FakeLanguageServer> fakes;
    private final List<Runnable> scheduled = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final List<Long> delays = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        manager = new LspManager((f, d) -> {}, (t, s) -> {});
        fakes = LspTestHooks.useFakeSessionsWithRawRequests(manager, new org.eclipse.lsp4j.ServerCapabilities());
        manager.setBuildSettleSchedulerForTest((ms, task) -> {
            delays.add(ms);
            scheduled.add(task);
        });
        manager.configure(true, Map.of("java", "jdtls"));
        Files.createDirectories(root.resolve("core"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("core/pom.xml"), "<project/>");
        Path file = root.resolve("A.java");
        Files.writeString(file, "class A {}\n");
        manager.openDocument(file, root, "java", "class A {}\n");
    }

    @AfterEach
    void tearDown() {
        manager.shutdownAll();
    }

    private List<FakeLanguageServer.Raw> updates() {
        synchronized (fakes.get(0).rawNotifications) {
            return fakes.get(0).rawNotifications.stream()
                    .filter(r -> UPDATE.equals(r.method()))
                    .toList();
        }
    }

    private static List<String> uris(FakeLanguageServer.Raw update) {
        @SuppressWarnings("unchecked")
        var ids = (List<org.eclipse.lsp4j.TextDocumentIdentifier>)
                ((Map<String, Object>) update.params()).get("identifiers");
        return ids.stream()
                .map(org.eclipse.lsp4j.TextDocumentIdentifier::getUri)
                .toList();
    }

    @Test
    void burstsOfBuildFileChangesEndInOneForcedUpdateOfThemAll() {
        manager.notifyWatchedFiles(List.of(new WatchedFile(root.resolve("core/pom.xml"), WatchedKind.CHANGED)));
        manager.notifyWatchedFiles(List.of(
                new WatchedFile(root.resolve("pom.xml"), WatchedKind.CHANGED),
                new WatchedFile(root.resolve("A.java"), WatchedKind.CHANGED)));
        assertEquals(2, scheduled.size(), "each burst waits again");
        assertEquals(LspFileWatching.BUILD_SETTLE_MS, delays.get(0));
        scheduled.get(0).run(); // the first burst's wait ends: a later change is still settling
        assertTrue(updates().isEmpty(), updates().toString());
        scheduled.get(1).run();
        List<FakeLanguageServer.Raw> sent = updates();
        assertEquals(1, sent.size(), sent.toString());
        List<String> uris = uris(sent.get(0));
        assertEquals(2, uris.size(), uris.toString());
        assertTrue(uris.stream().anyMatch(u -> u.endsWith("/core/pom.xml")), uris.toString());
        assertTrue(
                uris.stream().anyMatch(u -> u.endsWith("/pom.xml") && !u.endsWith("/core/pom.xml")), uris.toString());
    }

    @Test
    void sourceChangesAndDeletedBuildFilesForceNothing() {
        manager.notifyWatchedFiles(List.of(
                new WatchedFile(root.resolve("A.java"), WatchedKind.CHANGED),
                new WatchedFile(root.resolve("core/pom.xml"), WatchedKind.DELETED)));
        assertTrue(scheduled.isEmpty(), "nothing waits");
        assertTrue(updates().isEmpty(), updates().toString());
    }
}
