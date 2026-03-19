package org.adriandeleon.editora.persistence;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class EditoraPersistence {
    public static final String HOME_OVERRIDE_PROPERTY = "editora.home";
    public static final String DIRECTORY_NAME = ".editora";
    public static final String SETTINGS_FILE_NAME = "settings.json";
    public static final String WORKSPACE_SESSION_FILE_NAME = "workspace-session.json";
    public static final String RECENT_FILES_FILE_NAME = "recent-files.json";
    public static final int SCHEMA_VERSION = 1;

    private EditoraPersistence() {
    }

    public static Path dataDirectory() {
        String configuredHome = System.getProperty(HOME_OVERRIDE_PROPERTY);
        String home = configuredHome == null || configuredHome.isBlank()
                ? System.getProperty("user.home", ".")
                : configuredHome;
        return Path.of(home).toAbsolutePath().normalize().resolve(DIRECTORY_NAME);
    }

    public static Path settingsFile() {
        return dataDirectory().resolve(SETTINGS_FILE_NAME);
    }

    public static Path workspaceSessionFile() {
        return dataDirectory().resolve(WORKSPACE_SESSION_FILE_NAME);
    }

    public static Path recentFilesFile() {
        return dataDirectory().resolve(RECENT_FILES_FILE_NAME);
    }

    public static String persistenceFilesDescription() {
        return String.join(", ", SETTINGS_FILE_NAME, WORKSPACE_SESSION_FILE_NAME, RECENT_FILES_FILE_NAME);
    }

    public static Optional<Map<String, Object>> readJsonObject(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return Optional.of(JsonSupport.parseObject(json));
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static void writeJsonObject(Path file, Map<String, Object> values) {
        if (file == null || values == null) {
            return;
        }
        try {
            Path directory = Files.createDirectories(file.toAbsolutePath().normalize().getParent());
            Path temporaryFile = Files.createTempFile(directory, file.getFileName().toString(), ".tmp");
            Files.writeString(temporaryFile, JsonSupport.toJson(new LinkedHashMap<>(values)), StandardCharsets.UTF_8);
            moveAtomically(temporaryFile, file.toAbsolutePath().normalize());
        } catch (IOException exception) {
            throw new IllegalStateException("Could not write persistence file " + file, exception);
        }
    }

    private static void moveAtomically(Path temporaryFile, Path targetFile) throws IOException {
        try {
            Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

