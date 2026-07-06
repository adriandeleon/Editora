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
 * Persistent history of past AI Agent chat sessions in {@code <configDir>/agent-sessions.json}, so the
 * user can resume a conversation after starting a new one. Most-recently-used first, deduplicated by
 * {@code sessionId}, capped at {@link #MAX_ENTRIES}. Mirrors {@link SearchHistory}; the backing
 * {@link ObservableList} lets the resume picker update automatically. Stored as a versioned object
 * {@code { "schemaVersion": 1, "sessions": [ … ] }}.
 *
 * <p>An entry's {@code label} (derived from the session's first user prompt) is set once and never
 * overwritten on later prompts, but its {@code updatedAt} and list position bump on every prompt in that
 * session (move-to-top-on-use, mirroring {@link RecentFiles}).
 */
public class AgentSessionHistory {

    /** A remembered chat session: its ACP id, the cwd it was created in (needed to resume), a one-line
     *  title from its first prompt, and the epoch-seconds of its most recent prompt (ordering + display). */
    public record Entry(String sessionId, String cwd, String label, long updatedAt) {}

    /** Whole conversations, not lightweight strings — a shorter cap than RecentFiles/SearchHistory. */
    public static final int MAX_ENTRIES = 15;

    public static final int SCHEMA_VERSION = 1;

    static final String FILE_NAME = "agent-sessions.json";

    /** Serialized form of {@code agent-sessions.json}: a version stamp plus the session entries. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Stored {
        public int schemaVersion = SCHEMA_VERSION;
        public List<Entry> sessions = new ArrayList<>();
    }

    private final Path file;
    private final ObservableList<Entry> sessions = FXCollections.observableArrayList();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public AgentSessionHistory(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
        load();
    }

    public ObservableList<Entry> getList() {
        return sessions;
    }

    /**
     * Records a prompt in {@code sessionId}: moves that session to the top and stamps {@code updatedAt}.
     * A brand-new session is inserted with {@code candidateLabel} as its title; an existing session keeps
     * its original title ({@code candidateLabel} is ignored) but refreshes its cwd/timestamp/position.
     * Trims to {@link #MAX_ENTRIES} and persists. No-ops on a null/blank {@code sessionId}.
     *
     * <p><b>FX-thread only</b> — mutates the backing {@link ObservableList}, same rule as
     * {@link RecentFiles#add}/{@link SearchHistory#add}.
     */
    public void remember(String sessionId, String cwd, String candidateLabel, long updatedAt) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        String existingLabel = null;
        for (Entry e : sessions) {
            if (sessionId.equals(e.sessionId())) {
                existingLabel = e.label();
                break;
            }
        }
        sessions.removeIf(e -> sessionId.equals(e.sessionId()));
        String label = existingLabel != null ? existingLabel : candidateLabel;
        sessions.add(0, new Entry(sessionId, cwd, label, updatedAt));
        while (sessions.size() > MAX_ENTRIES) {
            sessions.remove(sessions.size() - 1);
        }
        save();
    }

    public void clear() {
        if (!sessions.isEmpty()) {
            sessions.clear();
            save();
        }
    }

    private void load() {
        Stored stored = ConfigMigrations.readVersioned(file, mapper, new Stored(), ConfigSchema.AGENT_SESSIONS);
        sessions.setAll(stored.sessions.stream()
                .filter(e ->
                        e != null && e.sessionId() != null && !e.sessionId().isBlank())
                .limit(MAX_ENTRIES)
                .toList());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Stored stored = new Stored();
            stored.sessions = new ArrayList<>(sessions);
            mapper.writeValue(file.toFile(), stored);
        } catch (IOException e) {
            // Best effort.
        }
    }
}
