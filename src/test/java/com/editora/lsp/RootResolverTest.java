package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RootResolverTest {

    private static final List<String> MARKERS = LspServerRegistry.JAVA_ROOT_MARKERS;

    @Test
    void projectRootWinsWhenFileIsUnderIt(@TempDir Path tmp) {
        Path project = tmp.resolve("proj");
        Path file = project.resolve("a/b/Main.java");
        assertEquals(project.toAbsolutePath().normalize(), RootResolver.resolve(project, file, MARKERS));
    }

    @Test
    void projectRootIgnoredWhenFileIsOutsideIt(@TempDir Path tmp) throws IOException {
        Path project = Files.createDirectories(tmp.resolve("proj"));
        Path other = Files.createDirectories(tmp.resolve("other"));
        Files.createFile(other.resolve("pom.xml"));
        Path file = Files.createFile(other.resolve("Main.java"));
        // The active project doesn't contain the file → fall back to the file's own marker root.
        assertEquals(other.toAbsolutePath().normalize(), RootResolver.resolve(project, file, MARKERS));
    }

    @Test
    void findsNearestMarkerAncestor(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("repo"));
        Files.createFile(root.resolve("pom.xml"));
        Path file = Files.createDirectories(root.resolve("src/main/java/app")).resolve("Main.java");
        Files.createFile(file);
        assertEquals(root, RootResolver.findMarkerRoot(file, MARKERS));
        assertEquals(root, RootResolver.resolve(null, file, MARKERS));
    }

    @Test
    void nearestMarkerWinsOverFartherOne(@TempDir Path tmp) throws IOException {
        Path outer = Files.createDirectories(tmp.resolve("outer"));
        Files.createFile(outer.resolve(".git")); // a file named .git for the test
        Path inner = Files.createDirectories(outer.resolve("module"));
        Files.createFile(inner.resolve("build.gradle"));
        Path file = Files.createDirectories(inner.resolve("src")).resolve("Main.java");
        Files.createFile(file);
        assertEquals(inner, RootResolver.findMarkerRoot(file, MARKERS));
    }

    @Test
    void fallsBackToParentDirWhenNoMarker(@TempDir Path tmp) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("loose"));
        Path file = dir.resolve("Scratch.java");
        Files.createFile(file);
        assertNull(RootResolver.findMarkerRoot(file, MARKERS));
        assertEquals(dir.toAbsolutePath().normalize(), RootResolver.resolve(null, file, MARKERS));
    }
}
