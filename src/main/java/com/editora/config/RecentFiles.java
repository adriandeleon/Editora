package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

/**
 * Persistent list of recently-opened files in {@code <configDir>/recent-files.json}. Most-recent
 * first; capped at {@link #MAX_ENTRIES}. The backing {@link ObservableList} lets UI controls react
 * to changes automatically.
 */
public class RecentFiles {

    public static final int MAX_ENTRIES = 20;
    static final String FILE_NAME = "recent-files.json";

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
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            List<String> raw = mapper.readValue(Files.readString(file),
                    new TypeReference<List<String>>() {
                    });
            recents.setAll(raw.stream().map(Path::of).limit(MAX_ENTRIES).toList());
        } catch (IOException e) {
            // Malformed or unreadable — leave empty.
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            mapper.writeValue(file.toFile(), recents.stream().map(Path::toString).toList());
        } catch (IOException e) {
            // Best effort.
        }
    }
}
