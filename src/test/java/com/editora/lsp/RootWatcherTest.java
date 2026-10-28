package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a build-file watcher covers: its own folders, not a build's output (thirteenth review CK3). */
class RootWatcherTest {

    @Test
    void aBuildFileWatcherSkipsOutputsAndSaysWhichFoldersItWatches(@TempDir Path dir) throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path app = Files.createDirectories(root.resolve("app"));
        Files.writeString(app.resolve("pom.xml"), "<project/>");
        Path classes = Files.createDirectories(app.resolve("target/classes"));
        Path plainBuild = Files.createDirectories(root.resolve("docs/build")); // no build file beside it
        assertTrue(RootWatcher.buildOutput(app.resolve("target")));
        assertFalse(RootWatcher.buildOutput(plainBuild));
        try (RootWatcher w = RootWatcher.start(root, batch -> {}, LspManager::modelFile)) {
            assertNotNull(w);
            assertTrue(w.watches(root));
            assertTrue(w.watches(app));
            assertTrue(w.watches(plainBuild));
            assertFalse(w.watches(app.resolve("target")));
            assertFalse(w.watches(classes));
            assertFalse(w.watches(root.resolve("elsewhere")), "a folder never registered");
        }
    }
}
