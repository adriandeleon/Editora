package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A server whose start throws — its command cannot be run, or a remote connection broke mid-start — is
 * disposed by the start itself, so it never reads as a crash. The manager reports it separately, or the
 * coordinator would never learn that the documents it opened have no server.
 */
@Tag("fx")
@DisabledOnOs(
        OS.WINDOWS) // a directory as the command is what makes the fork fail; Windows resolves commands its own way
class LspStartFailureFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void aStartThatThrowsIsReportedAsAFailedStartNotACrash(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("app"));
        Path file = root.resolve("main.py");
        Files.writeString(file, "print('hi')\n");
        // Executable to the availability probe, but nothing that can be exec'd.
        Path notAServer = Files.createDirectories(dir.resolve("not-a-server"));
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            manager.configure(true, Map.of("python", notAServer.toString()));
            CompletableFuture<Path> failed = new CompletableFuture<>();
            AtomicInteger crashes = new AtomicInteger();
            manager.setOnStartFailed((serverId, sessionRoot) -> {
                assertEquals("python", serverId);
                failed.complete(sessionRoot);
            });
            manager.setOnSessionCrashed((serverId, sessionRoot) -> crashes.incrementAndGet());

            Platform.runLater(() -> manager.openDocument(file, root, "python", "print('hi')\n"));

            assertEquals(root, failed.get(20, TimeUnit.SECONDS));
            CompletableFuture<Void> drained = new CompletableFuture<>();
            Platform.runLater(() -> drained.complete(null));
            drained.get(5, TimeUnit.SECONDS);
            assertEquals(0, crashes.get(), "a failed start is not a crash (no auto-restart, #666)");
            assertFalse(manager.isManaged(file), "a document must not look managed by a server that never started");
        } finally {
            manager.close();
        }
    }
}
