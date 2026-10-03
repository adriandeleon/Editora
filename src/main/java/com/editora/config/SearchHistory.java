package com.editora.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Persistent Find-in-Files query history in {@code <configDir>/search-history.json}. Most-recent first,
 * deduplicated, capped at {@link #MAX_ENTRIES}. Mirrors {@link RecentFiles}; the backing
 * {@link ObservableList} reports each change as a single event. Stored as a versioned object
 * {@code { "schemaVersion": 1, "queries": [ … ] }}.
 *
 * <p>The app holds <b>one</b> instance, in {@link SharedConfig#searchHistory()}, shared by every window. A
 * window binds its query dropdown to its own copy of {@link #getList()} and refreshes that on change, rather
 * than attaching a control to a list that outlives the window.
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
    private final ConfigWriter.Sink sink;
    private final ObservableList<String> queries = FXCollections.observableArrayList();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** A standalone history that writes on the calling thread (tests, tools). The app uses {@link SharedConfig}. */
    public SearchHistory(Path configDir) {
        this(configDir, ConfigWriter.DIRECT, problem -> {});
    }

    SearchHistory(Path configDir, ConfigWriter.Sink sink, Consumer<ConfigLoadProblem> problems) {
        this.file = configDir.resolve(FILE_NAME);
        this.sink = sink;
        load(problems);
    }

    public ObservableList<String> getList() {
        return queries;
    }

    /** Move {@code query} to the top (deduplicated, exact match), trim to MAX_ENTRIES, persist. */
    public void add(String query) {
        if (query == null || query.isEmpty()) {
            return;
        }
        if (!queries.isEmpty() && query.equals(queries.get(0))) {
            return; // already the most recent — nothing to reorder or rewrite
        }
        List<String> next = new ArrayList<>(queries);
        next.remove(query);
        next.add(0, query);
        queries.setAll(next.subList(0, Math.min(next.size(), MAX_ENTRIES)));
        save();
    }

    public void clear() {
        if (!queries.isEmpty()) {
            queries.clear();
            save();
        }
    }

    private void load(Consumer<ConfigLoadProblem> problems) {
        Stored stored =
                ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.SEARCH_HISTORY, problems);
        queries.setAll(stored.queries.stream()
                .filter(s -> s != null && !s.isEmpty())
                .limit(MAX_ENTRIES)
                .toList());
    }

    private void save() {
        Stored snapshot = new Stored();
        snapshot.queries = List.copyOf(queries);
        sink.write(file, () -> mapper.writeValueAsBytes(snapshot));
    }
}
