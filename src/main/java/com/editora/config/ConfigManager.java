package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.migration.ConfigMigrations;
import com.editora.config.migration.ConfigSchema;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

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
    static final String PLUGINS_FILE_NAME = "plugins.json";
    static final String PLUGINS_DIR_NAME = "plugins";
    static final String MACROS_FILE_NAME = "macros.json";
    static final String TRUST_FILE_NAME = "trusted-folders.json";
    static final String PROJECTS_DIR_NAME = "projects";
    /** Local File History lives under {@code history/}: a small {@code index.json} + {@code blobs/} bodies. */
    static final String HISTORY_DIR_NAME = "history";

    static final String HISTORY_INDEX_NAME = "index.json";
    static final String HISTORY_BLOBS_NAME = "blobs";

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

    /** The personal-dictionary file (the user's added words, one per line). May not exist until a word is added. */
    public Path getDictionaryFile() {
        return getConfigDir().resolve(DICTIONARY_FILE_NAME);
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

    /** The plugin install root ({@code <configDir>/plugins}); each plugin is a subdirectory. */
    public Path getPluginsDir() {
        return shared.getPluginsDir();
    }

    /** The shared plugin enable-state store ({@code plugins.json}). */
    public PluginStore getPluginStore() {
        return shared.getPluginStore();
    }

    /** Persists the plugin enable-state. */
    public void savePlugins() {
        shared.savePlugins();
    }

    /** The shared trusted-workspace-root store ({@code trusted-folders.json}). */
    public TrustStore getTrustStore() {
        return shared.getTrustStore();
    }

    /** Persists the trusted workspace roots. */
    public void saveTrust() {
        shared.saveTrust();
    }

    /** The shared keyboard-macro store ({@code macros.json}). */
    public MacroStore getMacroStore() {
        return shared.getMacroStore();
    }

    /** Persists the saved keyboard macros. */
    public void saveMacros() {
        shared.saveMacros();
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

    public void setConnections(List<com.editora.vfs.RemoteConnection> conns) {
        shared.setConnections(conns);
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

    public void removeUserWord(String word) {
        shared.removeUserWord(word);
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

    /** Every project's bookmark buckets ({@code projectKey → (path → bookmarks)}) — the cross-project panel view. */
    public Map<String, Map<String, List<Bookmark>>> getAllBookmarks() {
        return shared.allBookmarks();
    }

    /** Every project's note buckets ({@code projectKey → (path → notes)}) — the cross-project panel view. */
    public Map<String, Map<String, List<PersonalNote>>> getAllNotes() {
        return shared.allNotes();
    }

    /** This window's project key: {@code ""} for the global/no-project (or untitled) session, else the project id.
     *  The key the Bookmarks/Notes panels treat as "current" when grouping by project. */
    public String currentProjectKey() {
        return currentBookmarkKey();
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

    /**
     * The Local File History map (absolute file path -> revisions, newest-first) for <em>this window's</em>
     * project — bucket chosen by the current session file, exactly like {@link #getBookmarks()}. Persist
     * with {@link #saveHistory()}.
     */
    public Map<String, List<HistoryRevision>> getHistory() {
        return shared.historyBucket(currentBookmarkKey());
    }

    /** The whole per-project history map (across all projects) — for computing live blob hashes. */
    public Map<String, Map<String, List<HistoryRevision>>> getHistoryByProject() {
        return shared.historyByProject();
    }

    public void saveHistory() {
        shared.saveHistory();
    }

    /** Removes a project's entire history bucket (called when the project is deleted) and persists. */
    public void deleteHistoryForProject(String projectKey) {
        shared.deleteHistoryForProject(projectKey);
    }

    /** The directory holding gzip'd Local File History revision bodies ({@code history/blobs/}). */
    public Path getHistoryBlobsDir() {
        return shared.getHistoryBlobsDir();
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
     * The project key for this window's session: the project id (the {@code projects/<id>.json} base name)
     * for a project window, else {@code ""} (the no-project bucket). Crucially, an untitled "New Window"
     * session lives in {@code windows/<uuid>.json} but still maps to {@code ""} — so every no-project window
     * (the global one and any untitled ones) shares the global bookmark/note bucket. Only files directly
     * under {@code projects/} get their own bucket. The bucketed stores are keyed by this.
     */
    private String currentBookmarkKey() {
        Path name = workspaceStateFile.getFileName();
        if (name == null) {
            return "";
        }
        Path parent = workspaceStateFile.getParent();
        String dir = parent == null || parent.getFileName() == null
                ? ""
                : parent.getFileName().toString();
        if (!ProjectManager.PROJECTS_DIR.equals(dir)) {
            return ""; // the global workspace-state.json OR an untitled window's windows/<uuid>.json
        }
        String file = name.toString();
        return file.endsWith(".json") ? file.substring(0, file.length() - ".json".length()) : file;
    }

    /** Reads all config (shared + this window's session), merging stored values onto defaults. */
    public Settings load() {
        shared.load();
        workspaceState =
                ConfigMigrations.readVersioned(workspaceStateFile, json, new WorkspaceState(), ConfigSchema.WORKSPACE);
        return shared.getSettings();
    }

    /** Writes the shared preferences ({@code settings.toml}) and this window's session-state file. */
    /** Writes preferences + this window's session state synchronously (blocks until both are on disk). */
    public void save() {
        shared.enqueueSettings();
        enqueueWorkspace();
        shared.flushWrites();
    }

    /**
     * Writes preferences + session state <em>off the FX thread</em>: a consistent snapshot is serialized on
     * the caller thread (the FX thread is single-threaded, so this needs no locking), then written by the
     * shared {@link ConfigWriter}. Used by the frequent, coalesced in-session save
     * ({@code MainController.requestSave}); {@link #save()} is the durable (blocking) form for quit / one-off
     * actions / export.
     */
    public void saveAsync() {
        shared.enqueueSettings();
        enqueueWorkspace();
    }

    /** Queues this window's session state ({@code workspace-state.json}, possibly in a project sub-dir). */
    private void enqueueWorkspace() {
        shared.writer().enqueue(workspaceStateFile, workspaceBytes());
    }

    private byte[] workspaceBytes() {
        try {
            return json.writeValueAsBytes(workspaceState);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new UncheckedIOException("Failed to serialize session state for " + workspaceStateFile, e);
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
        return Path.of(userHome == null || userHome.isBlank() ? "." : userHome, dev ? APP_DIR_NAME_DEV : APP_DIR_NAME);
    }
}
