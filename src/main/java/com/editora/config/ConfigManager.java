package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Loads and saves {@link Settings} as JSON in {@code ~/.editora-v2/settings.json} (the same location
 * on macOS, Linux, and Windows, where {@code user.home} maps to the user profile).
 * Missing or malformed config falls back to built-in defaults rather than failing.
 */
public class ConfigManager {

    static final String APP_DIR_NAME = ".editora-v2";
    static final String CONFIG_FILE_NAME = "settings.json";

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path configDir;
    private Settings settings = new Settings();

    public ConfigManager() {
        this(defaultConfigDir());
    }

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Path getConfigFile() {
        return configDir.resolve(CONFIG_FILE_NAME);
    }

    public Settings getSettings() {
        return settings;
    }

    /** Reads config.json, merging stored values onto defaults. Falls back to defaults on any error. */
    public Settings load() {
        Path file = getConfigFile();
        if (!Files.isReadable(file)) {
            settings = new Settings();
            return settings;
        }
        try {
            settings = mapper.readerForUpdating(new Settings()).readValue(Files.readString(file));
        } catch (IOException e) {
            settings = new Settings();
        }
        return settings;
    }

    public void save() {
        try {
            Files.createDirectories(configDir);
            mapper.writeValue(getConfigFile().toFile(), settings);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write " + getConfigFile(), e);
        }
    }

    /** Resolves {@code ~/.editora-v2} ({@code user.home} is the user profile on every platform). */
    static Path defaultConfigDir() {
        return Path.of(System.getProperty("user.home", "."), APP_DIR_NAME);
    }
}
