package com.editora.diff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryDiffTest {

    @TempDir
    Path temp;

    @Test
    void reportsOnlyChangedFilesInStableRelativePathOrder() throws Exception {
        Path left = Files.createDirectories(temp.resolve("left/nested"));
        Path right = Files.createDirectories(temp.resolve("right/nested"));
        left = left.getParent();
        right = right.getParent();
        Files.writeString(left.resolve("same.txt"), "same\n");
        Files.writeString(right.resolve("same.txt"), "same\n");
        Files.writeString(left.resolve("nested/change.txt"), "before\n");
        Files.writeString(right.resolve("nested/change.txt"), "after!\n");
        Files.writeString(left.resolve("only-left.txt"), "left\n");
        Files.writeString(right.resolve("only-right.txt"), "right\n");

        DirectoryDiff.Result result = DirectoryDiff.compare(left, right);

        assertEquals(1, result.identicalFiles());
        assertFalse(result.truncated());
        assertEquals(
                List.of(
                        new DirectoryDiff.Entry("nested/change.txt", DirectoryDiff.Kind.MODIFIED, 7, 7),
                        new DirectoryDiff.Entry("only-left.txt", DirectoryDiff.Kind.LEFT_ONLY, 5, -1),
                        new DirectoryDiff.Entry("only-right.txt", DirectoryDiff.Kind.RIGHT_ONLY, -1, 6)),
                result.entries());
    }

    @Test
    void boundsTheReviewAndMarksItTruncated() throws Exception {
        Path left = Files.createDirectories(temp.resolve("bounded-left"));
        Path right = Files.createDirectories(temp.resolve("bounded-right"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(left.resolve("left-" + i + ".txt"), "left");
            Files.writeString(right.resolve("right-" + i + ".txt"), "right");
        }

        DirectoryDiff.Result result = DirectoryDiff.compare(left, right, 3);

        assertTrue(result.truncated());
        assertTrue(result.entries().size() <= 3);
    }

    @Test
    void emptyDirectoriesAreIdentical() throws Exception {
        Path left = Files.createDirectories(temp.resolve("empty-left"));
        Path right = Files.createDirectories(temp.resolve("empty-right"));
        DirectoryDiff.Result result = DirectoryDiff.compare(left, right);
        assertEquals(List.of(), result.entries());
        assertEquals(0, result.identicalFiles());
    }

    @Test
    void skipsGitMetadataAndPathsIgnoredByEitherRoot() throws Exception {
        Path left = Files.createDirectories(temp.resolve("ignored-left"));
        Path right = Files.createDirectories(temp.resolve("ignored-right"));
        Files.writeString(left.resolve(".gitignore"), "build/\n*.log\n");
        Files.writeString(right.resolve(".gitignore"), "generated/\n");
        Files.createDirectories(left.resolve(".git/objects"));
        Files.createDirectories(right.resolve(".git/objects"));
        Files.createDirectories(left.resolve("build"));
        Files.createDirectories(right.resolve("build"));
        Files.createDirectories(left.resolve("generated"));
        Files.createDirectories(right.resolve("generated"));
        Files.writeString(left.resolve(".git/objects/left"), "left");
        Files.writeString(right.resolve(".git/objects/right"), "right");
        Files.writeString(left.resolve("build/left.txt"), "left");
        Files.writeString(right.resolve("build/right.txt"), "right");
        Files.writeString(left.resolve("generated/left.txt"), "left");
        Files.writeString(right.resolve("generated/right.txt"), "right");
        Files.writeString(left.resolve("debug.log"), "left");
        Files.writeString(right.resolve("debug.log"), "right");
        Files.writeString(left.resolve("kept.txt"), "before");
        Files.writeString(right.resolve("kept.txt"), "after");

        DirectoryDiff.Result result = DirectoryDiff.compare(left, right);

        assertEquals(
                List.of(".gitignore", "kept.txt"),
                result.entries().stream().map(DirectoryDiff.Entry::relativePath).toList());
    }

    @Test
    void comparesTheTargetsOfSymlinkedRoots() throws Exception {
        Path v1 = Files.createDirectories(temp.resolve("v1"));
        Path v2 = Files.createDirectories(temp.resolve("v2"));
        Files.writeString(v1.resolve("app.conf"), "port=1\n");
        Files.writeString(v2.resolve("app.conf"), "port=2\n");
        Files.writeString(v2.resolve("new.conf"), "new\n");
        Path previous = temp.resolve("previous");
        Path current = temp.resolve("current");
        try {
            Files.createSymbolicLink(previous, v1);
            Files.createSymbolicLink(current, v2);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("symbolic links are not available here");
        }

        List<DirectoryDiff.Entry> expected = List.of(
                new DirectoryDiff.Entry("app.conf", DirectoryDiff.Kind.MODIFIED, 7, 7),
                new DirectoryDiff.Entry("new.conf", DirectoryDiff.Kind.RIGHT_ONLY, -1, 4));
        assertEquals(expected, DirectoryDiff.compare(previous, current).entries());
        assertEquals(expected, DirectoryDiff.compare(previous, v2).entries());
        assertEquals(expected, DirectoryDiff.compare(v1, current).entries());
    }

    /** A capped scan must not report a file as one-sided only because the other side's scan stopped early. */
    @Test
    void aTruncatedScanDoesNotInventOneSidedFiles() throws Exception {
        Path left = Files.createDirectories(temp.resolve("cap-left"));
        Path right = Files.createDirectories(temp.resolve("cap-right"));
        for (int i = 0; i < 5; i++) {
            Files.writeString(left.resolve("a-shared-" + i + ".txt"), "same");
            Files.writeString(right.resolve("a-shared-" + i + ".txt"), "same");
        }
        // The right scan stops after five of these 205 files, so it cannot have reached every shared one.
        for (int i = 0; i < 200; i++) {
            Files.writeString(right.resolve(String.format("z-extra-%03d.txt", i)), "extra");
        }

        DirectoryDiff.Result result = DirectoryDiff.compare(left, right, 5);

        assertTrue(result.truncated());
        assertEquals(
                List.of(),
                result.entries().stream()
                        .filter(e -> e.kind() != DirectoryDiff.Kind.RIGHT_ONLY)
                        .toList());
    }
}
