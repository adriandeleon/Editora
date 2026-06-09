package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;

/**
 * Loads and saves the user config in the config directory: the {@code EDITORA_CONFIG_DIR} environment
 * variable when set, otherwise {@code ~/.editora/} ({@code user.home} maps to the user profile on
 * macOS, Linux, and Windows). Preferences ({@link Settings}) are stored as TOML in
 * {@code settings.toml}; session state ({@link WorkspaceState}: fold regions, tool-window layout) is
 * stored as JSON in {@code workspace-state.json}. Missing or malformed files fall back to defaults.
 */
public class ConfigManager {

    static final String APP_DIR_NAME = ".editora";
    /** Dev-mode config dir (--dev), kept separate from the production config so they don't interfere. */
    static final String APP_DIR_NAME_DEV = ".editora-dev";
    static final String SETTINGS_FILE_NAME = "settings.toml";
    static final String WORKSPACE_FILE_NAME = "workspace-state.json";
    static final String BOOKMARKS_FILE_NAME = "bookmarks.json";
    static final String BREAKPOINTS_FILE_NAME = "breakpoints.json";
    static final String NOTES_FILE_NAME = "notes.json";
    static final String DICTIONARY_FILE_NAME = "dictionary.txt";
    static final String PROJECTS_DIR_NAME = "projects";

    /** TOML for preferences. */
    private final TomlMapper toml = new TomlMapper();
    /** Pretty JSON for session/state data. */
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configDir;
    /** Whether this instance was started in dev mode ({@code --dev}); surfaced in the UI (toolbar badge). */
    private final boolean dev;
    private Settings settings = new Settings();
    private WorkspaceState workspaceState = new WorkspaceState();
    /** Global bookmarks (all files/projects), stored in {@code bookmarks.json} — see {@link BookmarkStore}. */
    private BookmarkStore bookmarkStore = new BookmarkStore();
    /** Personal Notes (all files/projects), stored in {@code notes.json} — see {@link NoteStore}. */
    private NoteStore noteStore = new NoteStore();
    /** Breakpoints (all files/projects), stored in {@code breakpoints.json} — see {@link BreakpointStore}. */
    private BreakpointStore breakpointStore = new BreakpointStore();
    /** User-added spell-check words (one per line in {@code dictionary.txt}); lower-cased, shared globally. */
    private final java.util.Set<String> userDictionary = new java.util.LinkedHashSet<>();
    /** The session-state file currently in use — the default, or a project's state file. */
    private Path workspaceStateFile;

    public ConfigManager() {
        this(defaultConfigDir(), false);
    }

    /** Uses the default config dir, or {@code ~/.editora-dev} when {@code dev} (the {@code --dev} flag). */
    public ConfigManager(boolean dev) {
        this(defaultConfigDir(dev), dev);
    }

    public ConfigManager(Path configDir) {
        this(configDir, false);
    }

    private ConfigManager(Path configDir, boolean dev) {
        this.configDir = configDir;
        this.dev = dev;
        this.workspaceStateFile = configDir.resolve(WORKSPACE_FILE_NAME);
    }

    /** True when started in dev mode ({@code --dev}); the UI shows a "dev mode" badge in this case. */
    public boolean isDev() {
        return dev;
    }

    public Path getConfigDir() {
        return configDir;
    }

    /**
     * Zips the active config directory into a timestamped {@code .zip} in the user's home directory
     * and returns the created file (e.g. {@code ~/editora-config-2026-06-04_153012.zip}). Backs up
     * whichever config dir is in use ({@code ~/.editora}, {@code ~/.editora-dev}, or a
     * {@code --config-dir} override).
     *
     * @throws IOException if the home directory can't be written or a config file can't be read
     */
    public Path exportConfig() throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        return ConfigExporter.export(configDir, home, com.editora.AppInfo.VERSION,
                System.getProperty("user.name"), java.time.LocalDateTime.now());
    }

    public Path getSettingsFile() {
        return configDir.resolve(SETTINGS_FILE_NAME);
    }

    public Path getWorkspaceStateFile() {
        return workspaceStateFile;
    }

    /**
     * Switches the active session-state file (e.g. to a project's state) and reloads it into
     * {@link #getWorkspaceState()} — a missing/malformed file yields a fresh {@link WorkspaceState}.
     * Subsequent {@link #save()} calls write back to this file.
     */
    public void setWorkspaceStateFile(Path file) {
        this.workspaceStateFile = file;
        this.workspaceState = ConfigMigrations.readVersioned(file, json, new WorkspaceState(), ConfigSchema.WORKSPACE);
    }

    /** Points the session back at the default {@code workspace-state.json} (no project) and reloads it. */
    public void useDefaultWorkspaceStateFile() {
        setWorkspaceStateFile(configDir.resolve(WORKSPACE_FILE_NAME));
    }

    public Settings getSettings() {
        return settings;
    }

    public WorkspaceState getWorkspaceState() {
        return workspaceState;
    }

    public Path getBookmarksFile() {
        return configDir.resolve(BOOKMARKS_FILE_NAME);
    }

    public Path getNotesFile() {
        return configDir.resolve(NOTES_FILE_NAME);
    }

    public Path getBreakpointsFile() {
        return configDir.resolve(BREAKPOINTS_FILE_NAME);
    }

    /**
     * Personal Notes (canonical file path -> notes) for the <em>active</em> project — bucket chosen by the
     * current session file, exactly like {@link #getBookmarks()}. Persist changes with {@link #saveNotes()}.
     */
    public Map<String, List<PersonalNote>> getNotes() {
        return noteStore.bucket(currentBookmarkKey());
    }

    /** Removes a project's entire notes bucket (called when the project is deleted) and persists. */
    public void deleteNotesForProject(String projectKey) {
        if (noteStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveNotes();
        }
    }

    /**
     * The bookmark map (absolute file path -> bookmarks) for the <em>active</em> project — the bucket is
     * chosen by the current session file, so switching projects (via {@link #setWorkspaceStateFile} /
     * {@link #useDefaultWorkspaceStateFile}) automatically swaps which bookmarks are visible. Persist
     * changes with {@link #saveBookmarks()}.
     */
    public Map<String, List<Bookmark>> getBookmarks() {
        return bookmarkStore.bucket(currentBookmarkKey());
    }

    /** Removes a project's entire bookmark bucket (called when the project is deleted) and persists. */
    public void deleteBookmarksForProject(String projectKey) {
        if (bookmarkStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveBookmarks();
        }
    }

    /**
     * The breakpoint map (absolute file path -> breakpoints) for the <em>active</em> project — bucket
     * chosen by the current session file, exactly like {@link #getBookmarks()}. Persist changes with
     * {@link #saveBreakpoints()}.
     */
    public Map<String, List<Breakpoint>> getBreakpoints() {
        return breakpointStore.bucket(currentBookmarkKey());
    }

    /** Removes a project's entire breakpoint bucket (called when the project is deleted) and persists. */
    public void deleteBreakpointsForProject(String projectKey) {
        if (breakpointStore.getByProject().remove(projectKey == null ? "" : projectKey) != null) {
            saveBreakpoints();
        }
    }

    /**
     * The project key for the active session: {@code ""} for the default {@code workspace-state.json}
     * (no project), otherwise the project id (the {@code projects/<id>.json} base name). Bookmarks are
     * bucketed by this key in {@code bookmarks.json}.
     */
    private String currentBookmarkKey() {
        Path name = workspaceStateFile.getFileName();
        if (name == null) {
            return "";
        }
        String file = name.toString();
        if (file.equals(WORKSPACE_FILE_NAME)) {
            return "";
        }
        return file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : file;
    }

    /** Reads all config files, merging stored values onto defaults. Falls back to defaults on any error. */
    public Settings load() {
        settings = ConfigMigrations.readVersioned(getSettingsFile(), toml, new Settings(), ConfigSchema.SETTINGS);
        workspaceState = ConfigMigrations.readVersioned(
                getWorkspaceStateFile(), json, new WorkspaceState(), ConfigSchema.WORKSPACE);
        loadBookmarks();
        loadBreakpoints();
        loadNotes();
        loadUserDictionary();
        return settings;
    }

    /** The user's added spell-check words (lower-cased). Mutated in place; persisted by {@link #addUserWord}. */
    public java.util.Set<String> getUserDictionary() {
        return userDictionary;
    }

    public Path getUserDictionaryFile() {
        return configDir.resolve(DICTIONARY_FILE_NAME);
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
            Files.writeString(getUserDictionaryFile(), w + System.lineSeparator(),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            // non-fatal: the word still applies for this session, just isn't persisted
        }
    }

    private void loadUserDictionary() {
        userDictionary.clear();
        Path file = getUserDictionaryFile();
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(file)) {
                String w = line.strip().toLowerCase(java.util.Locale.ROOT);
                if (!w.isEmpty()) {
                    userDictionary.add(w);
                }
            }
        } catch (IOException ignored) {
            // missing/unreadable dictionary just means no user words
        }
    }

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

    /** Loads {@code breakpoints.json} (a fresh, empty store if it doesn't exist yet — no legacy migration). */
    private void loadBreakpoints() {
        if (Files.exists(getBreakpointsFile())) {
            breakpointStore = ConfigMigrations.readVersioned(
                    getBreakpointsFile(), json, new BreakpointStore(), ConfigSchema.BREAKPOINTS);
        } else {
            breakpointStore = new BreakpointStore();
        }
    }

    /** Migrates bookmarks out of the legacy session files into their per-project buckets, stripping each. */
    private void migrateLegacyBookmarks() {
        extractAndStripBookmarks(configDir.resolve(WORKSPACE_FILE_NAME), ""); // no-project bucket
        Path projectsDir = configDir.resolve(PROJECTS_DIR_NAME);
        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> s = Files.list(projectsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(f -> {
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
                        json.convertValue(bm, new TypeReference<LinkedHashMap<String, List<Bookmark>>>() { });
                Map<String, List<Bookmark>> into = bookmarkStore.bucket(projectKey);
                legacy.forEach(into::putIfAbsent);
            }
            obj.remove("bookmarks"); // drop the legacy node (even when empty) so it stops lingering
            json.writeValue(file.toFile(), obj);
        } catch (IOException | IllegalArgumentException e) {
            // a malformed legacy file simply contributes no bookmarks
        }
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            toml.writeValue(getSettingsFile().toFile(), settings);
            // The workspace-state file may live in a sub-dir (a project's state file).
            Files.createDirectories(workspaceStateFile.getParent());
            json.writeValue(workspaceStateFile.toFile(), workspaceState);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config to " + configDir, e);
        }
    }

    /** Writes the global bookmarks to {@code bookmarks.json}. Bookmarks are saved independently of {@link #save()}. */
    public void saveBookmarks() {
        try {
            Files.createDirectories(configDir);
            json.writeValue(getBookmarksFile().toFile(), bookmarkStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write bookmarks to " + getBookmarksFile(), e);
        }
    }

    /** Writes the breakpoints to {@code breakpoints.json}, independently of {@link #save()}. */
    public void saveBreakpoints() {
        try {
            Files.createDirectories(configDir);
            json.writeValue(getBreakpointsFile().toFile(), breakpointStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write breakpoints to " + getBreakpointsFile(), e);
        }
    }

    /** Loads {@code notes.json} (versioned, per-project buckets). Missing/malformed ⇒ an empty store. */
    private void loadNotes() {
        noteStore = ConfigMigrations.readVersioned(getNotesFile(), json, new NoteStore(), ConfigSchema.NOTES);
    }

    /** Writes the global Personal Notes to {@code notes.json}, independently of {@link #save()}. */
    public void saveNotes() {
        try {
            Files.createDirectories(configDir);
            json.writeValue(getNotesFile().toFile(), noteStore);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write notes to " + getNotesFile(), e);
        }
    }

    /**
     * The config directory: the {@code EDITORA_CONFIG_DIR} environment variable if set (used verbatim as the
     * config folder), otherwise {@code ~/.editora} (or {@code ~/.editora-dev} in dev mode). Works on
     * macOS, Linux, and Windows.
     */
    static Path defaultConfigDir() {
        return defaultConfigDir(false);
    }

    static Path defaultConfigDir(boolean dev) {
        return resolveConfigDir(System.getenv("EDITORA_CONFIG_DIR"), System.getProperty("user.home", "."), dev);
    }

    static Path resolveConfigDir(String editoraHome, String userHome) {
        return resolveConfigDir(editoraHome, userHome, false);
    }

    /**
     * Pure resolver (unit-testable): {@code editoraHome} (trimmed) when set and non-blank, else
     * {@code userHome/.editora} — or {@code userHome/.editora-dev} when {@code dev} (so the {@code --dev}
     * instance never shares config with production). The env var still wins, so it can override either.
     */
    static Path resolveConfigDir(String editoraHome, String userHome, boolean dev) {
        if (editoraHome != null && !editoraHome.isBlank()) {
            return Path.of(editoraHome.trim());
        }
        return Path.of(userHome == null || userHome.isBlank() ? "." : userHome,
                dev ? APP_DIR_NAME_DEV : APP_DIR_NAME);
    }
}
