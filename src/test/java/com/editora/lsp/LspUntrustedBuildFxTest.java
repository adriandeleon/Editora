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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * rust-analyzer runs a crate's build scripts and proc macros to load it, so on a folder that is not trusted and has a
 * {@code Cargo.toml} it is not started at all (see {@link UntrustedBuilds}); trusted, the same open goes on to start
 * the server (which, missing here, fails to start rather than being withheld).
 */
@Tag("fx")
class LspUntrustedBuildFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void anUntrustedCrateDoesNotStartItsServerAndATrustedOneDoes(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("crate"));
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"c\"\n");
        Path file = Files.createDirectories(root.resolve("src")).resolve("main.rs");
        Files.writeString(file, "fn main() {}\n");
        String missing = dir.resolve("absent/rust-analyzer").toString();

        LspManager manager = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            manager.configure(true, Map.of("rust", missing));
            CompletableFuture<Path> withheld = new CompletableFuture<>();
            manager.setOnStartWithheld((serverId, sessionRoot) -> {
                assertEquals("rust", serverId);
                withheld.complete(sessionRoot);
            });
            // No setFolderTrust: nothing is trusted by default.
            Platform.runLater(() -> manager.openDocument(file, root, "rust", "fn main() {}\n"));
            assertEquals(root, withheld.get(20, TimeUnit.SECONDS));
            assertFalse(manager.isManaged(file), "a document must not look managed by a server that never started");
        } finally {
            manager.close();
        }

        LspManager trusting = new LspManager((f, d) -> {}, (type, message) -> {});
        try {
            trusting.configure(true, Map.of("rust", missing));
            trusting.setFolderTrust(folder -> true);
            CompletableFuture<Path> withheld = new CompletableFuture<>();
            CompletableFuture<Path> failed = new CompletableFuture<>();
            trusting.setOnStartWithheld((serverId, sessionRoot) -> withheld.complete(sessionRoot));
            trusting.setOnStartFailed((serverId, sessionRoot) -> failed.complete(sessionRoot));
            Platform.runLater(() -> trusting.openDocument(file, root, "rust", "fn main() {}\n"));
            assertEquals(root, failed.get(20, TimeUnit.SECONDS), "trusted: started (and failed, being absent)");
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> withheld.get(200, TimeUnit.MILLISECONDS),
                    "a trusted folder's server is not withheld");
        } finally {
            trusting.close();
        }
    }
}
