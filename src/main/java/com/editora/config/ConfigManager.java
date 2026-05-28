package com.editora.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * Loads and saves {@link Settings} as JSON in a platform-appropriate config directory.
 * Missing or malformed config falls back to built-in defaults rather than failing.
 */
public class ConfigManager {

    static final String APP_DIR_NAME = "Editora";
    static final String CONFIG_FILE_NAME = "config.json";

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

    /** Resolves the OS-specific config directory: %APPDATA%\Editora, ~/Library/Application Support/Editora, or $XDG_CONFIG_HOME/editora. */
    static Path defaultConfigDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = (appData != null && !appData.isBlank()) ? Path.of(appData) : Path.of(home);
            return base.resolve(APP_DIR_NAME);
        }
        if (os.contains("mac")) {
            return Path.of(home, "Library", "Application Support", APP_DIR_NAME);
        }
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(home, ".config");
        return base.resolve("editora");
    }
}
