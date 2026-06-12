package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;

/**
 * The <em>per-window</em> view of the configuration. Each window owns its own session state
 * ({@link WorkspaceState} + the {@code workspace-state.json} or {@code projects/<id>.json} file it lives
 * in) but shares all global config — preferences, bookmarks, notes, breakpoints, connections, the
 * spell dictionary, and the projects index — through a {@link SharedConfig} held <strong>by
 * reference</strong>. So every window sees the same {@link Settings} object and the same stores, and a
 * {@link #save()} from one window writes settings without clobbering another window's copy.
 *
 * <p>The config directory is the {@code EDITORA_CONFIG_DIR} environment variable when set, otherwise
 * {@code ~/.editora/} ({@code ~/.editora-dev/} in {@code --dev}). Preferences are TOML in
 * {@code settings.toml}; session state is JSON in the window's state file. Missing/malformed files fall
 * back to defaults. In single-window/test use a {@code ConfigManager} owns its own {@link SharedConfig}.
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
    static final String CONNECTIONS_FILE_NAME = "connections.json";
    static final String DICTIONARY_FILE_NAME = "dictionary.txt";
    static final String PROJECTS_DIR_NAME = "projects";

    /** Pretty JSON for this window's session-state file. */
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The shared, app-wide config (preferences + cross-project stores), shared by reference. */
    private final SharedConfig shared;
    private WorkspaceState workspaceState = new WorkspaceState();
    /** The session-state file currently in use — the default, or a project's state file. */
    private Path workspaceStateFile;

    public ConfigManager() {
        this(new SharedConfig(defaultConfigDir(), false));
    }

    /** Uses the default config dir, or {@code ~/.editora-dev} when {@code dev} (the {@code --dev} flag). */
    public ConfigManager(boolean dev) {
        this(new SharedConfig(defaultConfigDir(dev), dev));
    }

    public ConfigManager(Path configDir) {
        this(new SharedConfig(configDir, false));
    }

    /** A window over {@code shared}, pointed at the default {@code workspace-state.json} (no project). */
    public ConfigManager(SharedConfig shared) {
        this(shared, shared.getConfigDir().resolve(WORKSPACE_FILE_NAME));
    }

    /** A window over {@code shared}, pointed at a specific session-state file (e.g. a project's). */
    public ConfigManager(SharedConfig shared, Path stateFile) {
        this.shared = shared;
        this.workspaceStateFile = stateFile;
    }

    /** The shared, app-wide config backing this (and every other) window. */
    public SharedConfig shared() {
        return shared;
    }

    /** The shared projects index (one source of truth across all windows). */
    public ProjectManager projects() {
        return shared.projects();
    }

    // --- shared config: plain delegation ---

    public boolean isDev() {
        return shared.isDev();
    }

    public Path getConfigDir() {
        return shared.getConfigDir();
    }

    public Path exportConfig() throws IOException {
        return shared.exportConfig();
    }

    public Settings getSettings() {
        return shared.getSettings();
    }

    public Path getSettingsFile() {
        return shared.getSettingsFile();
    }

    public Path getBookmarksFile() {
        return shared.getBookmarksFile();
    }

    public Path getNotesFile() {
        return shared.getNotesFile();
    }

    public Path getBreakpointsFile() {
        return shared.getBreakpointsFile();
    }

    public Path getConnectionsFile() {
        return shared.getConnectionsFile();
    }

    public Path getUserDictionaryFile() {
        return shared.getUserDictionaryFile();
    }

    public List<com.editora.vfs.RemoteConnection> getConnections() {
        return shared.getConnections();
    }

    public void putConnection(com.editora.vfs.RemoteConnection conn) {
        shared.putConnection(conn);
    }

    public void removeConnection(String id) {
        shared.removeConnection(id);
    }

    public void saveConnections() {
        shared.saveConnections();
    }

    public java.util.Set<String> getUserDictionary() {
        return shared.getUserDictionary();
    }

    public void addUserWord(String word) {
        shared.addUserWord(word);
    }

    /**
     * Personal Notes (canonical file path -> notes) for <em>this window's</em> project — bucket chosen by
     * the current session file, exactly like {@link #getBookmarks()}. Persist changes with {@link #saveNotes()}.
     */
    public Map<String, List<PersonalNote>> getNotes() {
        return shared.notesBucket(currentBookmarkKey());
    }

    public void saveNotes() {
        shared.saveNotes();
    }

    /** Removes a project's entire notes bucket (called when the project is deleted) and persists. */
    public void deleteNotesForProject(String projectKey) {
        shared.deleteNotesForProject(projectKey);
    }

    /**
     * The bookmark map (absolute file path -> bookmarks) for <em>this window's</em> project — the bucket is
     * chosen by the current session file, so switching the session file ({@link #setWorkspaceStateFile} /
     * {@link #useDefaultWorkspaceStateFile}) swaps which bookmarks are visible. Persist with {@link #saveBookmarks()}.
     */
    public Map<String, List<Bookmark>> getBookmarks() {
        return shared.bookmarksBucket(currentBookmarkKey());
    }

    public void saveBookmarks() {
        shared.saveBookmarks();
    }

    /** Removes a project's entire bookmark bucket (called when the project is deleted) and persists. */
    public void deleteBookmarksForProject(String projectKey) {
        shared.deleteBookmarksForProject(projectKey);
    }

    /**
     * The breakpoint map (absolute file path -> breakpoints) for <em>this window's</em> project — bucket
     * chosen by the current session file, exactly like {@link #getBookmarks()}. Persist with {@link #saveBreakpoints()}.
     */
    public Map<String, List<Breakpoint>> getBreakpoints() {
        return shared.breakpointsBucket(currentBookmarkKey());
    }

    public void saveBreakpoints() {
        shared.saveBreakpoints();
    }

    /** Removes a project's entire breakpoint bucket (called when the project is deleted) and persists. */
    public void deleteBreakpointsForProject(String projectKey) {
        shared.deleteBreakpointsForProject(projectKey);
    }

    // --- per-window session state ---

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
        setWorkspaceStateFile(getConfigDir().resolve(WORKSPACE_FILE_NAME));
    }

    public WorkspaceState getWorkspaceState() {
        return workspaceState;
    }

    /**
     * The project key for this window's session: {@code ""} for the default {@code workspace-state.json}
     * (no project), otherwise the project id (the {@code projects/<id>.json} base name). The bucketed
     * stores are keyed by this.
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

    /** Reads all config (shared + this window's session), merging stored values onto defaults. */
    public Settings load() {
        shared.load();
        workspaceState = ConfigMigrations.readVersioned(
                workspaceStateFile, json, new WorkspaceState(), ConfigSchema.WORKSPACE);
        return shared.getSettings();
    }

    /** Writes the shared preferences ({@code settings.toml}) and this window's session-state file. */
    public void save() {
        shared.saveSettings();
        try {
            // The workspace-state file may live in a sub-dir (a project's state file).
            Files.createDirectories(workspaceStateFile.getParent());
            json.writeValue(workspaceStateFile.toFile(), workspaceState);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write session state to " + workspaceStateFile, e);
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
