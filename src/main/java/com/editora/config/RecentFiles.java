package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Persistent list of recently-opened files in {@code <configDir>/recent-files.json}. Most-recent
 * first; capped at {@link #MAX_ENTRIES}. The backing {@link ObservableList} lets UI controls react
 * to changes automatically.
 *
 * <p>Stored as a versioned object {@code { "schemaVersion": 1, "files": [ … ] }}. The legacy v0 format
 * was a bare JSON array; it is migrated to the wrapped form on read (see {@link ConfigSchema#RECENT}).
 */
public class RecentFiles {

    public static final int MAX_ENTRIES = 20;
    /** Current on-disk schema version of {@code recent-files.json} (v0 = the legacy bare JSON array). */
    public static final int SCHEMA_VERSION = 1;
    static final String FILE_NAME = "recent-files.json";

    /** Serialized form of {@code recent-files.json}: a version stamp plus the file paths. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stored {
        public int schemaVersion = SCHEMA_VERSION;
        public List<String> files = new ArrayList<>();
    }

    private final Path file;
    private final ObservableList<Path> recents = FXCollections.observableArrayList();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public RecentFiles(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
        load();
    }

    public ObservableList<Path> getList() {
        return recents;
    }

    /** Move {@code path} to the top (deduplicated), trim to MAX_ENTRIES, persist. */
    public void add(Path path) {
        if (path == null) {
            return;
        }
        recents.remove(path);
        recents.add(0, path);
        while (recents.size() > MAX_ENTRIES) {
            recents.remove(recents.size() - 1);
        }
        save();
    }

    public void remove(Path path) {
        if (recents.remove(path)) {
            save();
        }
    }

    public void clear() {
        if (!recents.isEmpty()) {
            recents.clear();
            save();
        }
    }

    private void load() {
        Stored stored = ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.RECENT);
        recents.setAll(stored.files.stream().map(Path::of).limit(MAX_ENTRIES).toList());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Stored stored = new Stored();
            stored.files = recents.stream().map(Path::toString).toList();
            mapper.writeValue(file.toFile(), stored);
        } catch (IOException e) {
            // Best effort.
        }
    }
}
