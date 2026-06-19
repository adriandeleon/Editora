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
 * Persistent Find-in-Files query history in {@code <configDir>/search-history.json}. Most-recent first,
 * deduplicated, capped at {@link #MAX_ENTRIES}. Mirrors {@link RecentFiles}; the backing
 * {@link ObservableList} lets the panel's query combo update automatically. Stored as a versioned object
 * {@code { "schemaVersion": 1, "queries": [ … ] }}.
 */
public class SearchHistory {

    public static final int MAX_ENTRIES = 30;
    public static final int SCHEMA_VERSION = 1;

    static final String FILE_NAME = "search-history.json";

    /** Serialized form of {@code search-history.json}: a version stamp plus the query strings. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stored {
        public int schemaVersion = SCHEMA_VERSION;
        public List<String> queries = new ArrayList<>();
    }

    private final Path file;
    private final ObservableList<String> queries = FXCollections.observableArrayList();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public SearchHistory(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
        load();
    }

    public ObservableList<String> getList() {
        return queries;
    }

    /** Move {@code query} to the top (deduplicated, exact match), trim to MAX_ENTRIES, persist. */
    public void add(String query) {
        if (query == null || query.isEmpty()) {
            return;
        }
        queries.remove(query);
        queries.add(0, query);
        while (queries.size() > MAX_ENTRIES) {
            queries.remove(queries.size() - 1);
        }
        save();
    }

    public void clear() {
        if (!queries.isEmpty()) {
            queries.clear();
            save();
        }
    }

    private void load() {
        Stored stored = ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.SEARCH_HISTORY);
        queries.setAll(stored.queries.stream()
                .filter(s -> s != null && !s.isEmpty())
                .limit(MAX_ENTRIES)
                .toList());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Stored stored = new Stored();
            stored.queries = new ArrayList<>(queries);
            mapper.writeValue(file.toFile(), stored);
        } catch (IOException e) {
            // Best effort.
        }
    }
}
