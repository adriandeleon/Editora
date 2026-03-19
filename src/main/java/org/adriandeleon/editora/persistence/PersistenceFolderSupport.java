package org.adriandeleon.editora.persistence;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public final class PersistenceFolderSupport {
    private PersistenceFolderSupport() {
    }

    public static boolean openDirectory(Path directory) {
        if (directory == null) {
            return false;
        }

        Path targetDirectory = directory.toAbsolutePath().normalize();
        try {
            Files.createDirectories(targetDirectory);
        } catch (IOException exception) {
            return false;
        }

        if (Desktop.isDesktopSupported()) {
            try {
                Desktop desktop = Desktop.getDesktop();
                if (desktop.isSupported(Desktop.Action.OPEN)) {
                    desktop.open(targetDirectory.toFile());
                    return true;
                }
            } catch (IOException | UnsupportedOperationException | SecurityException exception) {
                // Fall through to process-based launchers.
            }
        }

        List<String> command = fallbackOpenCommand(System.getProperty("os.name", ""), targetDirectory);
        if (command.isEmpty()) {
            return false;
        }
        try {
            new ProcessBuilder(command).start();
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    static List<String> fallbackOpenCommand(String osName, Path directory) {
        if (directory == null) {
            return List.of();
        }

        String normalizedOsName = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        String target = directory.toAbsolutePath().normalize().toString();
        if (normalizedOsName.contains("mac")) {
            return List.of("open", target);
        }
        if (normalizedOsName.contains("win")) {
            return List.of("explorer", target);
        }
        if (normalizedOsName.contains("nix") || normalizedOsName.contains("nux") || normalizedOsName.contains("linux")) {
            return List.of("xdg-open", target);
        }
        return List.of("xdg-open", target);
    }
}

