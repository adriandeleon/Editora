package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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
    static final String SETTINGS_FILE_NAME = "settings.toml";
    static final String WORKSPACE_FILE_NAME = "workspace-state.json";

    /** TOML for preferences. */
    private final TomlMapper toml = new TomlMapper();
    /** Pretty JSON for session/state data. */
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configDir;
    private Settings settings = new Settings();
    private WorkspaceState workspaceState = new WorkspaceState();
    /** The session-state file currently in use — the default, or a project's state file. */
    private Path workspaceStateFile;

    public ConfigManager() {
        this(defaultConfigDir());
    }

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
        this.workspaceStateFile = configDir.resolve(WORKSPACE_FILE_NAME);
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Path getSettingsFile() {
        return configDir.resolve(SETTINGS_FILE_NAME);
    }

    /** The settings file under the default config dir — for display (e.g. the About dialog). */
    public static Path defaultSettingsFile() {
        return defaultConfigDir().resolve(SETTINGS_FILE_NAME);
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

    /** Reads both files, merging stored values onto defaults. Falls back to defaults on any error. */
    public Settings load() {
        settings = read(getSettingsFile(), toml, new Settings());
        workspaceState = read(getWorkspaceStateFile(), json, new WorkspaceState());
        return settings;
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

    /**
     * The config directory: the {@code EDITORA_CONFIG_DIR} environment variable if set (used verbatim as the
     * config folder), otherwise {@code ~/.editora}. Works on macOS, Linux, and Windows.
     */
    static Path defaultConfigDir() {
        return resolveConfigDir(System.getenv("EDITORA_CONFIG_DIR"), System.getProperty("user.home", "."));
    }

    /**
     * Pure resolver (unit-testable): {@code editoraHome} (trimmed) when set and non-blank, else
     * {@code userHome/.editora}.
     */
    static Path resolveConfigDir(String editoraHome, String userHome) {
        if (editoraHome != null && !editoraHome.isBlank()) {
            return Path.of(editoraHome.trim());
        }
        return Path.of(userHome == null || userHome.isBlank() ? "." : userHome, APP_DIR_NAME);
    }
}
