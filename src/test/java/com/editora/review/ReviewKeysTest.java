package com.editora.review;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** {@link ReviewKeys}: a repository and its files as {@code reviews.json} names them. */
class ReviewKeysTest {

    @TempDir
    Path dir;

    @Test
    void aFileIsNamedRelativeToItsRepositoryWithForwardSlashes() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path file = Files.createDirectories(repo.resolve("src/main")).resolve("A.java");
        Files.writeString(file, "x");
        assertEquals("src/main/A.java", ReviewKeys.relativePath(repo, file));
        assertNull(ReviewKeys.relativePath(repo, dir.resolve("elsewhere.txt")), "outside the repository");
        assertNull(ReviewKeys.relativePath(repo, repo), "the root is not a file in it");
        assertEquals(file, ReviewKeys.resolve(repo, "src/main/A.java"));
        assertEquals(ReviewKeys.repoKey(repo), ReviewKeys.repoKey(repo.resolve("src/..")), "one key per repository");
    }
}
