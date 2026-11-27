package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.review.ReviewAnchors;
import com.editora.review.ReviewThreads;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code reviews.json}: written, read back by the next start, and shared by two processes on one folder. */
class ReviewStoreTest {

    @TempDir
    Path dir;

    private SharedConfig a;
    private SharedConfig b;

    @AfterEach
    void stop() {
        if (b != null) {
            b.shutdown();
        }
        if (a != null) {
            a.shutdown();
        }
    }

    private static SharedConfig open(Path dir) {
        SharedConfig s = new SharedConfig(dir, false);
        s.setFxThreadForTest(Runnable::run);
        s.claimInstance();
        new ConfigManager(s).load();
        return s;
    }

    private static List<ReviewThread> started(List<ReviewThread> threads, String path, String body) {
        TextAnchor anchor = ReviewAnchors.capture("one\ntwo\n", 1, 1);
        return ReviewThreads.now()
                .start(threads, ReviewThreads.Start.onFile(path, anchor), ReviewAuthor.user(), body, "TWO");
    }

    @Test
    void threadsSurviveARestartWithEverythingTheyHold() throws Exception {
        a = open(dir);
        a.setReviewThreads("/repo", started(List.of(), "src/A.java", "Rename this."));
        a.awaitWrites();
        String json = Files.readString(dir.resolve("reviews.json"));
        assertTrue(
                json.contains("\"schemaVersion\" : " + ReviewStore.SCHEMA_VERSION)
                        || json.contains("\"schemaVersion\":1"),
                json);
        a.shutdown();
        a = null;

        b = open(dir);
        List<ReviewThread> back = b.reviewThreads("/repo");
        assertEquals(1, back.size());
        ReviewThread t = back.get(0);
        assertEquals("src/A.java", t.path());
        assertEquals("Rename this.", t.root().body());
        assertEquals("TWO", t.root().suggestion());
        assertEquals("two", t.anchor().selectedText());
        assertEquals(List.of(), b.reviewThreads("/other"));
        b.setReviewThreads("/repo", List.of());
        assertTrue(b.allReviewThreads().isEmpty(), "an empty repository is dropped");
    }

    @Test
    void twoProcessesOnOneConfigFolderKeepEachOthersThreads() throws Exception {
        a = open(dir);
        b = open(dir);
        a.setReviewThreads("/repo-a", started(List.of(), "a.txt", "FROM-A"));
        a.awaitWrites();
        b.setReviewThreads("/repo-b", started(List.of(), "b.txt", "FROM-B"));
        b.awaitWrites();
        String json = Files.readString(dir.resolve("reviews.json"));
        assertTrue(json.contains("FROM-A") && json.contains("FROM-B"), json);
    }
}
