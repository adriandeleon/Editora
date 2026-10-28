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
    /** A folder the project's {@code .gitignore} files leave out is not watched, at any depth (Bash review B16). */
    @Test
    void foldersTheGitignoreLeavesOutAreNotWatched(@TempDir Path dir) throws Exception {
        Path root = dir.toAbsolutePath().normalize();
        Files.writeString(root.resolve(".gitignore"), "dist/\n*.log\n");
        Path dist = Files.createDirectories(root.resolve("dist/assets"));
        Path pkg = Files.createDirectories(root.resolve("packages/a"));
        Files.writeString(pkg.resolve(".gitignore"), "cache/\n");
        Path cache = Files.createDirectories(pkg.resolve("cache"));
        Path lib = Files.createDirectories(root.resolve("lib"));
        try (RootWatcher w = RootWatcher.start(root, batch -> {})) {
            assertNotNull(w);
            assertTrue(w.watches(root));
            assertTrue(w.watches(lib));
            assertTrue(w.watches(pkg));
            assertFalse(w.watches(root.resolve("dist")));
            assertFalse(w.watches(dist));
            assertFalse(w.watches(cache), "a nested .gitignore counts for its own folder");
        }
    }
}
