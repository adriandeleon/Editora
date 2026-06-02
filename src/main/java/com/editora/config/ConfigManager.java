package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

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
        this.workspaceState = read(file, json, new WorkspaceState());
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

    /** The global bookmark map (absolute file path -> bookmarks). Persist changes with {@link #saveBookmarks()}. */
    public Map<String, List<Bookmark>> getBookmarks() {
        return bookmarkStore.getBookmarks();
    }

    /** Reads all config files, merging stored values onto defaults. Falls back to defaults on any error. */
    public Settings load() {
        settings = read(getSettingsFile(), toml, new Settings());
        workspaceState = read(getWorkspaceStateFile(), json, new WorkspaceState());
        loadBookmarks();
        return settings;
    }

    /**
     * Loads the global {@code bookmarks.json}. On first run (no {@code bookmarks.json} yet) it migrates
     * any bookmarks previously stored inside {@code workspace-state.json} and the per-project
     * {@code projects/*.json} session files into the global store, strips them from those files, and
     * writes {@code bookmarks.json} so the migration runs only once.
     */
    private void loadBookmarks() {
        if (Files.exists(getBookmarksFile())) {
            bookmarkStore = read(getBookmarksFile(), json, new BookmarkStore());
            return;
        }
        bookmarkStore = new BookmarkStore();
        migrateLegacyBookmarks(bookmarkStore.getBookmarks());
        saveBookmarks(); // create bookmarks.json so migration is one-time (even if empty)
    }

    /** Merges bookmarks out of the legacy session files into {@code into}, stripping them from each. */
    private void migrateLegacyBookmarks(Map<String, List<Bookmark>> into) {
        List<Path> sessionFiles = new ArrayList<>();
        sessionFiles.add(configDir.resolve(WORKSPACE_FILE_NAME));
        Path projectsDir = configDir.resolve(PROJECTS_DIR_NAME);
        if (Files.isDirectory(projectsDir)) {
            try (Stream<Path> s = Files.list(projectsDir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().forEach(sessionFiles::add);
            } catch (IOException ignored) {
                // best-effort migration; a missing/unreadable projects dir just means nothing to migrate
            }
        }
        for (Path f : sessionFiles) {
            extractAndStripBookmarks(f, into);
        }
    }

    /** Pulls the {@code bookmarks} node out of a legacy session file into {@code into} and rewrites it without that node. */
    private void extractAndStripBookmarks(Path file, Map<String, List<Bookmark>> into) {
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
                legacy.forEach((path, marks) -> mergeBookmarkList(into, path, marks));
            }
            obj.remove("bookmarks"); // drop the legacy node (even when empty) so it stops lingering
            json.writeValue(file.toFile(), obj);
        } catch (IOException | IllegalArgumentException e) {
            // a malformed legacy file simply contributes no bookmarks
        }
    }

    /** Unions {@code add} into {@code into[path]}, de-duplicating by line (keeps the already-present entry). */
    private static void mergeBookmarkList(Map<String, List<Bookmark>> into, String path, List<Bookmark> add) {
        if (add == null || add.isEmpty()) {
            return;
        }
        List<Bookmark> existing = into.computeIfAbsent(path, k -> new ArrayList<>());
        Set<Integer> lines = new HashSet<>();
        existing.forEach(b -> lines.add(b.line()));
        for (Bookmark b : add) {
            if (lines.add(b.line())) {
                existing.add(b);
            }
        }
    }

    /** Reads {@code file} with {@code mapper}, merging onto {@code defaults}; returns defaults on error. */
    private static <T> T read(Path file, ObjectMapper mapper, T defaults) {
        if (!Files.isReadable(file)) {
            return defaults;
        }
        try {
            return mapper.readerForUpdating(defaults).readValue(Files.readString(file));
        } catch (IOException e) {
            return defaults;
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
