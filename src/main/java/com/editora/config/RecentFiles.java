package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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
        // De-dupe by the storable string, not Path.equals — a remote (SFTP) Path.equals against a local
        // Path throws ProviderMismatchException, so a mixed local/remote list can't be compared directly.
        String key = com.editora.vfs.Vfs.toStorableString(path);
        recents.removeIf(p -> com.editora.vfs.Vfs.toStorableString(p).equals(key));
        recents.add(0, path);
        while (recents.size() > MAX_ENTRIES) {
            recents.remove(recents.size() - 1);
        }
        save();
    }

    public void remove(Path path) {
        if (path == null) {
            return;
        }
        String key = com.editora.vfs.Vfs.toStorableString(path);
        if (recents.removeIf(p -> com.editora.vfs.Vfs.toStorableString(p).equals(key))) {
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
        // Local paths round-trip as plain strings; remote (sftp://) entries resolve only once their
        // connection is open (else parseStorable returns null and the entry is dropped on this load).
        recents.setAll(stored.files.stream()
                .map(com.editora.vfs.Vfs::parseStorable)
                .filter(java.util.Objects::nonNull)
                .limit(MAX_ENTRIES)
                .toList());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Stored stored = new Stored();
            stored.files =
                    recents.stream().map(com.editora.vfs.Vfs::toStorableString).toList();
            ConfigWriter.writeAtomic(file, mapper, stored);
        } catch (IOException e) {
            // Best effort.
        }
    }
}
