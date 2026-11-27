package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.review.ReviewAnchors;
import com.editora.review.ReviewThreads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ProjectReviewFile}: a repository's threads in its own {@code .editora/reviews.json}. */
class ProjectReviewFileTest {

    @TempDir
    Path dir;

    @Test
    void threadsAreWrittenReadFoundFromAFileBelowAndRemovedWhenNoneAreLeft() throws Exception {
        Path repo = Files.createDirectories(dir.resolve("repo"));
        Path file = Files.createDirectories(repo.resolve("src")).resolve("A.java");
        assertNull(ProjectReviewFile.rootAbove(file), "no file yet");
        assertEquals(List.of(), ProjectReviewFile.read(repo));

        List<ReviewThread> threads = ReviewThreads.now()
                .start(
                        List.of(),
                        ReviewThreads.Start.onFile("src/A.java", ReviewAnchors.capture("a\nb\n", 1, 1)),
                        ReviewAuthor.user(),
                        "b?",
                        null);
        ProjectReviewFile.write(repo, threads);
        assertTrue(Files.isRegularFile(ProjectReviewFile.file(repo)));
        assertEquals(repo.toAbsolutePath(), ProjectReviewFile.rootAbove(file));
        assertEquals(threads, ProjectReviewFile.read(repo));

        ProjectReviewFile.write(repo, List.of());
        assertFalse(Files.exists(ProjectReviewFile.file(repo)), "nothing left: no file to commit");

        Files.createDirectories(ProjectReviewFile.file(repo).getParent());
        Files.writeString(ProjectReviewFile.file(repo), "{ not json");
        assertEquals(List.of(), ProjectReviewFile.read(repo), "unreadable: none, and logged");
    }
}
