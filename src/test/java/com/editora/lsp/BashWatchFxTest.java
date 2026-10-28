package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.ServerCapabilities;
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
 * A sourced library changed on disk in a folder the Project panel does not show: its server root is watched as a
 * whole (Bash review B16), and the Bash server, which ignores {@code didChangeWatchedFiles}, is made to read the file
 * again by opening and closing it (B7).
 */
@Tag("fx")
class BashWatchFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private LspManager manager;
    private final List<FakeLanguageServer> fakes = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        manager = new LspManager((f, d) -> {}, (t, m) -> {});
        manager.setSessionStarterForTest(session -> {
            FakeLanguageServer fake = new FakeLanguageServer();
            fakes.add(fake);
            session.attachForTest(fake, new ServerCapabilities());
        });
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void aLibraryChangedOnDiskIsReportedAndReadAgainByTheBashServer() throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root.resolve(".git"));
        Path util = Files.createDirectories(root.resolve("lib")).resolve("util.sh");
        Files.writeString(util, "log_info() { echo \"$*\"; }\n");
        Path main = Files.writeString(root.resolve("main.sh"), "source lib/util.sh\nlog_info hi\n");
        manager.configure(true, Map.of("bash", "bash-language-server"));
        manager.openDocument(main, root, "shell", Files.readString(main));
        assertEquals(java.util.Set.of(root), manager.watchedRoots(), "the root as a whole, project or not");
        FakeLanguageServer fake = fakes.get(0);
        String utilUri = util.toUri().toString();

        Files.writeString(util, "log_info() { echo \"$*\"; }\nnew_helper() { :; }\n");

        waitFor(() -> onFx(() -> fake.watchedFiles.stream()
                .flatMap(p -> p.getChanges().stream())
                .anyMatch(e -> e.getUri().equals(utilUri))));
        waitFor(() -> onFx(() ->
                fake.closed.stream().anyMatch(c -> c.getTextDocument().getUri().equals(utilUri))));
        var reopened = onFx(() -> fake.opened.stream()
                .filter(o -> o.getTextDocument().getUri().equals(utilUri))
                .reduce((a, b) -> b)
                .orElseThrow()
                .getTextDocument());
        assertTrue(reopened.getText().contains("new_helper"), "the server got the new text: " + reopened.getText());
        assertTrue(onFx(() -> fake.opened.stream()
                .noneMatch(o -> o.getTextDocument().getUri().equals(main.toUri().toString())
                        && o.getTextDocument().getVersion() != 1)));
    }

    /** A script open in a window is left to it: the editor's own reload re-sends it. */
    @Test
    void anOpenScriptChangedOnDiskIsNotReopened() throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        Files.createDirectories(root.resolve(".git"));
        Path main = Files.writeString(root.resolve("main.sh"), "echo hi\n");
        manager.configure(true, Map.of("bash", "bash-language-server"));
        manager.openDocument(main, root, "shell", "echo hi\n");
        FakeLanguageServer fake = fakes.get(0);
        onFx(() -> {
            manager.notifyWatchedFiles(List.of(new LspManager.WatchedFile(main, LspManager.WatchedKind.CHANGED)));
            return null;
        });
        Thread.sleep(BashReanalysis.DEBOUNCE_MS + 400);
        assertEquals(1, onFx(() -> fake.opened.size()));
        assertTrue(onFx(() -> fake.closed.isEmpty()));
    }

    private static void waitFor(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.call()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out");
            }
            Thread.sleep(50);
        }
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> task) throws Exception {
        var f = new java.util.concurrent.CompletableFuture<T>();
        javafx.application.Platform.runLater(() -> {
            try {
                f.complete(task.call());
            } catch (Exception e) {
                f.completeExceptionally(e);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }
}
