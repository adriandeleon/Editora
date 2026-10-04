package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * astro-ls loads and runs the JavaScript of the TypeScript SDK it is given. When the only SDK on offer
 * belongs to a folder that is not trusted, the server must not be started at all — and the manager says so
 * rather than leaving the document looking managed.
 */
@Tag("fx")
class LspAstroSdkTrustFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void anUntrustedFoldersSdkDoesNotStartTheAstroServer(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("site"));
        Path sdk = Files.createDirectories(root.resolve("node_modules/typescript/lib"));
        Files.createFile(sdk.resolve("typescript.js"));
        Path file = root.resolve("index.astro");
        Files.writeString(file, "---\n---\n");
        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            // A server with no TypeScript beside it, so the folder's own SDK is the only candidate.
            manager.configure(true, Map.of("astro", dir.resolve("absent/astro-ls") + " --stdio"));
            CompletableFuture<Path> withheld = new CompletableFuture<>();
            manager.setOnStartWithheld((serverId, sessionRoot) -> {
                assertEquals("astro", serverId);
                withheld.complete(sessionRoot);
            });
            // No setFolderTrust: nothing is trusted by default.

            Platform.runLater(() -> manager.openDocument(file, root, "astro", "---\n---\n"));

            assertEquals(root, withheld.get(20, TimeUnit.SECONDS));
            assertFalse(manager.isManaged(file), "a document must not look managed by a server that never started");
            assertTrue(Files.exists(sdk.resolve("typescript.js")));
        } finally {
            manager.close();
        }
    }
}
