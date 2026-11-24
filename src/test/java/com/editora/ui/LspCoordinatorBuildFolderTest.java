package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link LspCoordinator#buildFolderAbove}: what the per-folder build-folder memo looks up, once per server root and
 * source folder rather than per publish (perf review 2026-11-23 L10).
 */
class LspCoordinatorBuildFolderTest {

    @Test
    void theRootWithABuildFileElseTheNearestFolderAboveThatHasOne(@TempDir Path root) throws Exception {
        Path dir = Files.createDirectories(root.resolve("shelf/src/main/java/demo"));
        assertNull(LspCoordinator.buildFolderAbove(root, dir), "no build file anywhere");

        Files.writeString(root.resolve("shelf/pom.xml"), "<project/>");
        assertEquals(root.resolve("shelf"), LspCoordinator.buildFolderAbove(root, dir));

        Files.writeString(root.resolve("build.gradle"), "");
        assertEquals(root, LspCoordinator.buildFolderAbove(root, dir), "the root's own build wins");
    }

    @Test
    void aFolderOutsideTheRootHasNone(@TempDir Path base) throws Exception {
        Path root = Files.createDirectories(base.resolve("proj"));
        Path other = Files.createDirectories(base.resolve("other"));
        Files.writeString(other.resolve("pom.xml"), "<project/>");
        assertNull(LspCoordinator.buildFolderAbove(root, other));
    }
}
