package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import com.editora.config.ProjectReviewFile;
import com.editora.config.ReviewThread;
import com.editora.config.SharedConfig;

/**
 * Where the review threads are kept: {@code reviews.json} in the config folder (private, the default), or each
 * repository's own {@code .editora/reviews.json} when the {@code reviewsInProject} setting is on. Read on the FX
 * thread from memory only: a repository's project file is loaded off it first ({@link #loaded}), and written off it
 * by one writer thread, in order.
 */
final class ReviewStorage {

    private final SharedConfig shared;
    private final BooleanSupplier inProject;
    private final Map<String, List<ReviewThread>> projectThreads = new HashMap<>();
    private final Map<String, Path> roots = new HashMap<>();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "review-write");
        t.setDaemon(true);
        return t;
    });

    ReviewStorage(SharedConfig shared, BooleanSupplier inProject) {
        this.shared = shared;
        this.inProject = inProject;
    }

    boolean inProject() {
        return inProject.getAsBoolean();
    }

    /** The threads of the repository {@code repoKey}. */
    List<ReviewThread> threads(String repoKey) {
        return inProject() ? projectThreads.getOrDefault(repoKey, List.of()) : shared.reviewThreads(repoKey);
    }

    /** Replaces the threads of {@code repoKey} (its root is {@code root}) and writes them where they are kept. */
    void set(String repoKey, Path root, List<ReviewThread> threads) {
        if (!inProject()) {
            shared.setReviewThreads(repoKey, threads);
            return;
        }
        List<ReviewThread> copy = List.copyOf(threads);
        projectThreads.put(repoKey, copy);
        roots.put(repoKey, root);
        writer.execute(() -> ProjectReviewFile.write(root, copy));
    }

    /** The repositories that have threads, by key. */
    List<String> repoKeys() {
        return new ArrayList<>(
                inProject()
                        ? projectThreads.keySet()
                        : shared.allReviewThreads().keySet());
    }

    /** Whether {@code repoKey}'s project file has been loaded (always true when threads are kept privately). */
    boolean isLoaded(String repoKey) {
        return !inProject() || projectThreads.containsKey(repoKey);
    }

    /** {@code repoKey}'s project file as read off the FX thread; a write since then wins. */
    void loaded(String repoKey, Path root, List<ReviewThread> threads) {
        roots.putIfAbsent(repoKey, root);
        projectThreads.putIfAbsent(repoKey, List.copyOf(threads));
    }

    /** TEST SEAM — waits for the queued project-file writes. */
    void awaitWritesForTest() throws Exception {
        writer.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
    }
}
