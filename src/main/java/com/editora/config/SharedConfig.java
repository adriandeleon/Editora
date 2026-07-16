package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * The <em>shared</em>, app-wide half of the configuration: preferences ({@link Settings}) plus the
 * cross-project stores (bookmarks, notes, breakpoints, SFTP connections, the user spell dictionary)
 * and the {@link ProjectManager} index. A single instance is created once at startup and shared
 * <strong>by reference</strong> across every open window's {@link ConfigManager}, so a save from any
 * window can never clobber another window's in-memory copy. Per-window <em>session</em> state
 * ({@link WorkspaceState} + its file) lives in {@link ConfigManager}, not here.
 *
 * <p>The bucketed stores ({@code bookmarks.json}, {@code notes.json}, {@code breakpoints.json}) are
 * keyed by a project key ({@code ""} = the no-project/global session, else the project id); the key is
 * supplied by the caller (a {@link ConfigManager} derives it from its session file).
 */
public class SharedConfig {

    /** TOML for preferences. */
    private final TomlMapper toml = new TomlMapper();
    /** Pretty JSON for the bucketed stores. */
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configDir;
    /** Whether this instance was started in dev mode ({@code --dev}); surfaced in the UI (toolbar badge). */
    private final boolean dev;
    /** Performs settings/session writes off the FX thread (async for the frequent path, flush for durable). */
    private final ConfigWriter writer = new ConfigWriter();

    private Settings settings = new Settings();
    /** Global bookmarks (all files/projects), stored in {@code bookmarks.json} — see {@link BookmarkStore}. */
    private BookmarkStore bookmarkStore = new BookmarkStore();
    /** Personal Notes (all files/projects), stored in {@code notes.json} — see {@link NoteStore}. */
    private NoteStore noteStore = new NoteStore();
    /** Breakpoints (all files/projects), stored in {@code breakpoints.json} — see {@link BreakpointStore}. */
    private BreakpointStore breakpointStore = new BreakpointStore();
    /** Local File History index (all files/projects), in {@code history/index.json} — see {@link HistoryStore}. */
    private HistoryStore historyStore = new HistoryStore();
    /** Saved SFTP connections (metadata only, no secrets), stored in {@code connections.json}. */
    private ConnectionStore connectionStore = new ConnectionStore();
    /** Plugin enable-state (id → enabled), stored in {@code plugins.json} — see {@link PluginStore}. */
    private PluginStore pluginStore = new PluginStore();
    /** Saved keyboard macros (app-global, not per-project), stored in {@code macros.json} — see {@link MacroStore}. */
    private MacroStore macroStore = new MacroStore();
    /** User-added spell-check words (one per line in {@code dictionary.txt}); lower-cased, shared globally. */
    private final java.util.Set<String> userDictionary = new java.util.LinkedHashSet<>();
    /** The shared projects index ({@code projects.json}) — one source of truth across all windows. */
    private final ProjectManager projects;

    public SharedConfig(Path configDir, boolean dev) {
        this.configDir = configDir;
        this.dev = dev;
        this.projects = new ProjectManager(configDir);
    }

    /** True when started in dev mode ({@code --dev}); the UI shows a "dev mode" badge in this case. */
    public boolean isDev() {
        return dev;
    }

    public Path getConfigDir() {
        return configDir;
    }

    /** The shared projects index, one instance for the whole app. */
    public ProjectManager projects() {
        return projects;
    }

    /** The JSON mapper used for the bucketed stores (reused by {@link ConfigManager} for session state). */
    ObjectMapper json() {
        return json;
    }

    /** Reads all shared config files, merging stored values onto defaults. Falls back to defaults on error. */
    public void load() {
        settings = ConfigMigrations.readVersioned(getSettingsFile(), toml, new Settings(), ConfigSchema.SETTINGS);
        loadBookmarks();
        loadBreakpoints();
        loadHistory();
        loadNotes();
        loadConnections();
        loadPlugins();
        loadMacros();
        loadUserDictionary();
    }

    public Settings getSettings() {
        return settings;
    }

    /** Writes preferences to {@code settings.toml} synchronously (serialize now, then block until written). */
    public void saveSettings() {
        enqueueSettings();
        writer.flush();
    }

    /** The shared off-thread writer (settings + session state route through it; see {@link ConfigWriter}). */
    /** Drops any queued write for {@code file} (it's about to be deleted — see {@link ConfigWriter#cancel}). */
    public void cancelPendingWrite(Path file) {
        writer.cancel(file);
    }

    ConfigWriter writer() {
        return writer;
    }

    /** Serializes the current preferences and queues a write to {@code settings.toml} (non-blocking). */
    void enqueueSettings() {
        writer.enqueue(getSettingsFile(), settingsBytes());
    }

    private byte[] settingsBytes() {
        try {
            return toml.writeValueAsBytes(settings);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize settings", e);
        }
    }

    /** Blocks until all queued settings/session writes have landed (a durable save, an export, or exit). */
    public void flushWrites() {
        writer.flush();
    }

    /**
     * Zips the active config directory into a timestamped {@code .zip} in the user's home directory
     * and returns the created file. Backs up whichever config dir is in use.
     */
    public Path exportConfig() throws IOException {
        writer.flush(); // make sure any pending async settings/session write is on disk before zipping
        Path home = Path.of(System.getProperty("user.home"));
        return ConfigExporter.export(
                configDir,
                home,
                com.editora.AppInfo.VERSION,
                System.getProperty("user.name"),
                java.time.LocalDateTime.now());
    }

    // --- file locations ---

    public Path getSettingsFile() {
        return configDir.resolve(ConfigManager.SETTINGS_FILE_NAME);
    }

    public Path getBookmarksFile() {
        return configDir.resolve(ConfigManager.BOOKMARKS_FILE_NAME);
    }

    public Path getNotesFile() {
        return configDir.resolve(ConfigManager.NOTES_FILE_NAME);
    }

    public Path getBreakpointsFile() {
        return configDir.resolve(ConfigManager.BREAKPOINTS_FILE_NAME);
    }

    /** The Local File History index file ({@code history/index.json}). */
    public Path getHistoryFile() {
        return configDir.resolve(ConfigManager.HISTORY_DIR_NAME).resolve(ConfigManager.HISTORY_INDEX_NAME);
    }

    /** The directory holding gzip'd revision bodies ({@code history/blobs/}). */
    public Path getHistoryBlobsDir() {
        return configDir.resolve(ConfigManager.HISTORY_DIR_NAME).resolve(ConfigManager.HISTORY_BLOBS_NAME);
    }

    public Path getConnectionsFile() {
        return configDir.resolve(ConfigManager.CONNECTIONS_FILE_NAME);
    }

    public Path getUserDictionaryFile() {
        return configDir.resolve(ConfigManager.DICTIONARY_FILE_NAME);
    }

    public Path getPluginsFile() {
        return configDir.resolve(ConfigManager.PLUGINS_FILE_NAME);
    }

    public Path getMacrosFile() {
        return configDir.resolve(ConfigManager.MACROS_FILE_NAME);
    }

    /** The plugin install root: {@code <configDir>/plugins} (each plugin lives in its own subdirectory). */
    public Path getPluginsDir() {
        return configDir.resolve(ConfigManager.PLUGINS_DIR_NAME);
    }

    // --- plugins (enable-state) ---

    public PluginStore getPluginStore() {
        return pluginStore;
    }

    private void loadPlugins() {
        if (Files.exists(getPluginsFile())) {
            pluginStore =
                    ConfigMigrations.readVersioned(getPluginsFile(), json, new PluginStore(), ConfigSchema.PLUGINS);
        } else {
            pluginStore = new PluginStore();
        }
    }

    public void savePlugins() {
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(getPluginsFile(), json, pluginStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write plugins to " + getPluginsFile(), e);
        }
    }

    // --- keyboard macros (app-global) ---

    public MacroStore getMacroStore() {
        return macroStore;
    }

    private void loadMacros() {
        if (Files.exists(getMacrosFile())) {
            macroStore = ConfigMigrations.readVersioned(getMacrosFile(), json, new MacroStore(), ConfigSchema.MACROS);
        } else {
            macroStore = new MacroStore();
        }
    }

    public void saveMacros() {
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(getMacrosFile(), json, macroStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write macros to " + getMacrosFile(), e);
        }
    }

    // --- SFTP connections ---

    public List<com.editora.vfs.RemoteConnection> getConnections() {
        return connectionStore.connections;
    }

    public void putConnection(com.editora.vfs.RemoteConnection conn) {
        connectionStore.put(conn);
        saveConnections();
    }

    public void removeConnection(String id) {
        connectionStore.remove(id);
        saveConnections();
    }

    /** Replaces the whole saved-connection list (preserving order) and persists — backs the Settings editor. */
    public void setConnections(List<com.editora.vfs.RemoteConnection> conns) {
        connectionStore.connections = new java.util.ArrayList<>(conns == null ? List.of() : conns);
        saveConnections();
    }

    public void saveConnections() {
        try {
            ConfigWriter.writeAtomic(getConnectionsFile(), json, connectionStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write connections to " + getConnectionsFile(), e);
        }
    }

    // --- bucketed stores (keyed by project key) ---

    public Map<String, List<PersonalNote>> notesBucket(String key) {
        return noteStore.bucket(key);
    }

    public void deleteNotesForProject(String projectKey) {
        if (noteStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveNotes();
        }
    }

    public Map<String, List<Bookmark>> bookmarksBucket(String key) {
        return bookmarkStore.bucket(key);
    }

    public void deleteBookmarksForProject(String projectKey) {
        if (bookmarkStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveBookmarks();
        }
    }

    public Map<String, List<Breakpoint>> breakpointsBucket(String key) {
        return breakpointStore.bucket(key);
    }

    public void deleteBreakpointsForProject(String projectKey) {
        if (breakpointStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveBreakpoints();
        }
    }

    public Map<String, List<HistoryRevision>> historyBucket(String key) {
        return historyStore.bucket(key);
    }

    /** The whole per-project history map (every project) — used to compute live blob hashes for GC. */
    public Map<String, Map<String, List<HistoryRevision>>> historyByProject() {
        return historyStore.getByProject();
    }

    public void deleteHistoryForProject(String projectKey) {
        if (historyStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveHistory();
        }
    }

    // --- user dictionary ---

    /** The user's added spell-check words (lower-cased). Mutated in place; persisted by {@link #addUserWord}. */
    public java.util.Set<String> getUserDictionary() {
        return userDictionary;
    }

    /** Adds a word to the user dictionary (lower-cased) and appends it to {@code dictionary.txt}. */
    public void addUserWord(String word) {
        if (word == null || word.isBlank()) {
            return;
        }
        String w = word.strip().toLowerCase(java.util.Locale.ROOT);
        if (!userDictionary.add(w)) {
            return; // already present
        }
        try {
            Files.createDirectories(configDir);
            Files.writeString(
                    getUserDictionaryFile(),
                    w + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // non-fatal: the word still applies for this session, just isn't persisted
        }
    }

    /** Removes a word from the user dictionary and rewrites {@code dictionary.txt}; no-op if absent. */
    public void removeUserWord(String word) {
        if (word == null) {
            return;
        }
        String w = word.strip().toLowerCase(java.util.Locale.ROOT);
        if (userDictionary.remove(w)) {
            rewriteUserDictionary();
        }
    }

    /** Rewrites {@code dictionary.txt} from the in-memory set (one word per line); non-fatal on failure.
     *  Written via temp + atomic move (the project convention): a plain {@code writeString} truncates first,
     *  so a crash / disk-full between truncate and write left the whole personal dictionary empty. */
    private void rewriteUserDictionary() {
        try {
            Files.createDirectories(configDir);
            StringBuilder sb = new StringBuilder();
            for (String w : userDictionary) {
                sb.append(w).append(System.lineSeparator());
            }
            com.editora.io.AtomicFileWrite.write(
                    getUserDictionaryFile(), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException e) {
            // non-fatal: the in-memory set still applies for this session
        }
    }

    private void loadUserDictionary() {
        userDictionary.clear();
        Path file = getUserDictionaryFile();
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            // Lenient decode: Files.readAllLines REPORTs malformed bytes and throws, which silently discarded
            // the ENTIRE personal dictionary over one bad byte — and a later remove would then rewrite the
            // file from that empty set (permanent loss). new String(bytes, UTF_8) replaces instead of throwing.
            String text = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
            for (String line : text.split("\r?\n")) {
                String w = stripBom(line).strip().toLowerCase(java.util.Locale.ROOT);
                if (!w.isEmpty()) {
                    userDictionary.add(w);
                }
            }
        } catch (IOException ignored) {
            // missing/unreadable dictionary just means no user words
        }
    }

    /** Drops a leading UTF-8 BOM — {@link String#strip()} does not (U+FEFF is {@code Cf}, not whitespace), so
     *  a BOM'd dictionary.txt left its first word permanently unmatchable. */
    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    // --- loading + saving the bucketed stores ---

    /**
     * Loads {@code bookmarks.json}. On first run (no {@code bookmarks.json} yet) it migrates any bookmarks
     * previously stored inside {@code workspace-state.json} (→ the {@code ""} / no-project bucket) and
     * each per-project {@code projects/<id>.json} session file (→ that project's bucket), strips them from
     * those files, and writes {@code bookmarks.json} so the migration runs only once.
     */
    private void loadBookmarks() {
        if (Files.exists(getBookmarksFile())) {
            bookmarkStore = ConfigMigrations.readVersioned(
                    getBookmarksFile(), json, new BookmarkStore(), ConfigSchema.BOOKMARKS);
            return;
        }
        bookmarkStore = new BookmarkStore();
        migrateLegacyBookmarks();
        saveBookmarks(); // create bookmarks.json so migration is one-time (even if empty)
    }

    private void loadConnections() {
        if (Files.exists(getConnectionsFile())) {
            connectionStore = ConfigMigrations.readVersioned(
                    getConnectionsFile(), json, new ConnectionStore(), ConfigSchema.CONNECTIONS);
        } else {
            connectionStore = new ConnectionStore();
        }
    }

    private void loadBreakpoints() {
        if (Files.exists(getBreakpointsFile())) {
            breakpointStore = ConfigMigrations.readVersioned(
                    getBreakpointsFile(), json, new BreakpointStore(), ConfigSchema.BREAKPOINTS);
        } else {
            breakpointStore = new BreakpointStore();
        }
    }

    private void loadHistory() {
        if (Files.exists(getHistoryFile())) {
            historyStore =
                    ConfigMigrations.readVersioned(getHistoryFile(), json, new HistoryStore(), ConfigSchema.HISTORY);
        } else {
            historyStore = new HistoryStore();
        }
    }

    /** Writes the Local File History index to {@code history/index.json}, independently of a session save. */
    public void saveHistory() {
        try {
            Files.createDirectories(getHistoryFile().getParent());
            ConfigWriter.writeAtomic(getHistoryFile(), json, historyStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write history to " + getHistoryFile(), e);
        }
    }

    /** Migrates bookmarks out of the legacy session files into their per-project buckets, stripping each. */
    private void migrateLegacyBookmarks() {
        extractAndStripBookmarks(configDir.resolve(ConfigManager.WORKSPACE_FILE_NAME), ""); // no-project bucket
        Path projectsDir = configDir.resolve(ConfigManager.PROJECTS_DIR_NAME);
        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> s = Files.list(projectsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(f -> {
                            String fn = f.getFileName().toString();
                            extractAndStripBookmarks(f, fn.substring(0, fn.length() - ".json".length()));
                        });
            } catch (IOException ignored) {
                // best-effort migration; a missing/unreadable projects dir just means nothing to migrate
            }
        }
    }

    /** Pulls the {@code bookmarks} node out of a legacy session file into {@code projectKey}'s bucket and rewrites it without that node. */
    private void extractAndStripBookmarks(Path file, String projectKey) {
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            JsonNode root = json.readTree(Files.readString(file));
            if (!(root instanceof ObjectNode obj)) {
                return;
            }
            if (!obj.has("bookmarks")) {
                return;
            }
            JsonNode bm = obj.get("bookmarks");
            if (bm != null && bm.isObject() && !bm.isEmpty()) {
                Map<String, List<Bookmark>> legacy =
                        json.convertValue(bm, new TypeReference<LinkedHashMap<String, List<Bookmark>>>() {});
                Map<String, List<Bookmark>> into = bookmarkStore.bucket(projectKey);
                legacy.forEach(into::putIfAbsent);
            }
            obj.remove("bookmarks"); // drop the legacy node (even when empty) so it stops lingering
            ConfigWriter.writeAtomic(file, json, obj);
        } catch (IOException | IllegalArgumentException e) {
            // a malformed legacy file simply contributes no bookmarks
        }
    }

    /** Writes the global bookmarks to {@code bookmarks.json}, independently of a session save. */
    public void saveBookmarks() {
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(getBookmarksFile(), json, bookmarkStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write bookmarks to " + getBookmarksFile(), e);
        }
    }

    /** Writes the breakpoints to {@code breakpoints.json}, independently of a session save. */
    public void saveBreakpoints() {
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(getBreakpointsFile(), json, breakpointStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write breakpoints to " + getBreakpointsFile(), e);
        }
    }

    /** Loads {@code notes.json} (versioned, per-project buckets). Missing/malformed ⇒ an empty store. */
    private void loadNotes() {
        noteStore = ConfigMigrations.readVersioned(getNotesFile(), json, new NoteStore(), ConfigSchema.NOTES);
    }

    /** Writes the global Personal Notes to {@code notes.json}, independently of a session save. */
    public void saveNotes() {
        try {
            Files.createDirectories(configDir);
            ConfigWriter.writeAtomic(getNotesFile(), json, noteStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write notes to " + getNotesFile(), e);
        }
    }
}
