package com.editora.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The local code-review threads, serialized as JSON to {@code reviews.json} in the config folder: private to this
 * computer, kept per repository. {@code byRepo} maps a repository key (its root's canonical path, see
 * {@code com.editora.review.ReviewKeys}) to its threads, each with a path relative to that root. Versioned via
 * {@link #schemaVersion} (see {@code config/migration}).
 *
 * <p>A plain Jackson POJO ({@code com.editora.config} is opened to jackson.databind).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReviewStore {

    /** Current on-disk schema version of {@code reviews.json}. */
    public static final int SCHEMA_VERSION = 2;

    private int schemaVersion = SCHEMA_VERSION;

    /** Repository key → its threads, oldest first. */
    private Map<String, List<ReviewThread>> byRepo = new LinkedHashMap<>();

    /**
     * Repository key → path → a digest of the file's diff when you marked it viewed in a review tab (kept here, in
     * the config folder only: what you have looked at is yours, also when the threads are kept in the project). A
     * file whose diff no longer has that digest is unviewed again.
     */
    private Map<String, Map<String, String>> viewed = new LinkedHashMap<>();

    public Map<String, Map<String, String>> getViewed() {
        return viewed;
    }

    public void setViewed(Map<String, Map<String, String>> viewed) {
        this.viewed = viewed == null ? new LinkedHashMap<>() : viewed;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Map<String, List<ReviewThread>> getByRepo() {
        return byRepo;
    }

    public void setByRepo(Map<String, List<ReviewThread>> byRepo) {
        this.byRepo = byRepo == null ? new LinkedHashMap<>() : byRepo;
    }

    /** One repository's threads (a copy). */
    public List<ReviewThread> threads(String repoKey) {
        return List.copyOf(byRepo.getOrDefault(repoKey, List.of()));
    }

    /** Replaces one repository's threads; an empty list drops the repository. */
    public void setThreads(String repoKey, List<ReviewThread> threads) {
        if (threads == null || threads.isEmpty()) {
            byRepo.remove(repoKey);
        } else {
            byRepo.put(repoKey, new ArrayList<>(threads));
        }
    }
}
