package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;

/**
 * Loads and saves the user config in {@code ~/.editora/} ({@code user.home} maps to the user
 * profile on macOS, Linux, and Windows). Preferences ({@link Settings}) are stored as TOML in
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

    public ConfigManager() {
        this(defaultConfigDir());
    }

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
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
        return configDir.resolve(WORKSPACE_FILE_NAME);
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
            json.writeValue(getWorkspaceStateFile().toFile(), workspaceState);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write config to " + configDir, e);
        }
    }

    /** Resolves {@code ~/.editora} ({@code user.home} is the user profile on every platform). */
    static Path defaultConfigDir() {
        return Path.of(System.getProperty("user.home", "."), APP_DIR_NAME);
    }
}
