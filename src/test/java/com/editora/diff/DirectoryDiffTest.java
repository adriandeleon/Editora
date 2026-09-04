package com.editora.diff;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
