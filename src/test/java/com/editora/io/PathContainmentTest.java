package com.editora.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Canonical ("real path") containment — the definition of "inside this folder" the trust boundaries share. */
class PathContainmentTest {

    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "symbolic links unavailable: " + e);
        }
    }

    @Test
    void ordinaryPathsInsideAndOutside(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Files.writeString(root.resolve("a.txt"), "x");
        assertTrue(PathContainment.isWithin(root, root));
        assertTrue(PathContainment.isWithin(root, root.resolve("a.txt")));
        assertTrue(PathContainment.isWithin(root, root.resolve("new/dir/b.txt")), "not created yet, still inside");
        assertTrue(PathContainment.isWithin(root, root.resolve("sub/../a.txt")));
        assertFalse(PathContainment.isWithin(root, tmp.resolve("other.txt")));
        assertFalse(PathContainment.isWithin(root, root.resolve("../other.txt")));
        assertFalse(PathContainment.isWithin(root, tmp.resolve("project-secrets/a.txt")), "a shared name prefix");
        assertFalse(PathContainment.isWithin(null, root));
        assertFalse(PathContainment.isWithin(root, null));
    }

    @Test
    void aSymlinkInsideTheRootDoesNotMakeItsTargetInside(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        Path outside = Files.createDirectories(tmp.resolve("outside"));
        Files.writeString(outside.resolve("secret.txt"), "s");
        symlink(root.resolve("link"), outside);
        symlink(root.resolve("file-link"), outside.resolve("secret.txt"));
        assertFalse(PathContainment.isWithin(root, root.resolve("link/secret.txt")));
        assertFalse(PathContainment.isWithin(root, root.resolve("link/not/yet/there.txt")));
        assertFalse(PathContainment.isWithin(root, root.resolve("file-link")));
    }

    @Test
    void aRootReachedThroughASymlinkStillContainsItsFiles(@TempDir Path tmp) throws IOException {
        Path real = Files.createDirectories(tmp.resolve("real-project"));
        Files.writeString(real.resolve("a.txt"), "x");
        symlink(tmp.resolve("alias"), real);
        assertTrue(PathContainment.isWithin(tmp.resolve("alias"), real.resolve("a.txt")), "both sides canonical");
        assertTrue(PathContainment.isWithin(real, tmp.resolve("alias/a.txt")));
    }

    @Test
    void aDanglingLinkIsNeverVouchedFor(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project"));
        symlink(root.resolve("dangling"), tmp.resolve("nowhere"));
        assertFalse(PathContainment.isWithin(root, root.resolve("dangling")));
        assertThrows(IOException.class, () -> PathContainment.realOrNearest(root.resolve("dangling/x")));
    }

    @Test
    void realOrNearestKeepsTheMissingTail(@TempDir Path tmp) throws IOException {
        Path root = Files.createDirectories(tmp.resolve("project")).toRealPath();
        assertEquals(root.resolve("a/b/c.txt"), PathContainment.realOrNearest(root.resolve("a/b/c.txt")));
        assertEquals(root, PathContainment.realOrNearest(root.resolve("x/..")));
    }
}
