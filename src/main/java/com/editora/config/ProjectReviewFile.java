package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jspecify.annotations.Nullable;

/**
 * A repository's review threads kept in the repository itself, {@code .editora/reviews.json}, when the user chose to
 * keep them in the project (the {@code reviewsInProject} setting): the file can be committed and shared. The same
 * shape as {@code reviews.json} ({@link ReviewStore}), with the one repository under the key {@value #KEY}. Reads
 * and writes the disk: call it off the FX thread.
 */
public final class ProjectReviewFile {

    private static final Logger LOG = Logger.getLogger(ProjectReviewFile.class.getName());

    /** The repository's key inside the file: the repository itself. */
    public static final String KEY = ".";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private ProjectReviewFile() {}

    /** Where {@code root}'s threads are kept. */
    public static Path file(Path root) {
        return root.resolve(".editora").resolve("reviews.json");
    }

    /**
     * The root of the repository above {@code file} whose {@code .editora/reviews.json} exists (the nearest), or null.
     */
    public static @Nullable Path rootAbove(Path file) {
        for (Path dir = file.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(file(dir))) {
                return dir;
            }
        }
        return null;
    }

    /** {@code root}'s threads; none when the file is missing or cannot be read (that is logged). */
    public static List<ReviewThread> read(Path root) {
        Path f = file(root);
        if (!Files.isRegularFile(f)) {
            return List.of();
        }
        try {
            ReviewStore store = JSON.readValue(f.toFile(), ReviewStore.class);
            return store == null ? List.of() : store.threads(KEY);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not read " + f, e);
            return List.of();
        }
    }

    /** Writes {@code root}'s threads (atomically); with none left the file is removed. */
    public static void write(Path root, List<ReviewThread> threads) {
        Path f = file(root);
        try {
            if (threads.isEmpty()) {
                Files.deleteIfExists(f);
                return;
            }
            Files.createDirectories(f.getParent());
            ReviewStore store = new ReviewStore();
            store.setThreads(KEY, threads);
            ConfigWriter.writeAtomic(f, JSON, store);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not write " + f, e);
        }
    }
}
